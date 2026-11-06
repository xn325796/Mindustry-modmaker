package dev.modmaker.core.io;

import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of a save, split so the model is never read from a worker thread.
 *
 * <p>{@link ProjectIo#plan} decides everything on the thread that owns the model and {@link
 * ProjectIo#write} only touches the disk. Serialising the field trees on the io thread while the FX
 * thread was editing the very same maps was the race this split removes, so the tests below pin both
 * halves of the contract: the write phase must not read the model, and a plan must not write.
 */
class ProjectIoSavePlanTest {

    private static final Path WORK = Paths.get("build", "save-plan").toAbsolutePath();

    @Test
    void writePhaseNeverReadsTheModelAgain() throws IOException {
        Path work = WORK.resolve("no-model-read");
        deleteRecursively(work);
        Path root = work.resolve("project");
        ModProject project = projectWithItem(root);
        ContentRecord item = project.contents.get(0);

        item.fields.put("hardness", new JsonPrimitive(5));
        item.dirty = true;
        ProjectIo.SavePlan plan = ProjectIo.plan(project, root);
        Path itemFile = firstFileUnder(root.resolve("content"));

        // Replace the model with something completely different. The write phase must not notice -
        // that is exactly what makes it safe to run on a worker thread while the user keeps editing.
        project.contents.clear();
        project.meta.name = "RenamedMidSave";
        ProjectIo.write(plan);

        assertTrue(Files.readString(itemFile).contains("hardness"),
            "the plan's content is what reaches the disk, not whatever the model says later");
        assertTrue(Files.readString(root.resolve("mod.hjson")).contains("My Mod"),
            "the meta the plan captured is what was written");
    }

    @Test
    void planningWritesNothingToDisk() throws IOException {
        Path work = WORK.resolve("plan-is-read-only");
        deleteRecursively(work);
        Path root = work.resolve("project");
        ModProject project = projectWithItem(root);
        ContentRecord item = project.contents.get(0);
        item.fields.put("hardness", new JsonPrimitive(5));
        item.dirty = true;

        Map<Path, byte[]> before = snapshotFiles(root);
        ProjectIo.SavePlan plan = ProjectIo.plan(project, root);

        Map<Path, byte[]> afterPlan = snapshotFiles(root);
        assertEquals(before.keySet(), afterPlan.keySet(), "planning must not add or remove files");
        for (Map.Entry<Path, byte[]> entry : before.entrySet()) {
            assertArrayEquals(entry.getValue(), afterPlan.get(entry.getKey()),
                "planning must not touch " + root.relativize(entry.getKey()));
        }

        ProjectIo.write(plan);
        assertTrue(Files.readString(firstFileUnder(root.resolve("content"))).contains("hardness"),
            "the write phase is what materialises the plan");
    }

    @Test
    void aFailedWriteCanPutTheUnsavedFlagsBack() throws IOException {
        Path work = WORK.resolve("failed-write");
        deleteRecursively(work);
        Path root = work.resolve("project");
        ModProject project = projectWithItem(root);
        ContentRecord item = project.contents.get(0);
        item.fields.put("hardness", new JsonPrimitive(5));
        item.dirty = true;
        project.meta.name = "Renamed Too";
        project.metaDirty = true;
        project.strings.markBundleDirty("en");

        // Host one target with a non-empty directory: the write cannot replace it, so the save fails
        // partway - the case where the model must not be left looking persisted.
        Path victim = firstFileUnder(root.resolve("content"));
        Files.delete(victim);
        Files.createDirectories(victim);
        Files.writeString(victim.resolve("occupied"), "not replaceable by a file");

        ProjectIo.SavePlan plan = ProjectIo.plan(project, root);
        assertThrows(IOException.class, () -> ProjectIo.write(plan));
        plan.rollback();

        assertTrue(item.dirty, "the edit must still count as unsaved after a failed write");
        assertTrue(project.metaDirty, "the meta edit must still count as unsaved");
        assertTrue(project.strings.isBundleDirty("en"), "the bundle must still count as unsaved");
    }

    @Test
    void failedWriteRestoresPreviousMetaFile() throws IOException {
        Path work = WORK.resolve("failed-meta-rollback");
        deleteRecursively(work);
        Path root = work.resolve("project");
        ModProject project = projectWithItem(root);
        // Simulate a format conversion that renamed mod.hjson -> mod.json and must delete the old.
        project.previousMetaFile = "mod.hjson";
        project.metaFile = "mod.json";
        project.metaDirty = true;

        Path victim = firstFileUnder(root.resolve("content"));
        Files.delete(victim);
        Files.createDirectories(victim);
        Files.writeString(victim.resolve("occupied"), "not replaceable by a file");

        ProjectIo.SavePlan plan = ProjectIo.plan(project, root);
        assertThrows(IOException.class, () -> ProjectIo.write(plan));
        plan.rollback();

        assertEquals("mod.hjson", project.previousMetaFile,
            "a failed write must restore previousMetaFile so the old meta is cleaned up next save");
        assertTrue(project.metaDirty, "the meta edit stays unsaved after a failed write");
    }

    private static ModProject projectWithItem(Path root) throws IOException {
        ModProject project = ProjectFactory.create("My Mod", root);
        ContentRecord item = ContentRecord.create("c1", ContentType.item, "steel-ingot");
        item.fields.put("color", new JsonPrimitive("#8899aa"));
        project.contents.add(item);
        ProjectIo.save(project, root);
        return project;
    }

    private static Path firstFileUnder(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile).sorted().findFirst().orElseThrow();
        }
    }

    private static Map<Path, byte[]> snapshotFiles(Path root) throws IOException {
        Map<Path, byte[]> snapshot = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                snapshot.put(file, Files.readAllBytes(file));
            }
        }
        return snapshot;
    }

    private static void deleteRecursively(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

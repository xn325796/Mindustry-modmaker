package dev.modmaker.core.io;

import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash recovery: an autosave is a self-contained snapshot under modmaker/autosave/ that is newer
 * than the last manual save only while there are unsaved edits, and {@link Autosave#restore} brings
 * those edits back without touching the mod's binary assets.
 */
class AutosaveTest {

    private static final Path WORK = Paths.get("build", "autosave").toAbsolutePath();

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.loadDefault();
    }

    private ModProject projectWithItem(Path root) throws IOException {
        ModProject project = ProjectFactory.create("My Mod", root);
        ContentRecord item = ContentRecord.create("c1", ContentType.item, "steel-ingot");
        item.fields.put("color", new JsonPrimitive("#8899aa"));
        project.contents.add(item);
        ProjectIo.save(project, root);
        return project;
    }

    @Test
    void writeProducesASnapshotNewerThanTheLastSave() throws IOException {
        Path root = WORK.resolve("newer").resolve("project");
        ModProject project = projectWithItem(root);
        assertFalse(Autosave.isNewerThan(root), "no autosave yet");

        ContentRecord item = project.contents.get(0);
        item.fields.put("color", new JsonPrimitive("#112233"));
        item.dirty = true;
        Autosave.write(project.snapshot());

        assertTrue(Autosave.exists(root));
        assertTrue(Autosave.isNewerThan(root), "the autosave holds edits the manual save does not");
    }

    @Test
    void restoreBringsBackTheAutosavedEditsAndDiscardsTheAutosave() throws IOException {
        Path root = WORK.resolve("restore").resolve("project");
        ModProject project = projectWithItem(root);

        ContentRecord item = project.contents.get(0);
        item.fields.put("color", new JsonPrimitive("#112233"));
        item.dirty = true;
        project.meta.description = "autosaved";
        project.metaDirty = true;
        Autosave.write(project.snapshot());

        // Edits made after the autosave are the ones a crash would lose.
        item.fields.put("color", new JsonPrimitive("#ffffff"));
        item.dirty = true;
        project.meta.description = "lost after crash";
        project.metaDirty = true;

        ModProject recovered = Autosave.restore(root, registry);

        assertEquals("#112233", recovered.contents.get(0).fields.get("color").getAsString(),
            "the recovered model must hold the autosaved value, not the later edit");
        assertEquals("autosaved", recovered.meta.description);
        assertFalse(Autosave.exists(root), "restore discards the autosave it consumed");
        // The restored state is what now lives on disk.
        ModProject reopened = ProjectIo.open(root, registry);
        assertEquals("#112233", reopened.contents.get(0).fields.get("color").getAsString());
        assertEquals("autosaved", reopened.meta.description);
    }

    @Test
    void discardRemovesTheAutosaveWithoutTouchingTheProject() throws IOException {
        Path root = WORK.resolve("discard").resolve("project");
        ModProject project = projectWithItem(root);
        project.contents.get(0).fields.put("color", new JsonPrimitive("#112233"));
        project.contents.get(0).dirty = true;
        Autosave.write(project.snapshot());
        assertTrue(Autosave.exists(root));

        Autosave.discard(root);

        assertFalse(Autosave.exists(root));
        assertTrue(Files.exists(root.resolve("mod.hjson")), "the real project is untouched");
        assertTrue(Files.exists(root.resolve("content")), "the real content is untouched");
    }
}

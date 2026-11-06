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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Building an archive must never damage the previous build. Packing straight into the destination
 * truncated it, so a failure halfway (an unreadable file, a full disk) destroyed the last good zip
 * and left a corrupt one behind; the build now packs into a sibling temp file and swaps it in only
 * once the archive is complete.
 */
class ModExporterAtomicityTest {

    private static final Path WORK = Paths.get("build", "exporter-atomicity").toAbsolutePath();

    @Test
    void aFailedBuildLeavesThePreviousArchiveByteForByteIntact() throws IOException {
        Path work = WORK.resolve("failed");
        deleteRecursively(work);
        Path root = work.resolve("project");
        Path zip = work.resolve("out").resolve("mod.zip");

        ModProject project = projectWithOneItem(root);
        ModExporter.buildZip(project, zip, List.of());
        byte[] previous = Files.readAllBytes(zip);
        assertTrue(previous.length > 0, "the first build must produce an archive");

        // Occupy the temp path with a directory: the pack step cannot open it for writing, which is
        // exactly the "failed halfway" case this guards against.
        Path temp = zip.resolveSibling(zip.getFileName() + ProjectIo.TEMP_SUFFIX);
        Files.createDirectories(temp);
        Files.writeString(temp.resolve("occupied"), "not writable as a file");

        assertThrows(IOException.class, () -> ModExporter.buildZip(project, zip, List.of()));

        assertArrayEquals(previous, Files.readAllBytes(zip),
            "a failed build must leave the previous archive untouched");
    }

    @Test
    void aSuccessfulBuildLeavesNoTemporaryFileBehind() throws IOException {
        Path work = WORK.resolve("clean");
        deleteRecursively(work);
        Path root = work.resolve("project");
        Path zip = work.resolve("out").resolve("mod.zip");

        ModProject project = projectWithOneItem(root);
        ModExporter.buildZip(project, zip, List.of());
        byte[] first = Files.readAllBytes(zip);

        assertTrue(Files.exists(zip), "the archive must exist");
        assertFalse(Files.exists(zip.resolveSibling(zip.getFileName() + ProjectIo.TEMP_SUFFIX)),
            "the temp file must be gone once the archive is in place");
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(zip.toFile())) {
            assertTrue(archive.stream().anyMatch(entry ->
                    entry.getName().startsWith(project.internalName() + "/")),
                "entries must sit under the wrapping folder the game unwraps");
        }

        // Rebuilding over an existing archive replaces it wholesale; the fixed timestamps make a
        // repeat build of an unchanged project deterministic.
        ModExporter.buildZip(project, zip, List.of());
        assertArrayEquals(first, Files.readAllBytes(zip), "a repeat build is deterministic");
    }

    private static ModProject projectWithOneItem(Path root) throws IOException {
        ModProject project = ProjectFactory.create("My Mod", root);
        ContentRecord item = ContentRecord.create("c1", ContentType.item, "steel-ingot");
        item.fields.put("color", new JsonPrimitive("#8899aa"));
        project.contents.add(item);
        ProjectIo.save(project, root);
        return project;
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

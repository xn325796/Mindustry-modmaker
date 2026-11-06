package dev.modmaker.core.io;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Security hardening: untrusted mod packages (zips downloaded from the internet) must not be able
 * to write outside the project folder, exhaust memory, or smuggle a dangerous internal name past
 * the import/export boundaries.
 */
class SecurityHardeningTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    /** Builds an in-memory zip: each value is the entry's text, or binary filler when large. */
    private static Path zipWith(java.util.LinkedHashMap<String, String> entries) throws IOException {
        Path zip = Files.createTempFile("mm-security-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip),
            StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void traversalEntriesAreSkippedAtTheSource() throws IOException {
        java.util.LinkedHashMap<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("mod.json", "{ \"name\": \"test-mod\", \"version\": \"1.0\" }");
        entries.put("content/blocks/../../evil-content.hjson", "type: GenericCrafter");
        entries.put("bundles/../../evil-bundle.properties", "key=value");
        entries.put("sprites/../evil-sprite.png", "not really a png");
        entries.put("/absolute.txt", "rooted path");
        entries.put("C:drive.txt", "drive letter");
        Path zip = zipWith(entries);

        ModPackage pkg = ModPackageReader.read(zip);
        assertTrue(pkg.skipped.stream().anyMatch(s -> s.contains("evil-content")),
            "the traversal entry is refused");
        assertTrue(pkg.skipped.stream().anyMatch(s -> s.contains("absolute.txt")));
        assertTrue(pkg.skipped.stream().anyMatch(s -> s.contains("C:drive.txt")));
        assertTrue(pkg.assetFiles.keySet().stream().noneMatch(p -> p.contains("..")));
        Files.deleteIfExists(zip);
    }

    @Test
    void unpackRefusesToWriteContentOrBundlesOutsideTheRoot() throws IOException {
        // Defence in depth: even if a traversal path reached the package, unpack would not
        // follow it (the asset branch already went through safeResolve; so do content/bundles).
        Path root = Path.of("build", "security", "zip-slip").toAbsolutePath();
        deleteRecursively(root);
        Files.createDirectories(root);
        Path outside = root.getParent().resolve("mm-zipslip-marker.txt");
        Files.deleteIfExists(outside);

        ModPackage pkg = new ModPackage();
        pkg.metaFile = "mod.json";
        pkg.metaText = "{ \"name\": \"test-mod\" }";
        ModPackage.SourceContent content = new ModPackage.SourceContent();
        content.path = "content/blocks/../../mm-zipslip-marker.txt";
        content.name = "evil";
        content.ext = "hjson";
        content.type = ContentType.block;
        content.text = "type: GenericCrafter";
        pkg.contents.add(content);
        pkg.bundleFiles.put("bundles/../../../evil.properties", "key=value");

        ModProject project = new ModProject();
        project.meta.name = "test-mod";
        ProjectIo.unpack(project, pkg, root);

        assertFalse(Files.exists(outside), "nothing may be written outside the project folder");
        assertTrue(pkg.skipped.stream().anyMatch(s -> s.contains("escapes")));
        deleteRecursively(root);
    }

    @Test
    void anUnsafeInternalNameIsRefusedByTheExporter() throws IOException {
        Path root = Path.of("build", "security", "unsafe-name").toAbsolutePath();
        deleteRecursively(root);
        Files.createDirectories(root);
        ModProject project = new ModProject();
        project.meta.name = "..\\..\\pwned";
        project.root = root;

        assertThrows(IOException.class, () -> ModExporter.buildFolder(project, root, List.of()));
        assertThrows(IOException.class,
            () -> ModExporter.buildFolder(project, root.getParent(), List.of()));
    }

    @Test
    void anUnsafeInternalNameIsRefusedByTheImport() throws IOException {
        Path destination = Files.createTempDirectory("mm-sec-dest");
        Path zip = zipWith(new java.util.LinkedHashMap<>() {{
            put("mod.json", "{ \"name\": \"..\\\\..\\\\pwned\", \"version\": \"1.0\" }");
        }});
        var controller = new dev.modmaker.ui.ProjectController(registry);
        assertThrows(IOException.class, () -> controller.importPackage(zip, destination));
        Files.deleteIfExists(zip);
    }

    @Test
    void anEntryFloodIsRefusedAsAProbableZipBomb() throws IOException {
        Path zip = Files.createTempFile("mm-flood-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip),
            StandardCharsets.UTF_8)) {
            for (int i = 0; i < 20_001; i++) {
                out.putNextEntry(new ZipEntry("content/blocks/fill-" + i + ".hjson"));
                out.write("type: GenericCrafter".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        assertThrows(IOException.class, () -> ModPackageReader.read(zip),
            "an entry flood must be refused before it exhausts memory");
        Files.deleteIfExists(zip);
    }

    @Test
    void aCorruptMetaFileNameCannotEscapeOnSave() throws IOException {
        // Defense in depth for S5: metaFile is normally probed or validated on open; writeMeta
        // refuses anything outside the game's four names.
        Path root = Path.of("build", "security", "meta-name").toAbsolutePath();
        deleteRecursively(root);
        Files.createDirectories(root);
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        project.metaFile = "..\\evil.json";
        project.metaDirty = true;

        ProjectIo.save(project, root);
        assertTrue(Files.exists(root.resolve("mod.hjson")), "the fallback meta is written");
        assertFalse(Files.exists(root.getParent().resolve("evil.json")),
            "nothing is written outside the project folder");
    }

    @Test
    void validContentStillImportsAfterTheHardening() throws IOException {
        // Guard against over-blocking: a plain mod must import exactly as before.
        java.util.LinkedHashMap<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("mod.hjson", "name: \"test-mod\"\nversion: \"1.0\"\n");
        entries.put("content/items/steel.hjson", "hardness: 3\n");
        Path zip = zipWith(entries);

        ModPackage pkg = ModPackageReader.read(zip);
        assertEquals(1, pkg.contents.size());
        assertTrue(pkg.skipped.isEmpty(), "no benign entry may be skipped: " + pkg.skipped);
        Files.deleteIfExists(zip);
    }

    @Test
    void aHostileBoardFileNameCannotOverwriteAnything() throws IOException {
        // The authoring layer is user-editable text, so "boards":[{"file":"../../mod.hjson"}] used
        // to make the next save overwrite the mod's own meta (and an absolute path overwrote any
        // file on disk). Both the read and the write side must normalise the name.
        Path root = Path.of("build", "security", "board-name").toAbsolutePath();
        Path outside = Path.of("build", "security", "board-name-outside").toAbsolutePath();
        deleteRecursively(root);
        deleteRecursively(outside);
        Files.createDirectories(root);
        Files.createDirectories(outside);
        Path victim = outside.resolve("victim.json");
        Files.writeString(victim, "{\"important\":\"do not touch\"}");

        String meta = "name: Victim\nauthor: Real Author\n";
        Files.writeString(root.resolve("mod.hjson"), meta);
        Files.createDirectories(root.resolve(ProjectIo.AUTHORING_DIR).resolve(ProjectIo.BOARDS_DIR));
        Files.writeString(root.resolve(ProjectIo.AUTHORING_DIR).resolve(ProjectIo.PROJECT_FILE),
            "{\"format\":1,\"metaFile\":\"mod.hjson\",\"records\":{},\"boards\":["
                + "{\"id\":\"b1\",\"name\":\"Main\",\"file\":\"../../mod.hjson\"},"
                + "{\"id\":\"b2\",\"name\":\"Second\",\"file\":\""
                + victim.toAbsolutePath().toString().replace("\\", "/") + "\"}]}");

        ModProject project = ProjectIo.open(root, registry);
        project.boards.forEach(board -> assertFalse(board.file.contains("..") || board.file.contains("/"),
            "a board file name is a bare .canvas name, saw '" + board.file + "'"));
        assertEquals("main.canvas", project.boards.get(0).file);

        // Even a hostile name injected into the model directly is normalised before it is written.
        project.boards.get(0).file = "..\\..\\escape.canvas";
        ProjectIo.save(project, root);

        assertEquals(meta, Files.readString(root.resolve("mod.hjson")),
            "the mod's own meta file must keep its content");
        assertEquals("{\"important\":\"do not touch\"}", Files.readString(victim),
            "nothing outside the project may be touched");
        assertFalse(Files.exists(root.getParent().resolve("escape.canvas")));
        assertFalse(project.boards.get(0).file.contains(".."));
        deleteRecursively(root);
        deleteRecursively(outside);
    }

    @Test
    void anOversizedZipEntryIsRefusedInsteadOfTruncated() throws IOException {
        // Before the fix, readNBytes(MAX_ENTRY_BYTES) made the following "length > MAX_ENTRY_BYTES"
        // check dead code: a 257 MiB entry was silently cut to 256 MiB (broken JSON, broken PNG)
        // instead of being refused.
        Path zip = Files.createTempFile("mm-huge-", ".zip");
        long size = 257L * 1024 * 1024;
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.setLevel(java.util.zip.Deflater.BEST_SPEED);
            out.putNextEntry(new ZipEntry("assets/huge.bin"));
            byte[] chunk = new byte[1024 * 1024];
            for (long written = 0; written < size; written += chunk.length) {
                out.write(chunk, 0, (int) Math.min(chunk.length, size - written));
            }
            out.closeEntry();
        }
        try {
            assertThrows(IOException.class, () -> ModPackageReader.read(zip),
                "an entry past the limit must be refused, not silently truncated");
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    @Test
    void aDirectoryEntryFloodIsRefusedToo() throws IOException {
        // The budget only counted files, so 30 000 empty directories were accepted and retained.
        Path zip = Files.createTempFile("mm-dirflood-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip),
            StandardCharsets.UTF_8)) {
            for (int i = 0; i < 20_001; i++) {
                out.putNextEntry(new ZipEntry("empty/" + i + "/"));
                out.closeEntry();
            }
        }
        try {
            assertThrows(IOException.class, () -> ModPackageReader.read(zip),
                "directory entries count toward the entry budget");
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    @Test
    void aFolderImportIsHeldToTheSameBudgetAsAZip() throws IOException {
        // readFolder had no budget at all while readZip refused the identical payload.
        Path folder = Files.createTempDirectory("mm-folderflood-");
        try {
            for (int i = 0; i < 20_001; i++) {
                Files.write(folder.resolve("fill-" + i + ".txt"), new byte[0]);
            }
            assertThrows(IOException.class, () -> ModPackageReader.read(folder),
                "a folder import must not bypass the entry budget a zip is held to");
        } finally {
            deleteRecursively(folder);
        }
    }

    @Test
    void illegalWindowsCharactersAreRefused() throws IOException {
        java.util.LinkedHashMap<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("mod.json", "{ \"name\": \"test-mod\" }");
        entries.put("content/items/a:b.json", "{ \"hardness\": 1 }");
        entries.put("sprites/bad?.png", "not a png");
        entries.put("bundles/bad|file.properties", "k=v");
        Path zip = zipWith(entries);

        ModPackage pkg = ModPackageReader.read(zip);
        assertTrue(pkg.contents.isEmpty(), "a colon in a path segment is refused, not resolved");
        assertTrue(pkg.assetFiles.keySet().stream().noneMatch(p -> p.contains("?")),
            "illegal characters never reach the asset set");
        assertTrue(pkg.skipped.size() >= 3, pkg.skipped.toString());
        Files.deleteIfExists(zip);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

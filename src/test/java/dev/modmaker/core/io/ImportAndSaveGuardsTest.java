package dev.modmaker.core.io;

import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regressions for defects that silently lost a file, a string or an edit: a second bundle for one
 * locale, two meta files at the root, a meta the reader could not parse, and crash leftovers.
 */
class ImportAndSaveGuardsTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    @Test
    void renamingOntoAnExistingNameIsRefusedInsteadOfLosingARecord() throws IOException {
        // Both records resolve to one file, so save() wrote the same path twice: the renamed
        // record's content was gone and the reopened project had one record fewer.
        Path root = Files.createTempDirectory("mm-rename-");
        try {
            dev.modmaker.ui.ProjectController controller = new dev.modmaker.ui.ProjectController(registry);
            controller.createProject("renamemod", root);
            dev.modmaker.core.model.ContentRecord kiln =
                controller.addContent(dev.modmaker.core.fmt.ContentType.block);
            kiln.name = "kiln";
            dev.modmaker.core.model.ContentRecord furnace =
                controller.addContent(dev.modmaker.core.fmt.ContentType.block);
            furnace.name = "furnace";
            controller.save();
            int before = controller.project().contents.size();

            controller.rename(furnace, "kiln");
            controller.save();

            assertEquals("furnace", furnace.name, "the colliding rename must be refused");
            assertEquals(before, ProjectIo.open(root, registry).contents.size(),
                "no record may be lost");
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void savingRefusesTwoRecordsThatResolveToOneFile() throws IOException {
        // Defense in depth behind the controller's rename guard: whatever produced the collision,
        // a save must fail loudly rather than overwrite one record's file with another's.
        Path root = Files.createTempDirectory("mm-dup-target-");
        try {
            ModProject project = new ModProject();
            project.meta.name = "dupmod";
            project.metaFile = "mod.hjson";
            project.metaText = "name: dupmod\n";
            for (String id : List.of("a", "b")) {
                dev.modmaker.core.model.ContentRecord record = new dev.modmaker.core.model.ContentRecord();
                record.id = id;
                record.type = dev.modmaker.core.fmt.ContentType.block;
                record.name = "same";
                record.rawPath = "content/blocks/same.json";
                record.raw = "{\"type\":\"GenericCrafter\"}";
                project.contents.add(record);
            }
            assertThrows(java.io.IOException.class, () -> ProjectIo.save(project, root));
        } finally {
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static List<String> filesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .sorted()
                .toList();
        }
    }

    @Test
    void aSecondBundleForOneLocaleSurvivesAsAnAssetInsteadOfVanishing() throws IOException {
        // bundle.properties is the base bundle and bundle_en.properties is the "en" bundle; the tool
        // keys both as "en", so one of them used to overwrite the other in the string table and was
        // simply not written out again. The loser must survive byte for byte.
        Path source = Files.createTempDirectory("mm-bundles-src-");
        Path target = Files.createTempDirectory("mm-bundles-out-");
        try {
            write(source.resolve("mod.hjson"), "name: bundlemod\nversion: 1.0\n");
            write(source.resolve("bundles/bundle.properties"),
                "# base bundle\nblock.bundlemod-core.name = Base Name\n");
            write(source.resolve("bundles/bundle_en.properties"),
                "# english bundle\nblock.bundlemod-core.name = English Name\n");

            ModPackage pkg = ModPackageReader.read(source);
            ModImporter.Result result = new ModImporter(registry).importPackage(pkg);
            ModProject project = result.project();

            assertEquals("bundle.properties", project.strings.bundleFiles.get("en"),
                "the base bundle wins the slot deterministically");
            assertFalse(result.report().warnings.isEmpty(), "the author must be told about the collision");

            ProjectIo.unpack(project, pkg, target);
            ProjectIo.save(project, target);
            List<String> bundles = filesUnder(target).stream()
                .filter(path -> path.startsWith("bundles/"))
                .toList();
            assertEquals(List.of("bundles/bundle.properties", "bundles/bundle_en.properties"), bundles,
                "both files must exist after an import and a save");
            assertEquals("# english bundle\nblock.bundlemod-core.name = English Name\n",
                Files.readString(target.resolve("bundles/bundle_en.properties"), StandardCharsets.UTF_8),
                "the extra file is kept verbatim");
        } finally {
            deleteRecursively(source);
            deleteRecursively(target);
        }
    }

    @Test
    void zipEntriesWithBackslashSeparatorsAreNotDropped() throws IOException {
        // Archives written by Windows tools store entry names with '\' separators; the reader
        // normalises them to '/', but ZipFile.getEntry only matches the stored name, so the file
        // used to vanish without a trace instead of being imported. The meta entry keeps the
        // single-root-prefix logic from stripping "content/" off the single content file.
        Path zip = Files.createTempFile("mm-backslash-", ".zip");
        try {
            try (java.util.zip.ZipOutputStream out =
                     new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new java.util.zip.ZipEntry("mod.hjson"));
                out.write("name: backslashmod\nversion: 1.0\n".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                out.putNextEntry(new java.util.zip.ZipEntry("content\\blocks\\duo.json"));
                out.write("{\"type\": \"Block\"}".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            ModPackage pkg = ModPackageReader.read(zip);
            assertTrue(pkg.contents.stream().anyMatch(content ->
                    content.path.equals("content/blocks/duo.json")),
                "the backslash entry must be imported, not silently skipped");
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    @Test
    void adoptingTheRecordedMetaFileKeepsItsOwnContentAndMetadata() throws IOException {
        // Two meta files at the root: the reader probes mod.json, the authoring layer records
        // mod.hjson. The save used to write mod.json's text into mod.hjson and then delete mod.json.
        Path root = Files.createTempDirectory("mm-dualmeta-");
        try {
            String hjson = "name: DualMeta\nversion: 2.0\n# a comment the user wrote\n";
            write(root.resolve("mod.json"), "{\n  \"name\": \"DualMeta\",\n  \"version\": \"1.0\"\n}");
            write(root.resolve("mod.hjson"), hjson);
            write(root.resolve("modmaker/project.json"),
                "{\"format\":" + ModProject.FORMAT + ",\"metaFile\":\"mod.hjson\",\"records\":{},\"boards\":[]}");

            ModProject project = ProjectIo.open(root, registry);
            assertEquals("mod.hjson", project.metaFile);
            assertEquals("2.0", project.meta.version, "the adopted file's metadata is the one parsed");
            assertEquals(hjson, project.metaText, "the adopted file's own text is the one kept");

            ProjectIo.save(project, root);
            assertEquals(hjson, Files.readString(root.resolve("mod.hjson"), StandardCharsets.UTF_8));
            assertFalse(Files.exists(root.resolve("mod.json")), "the stray probe file is cleaned up");
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void aFormatConversionLeavesAnUnparseableMetaAlone() throws IOException {
        // The record-level parse-failure guard existed, the meta-level one did not: converting
        // destroyed the file and replaced the metadata with an empty object.
        Path root = Files.createTempDirectory("mm-badmeta-");
        try {
            String broken = "name: Broken Mod\nauthor: Someone\nbroken: {\n";
            write(root.resolve("mod.hjson"), broken);

            ModProject project = ProjectIo.open(root, registry);
            assertTrue(project.metaParseFailed, "the failure must be carried into the project");

            FormatConverter.convert(project, "json");
            assertEquals(0, FormatConverter.pendingCount(project, "json"));
            ProjectIo.save(project, root);

            assertTrue(Files.exists(root.resolve("mod.hjson")), "the unreadable meta is not deleted");
            assertEquals(broken, Files.readString(root.resolve("mod.hjson"), StandardCharsets.UTF_8),
                "its bytes are untouched");
            assertFalse(Files.exists(root.resolve("mod.json")), "no empty meta is invented");
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void aCrashLeftoverTempFileIsNotAdoptedAsAnAsset() throws IOException {
        // writeText goes through "<target>.mm-tmp"; a crash leaves it behind. It used to be adopted
        // as a mod asset, so it was never pruned and was copied into every build.
        Path source = Files.createTempDirectory("mm-tmp-leftover-");
        Path target = Files.createTempDirectory("mm-tmp-out-");
        try {
            write(source.resolve("mod.hjson"), "name: tmpmod\nversion: 1.0\n");
            write(source.resolve("content/items/steel.json.json.mm-tmp"), "{\"half\": true}");
            write(source.resolve("sprites/icon.png.mm-tmp"), "broken png");

            ModPackage pkg = ModPackageReader.read(source);
            ModImporter.Result result = new ModImporter(registry).importPackage(pkg);
            assertTrue(result.project().assets.isEmpty(), "a temp leftover is not a mod asset: "
                + result.project().assetPaths());

            ProjectIo.unpack(result.project(), pkg, target);
            ProjectIo.save(result.project(), target);
            assertTrue(filesUnder(target).stream()
                    .noneMatch(path -> path.endsWith(ProjectIo.TEMP_SUFFIX)),
                "no temp leftover may reach the project: " + filesUnder(target));
            assertTrue(ModExporter.modFiles(target).stream()
                    .noneMatch(file -> file.getFileName().toString().endsWith(ProjectIo.TEMP_SUFFIX)),
                "the exporter skips it even if one appears inside the project");
        } finally {
            deleteRecursively(source);
            deleteRecursively(target);
        }
    }

    @Test
    void vcsMetadataIsNotImportedAsModPayload() throws IOException {
        // Mod folders are often git working trees. .git/ and the tracked .gitignore/.gitattributes
        // must not be shipped as assets, or they end up in the built package.
        Path source = Files.createTempDirectory("mm-vcs-noise-");
        try {
            write(source.resolve("mod.hjson"), "name: vcsmod\nversion: 1.0\n");
            write(source.resolve("content/blocks/a.json"), "{\"type\":\"GenericCrafter\"}");
            write(source.resolve(".git/config"), "[core]\n");
            write(source.resolve(".gitignore"), "*.zip\n");
            write(source.resolve(".gitattributes"), "* -text\n");
            write(source.resolve(".gitmodules"), "");

            ModPackage pkg = ModPackageReader.read(source);
            assertEquals(1, pkg.contents.size(), "content is still imported");
            assertTrue(pkg.assetFiles.isEmpty(), "no VCS file may become an asset: " + pkg.assetFiles.keySet());
            assertTrue(pkg.skipped.contains(".gitignore"), "skipped tracks the VCS files: " + pkg.skipped);
            assertTrue(pkg.skipped.contains(".git/config"), "skipped tracks the VCS directory: " + pkg.skipped);
        } finally {
            deleteRecursively(source);
        }
    }

    @Test
    void aSpriteWithAnUppercaseExtensionIsReportedAsUnpacked() throws IOException {
        // Mods.java packs only files whose extension is exactly "png"; the warning compared a
        // lower-cased path, so it could never fire.
        Path source = Files.createTempDirectory("mm-sprite-case-");
        try {
            write(source.resolve("mod.hjson"), "name: spritemod\nversion: 1.0\n");
            write(source.resolve("sprites/Icon.PNG"), "not really a png");
            write(source.resolve("sprites/other.png"), "not really a png");

            ModImporter.Result result = new ModImporter(registry)
                .importPackage(ModPackageReader.read(source));
            assertTrue(result.report().warnings.stream().anyMatch(warning -> warning.contains("Icon.PNG")),
                "the upper-case extension must be reported: " + result.report().warnings);
            assertTrue(result.report().warnings.stream().noneMatch(w -> w.contains("other.png")),
                "a correct extension must not be reported");
        } finally {
            deleteRecursively(source);
        }
    }
}

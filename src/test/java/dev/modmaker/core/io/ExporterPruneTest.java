package dev.modmaker.core.io;

import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Building twice must not resurrect deleted content: files left in the destination from an earlier
 * build that the project no longer contains are pruned and listed in the report. Pruning never runs
 * when the destination is the project folder itself.
 */
class ExporterPruneTest {

    private static final Path WORK = Paths.get("build", "prune").toAbsolutePath();

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.loadDefault();
    }

    @Test
    void staleFilesFromEarlierBuildsArePrunedAndReported() throws IOException {
        deleteRecursively(WORK);
        Path projectRoot = WORK.resolve("project");
        ModProject project = ProjectFactory.create("prune-mod", projectRoot);
        ProjectIo.save(project, projectRoot);

        Path buildDir = WORK.resolve("build");
        ModExporter.buildFolder(project, buildDir, List.of());

        // Simulate an earlier build that still contains content the project has since dropped.
        Path stale = buildDir.resolve("prune-mod/content/blocks/stale-block.json");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "{\"type\": \"Wall\"}");
        Path staleRoot = buildDir.resolve("prune-mod/leftover.json");
        Files.writeString(staleRoot, "{}");

        ExportReport report = ModExporter.buildFolder(project, buildDir, List.of());

        assertEquals(List.of("content/blocks/stale-block.json", "leftover.json"),
            report.pruned(), "both stale files are removed and listed");
        assertFalse(Files.exists(stale));
        assertFalse(Files.exists(staleRoot));
    }

    @Test
    void pruningSkipsTheProjectFolderItself() throws IOException {
        deleteRecursively(WORK);
        Path projectRoot = WORK.resolve("prune-mod");
        ModProject project = ProjectFactory.create("prune-mod", projectRoot);
        ProjectIo.save(project, projectRoot);
        // A non-mod file the user keeps next to the project; pruning must never eat it.
        Path note = projectRoot.resolve("notes.txt");
        Files.writeString(note, "do not delete");

        Path destination = projectRoot.getParent();
        ExportReport report = ModExporter.buildFolder(project, destination, List.of());

        assertEquals(List.of(), report.pruned(),
            "the target is the project folder, so nothing may be pruned");
        assertTrue(Files.exists(note));
    }

    @Test
    void aDifferentlyCasedAncestorTargetNeverPrunesTheProject() throws IOException {
        assumeTrue(isCaseInsensitiveFs(), "needs a case-insensitive filesystem");
        deleteRecursively(WORK);
        // Project inside a folder that shares the mod's internal name: <WORK>/out/project, build
        // destination <WORK> + internal name "OUT" gives modDir <WORK>/OUT, which case-insensitively
        // names the project's parent. The old string comparison was case-sensitive, so pruning ran
        // and deleted the project's own files.
        Path projectRoot = WORK.resolve("out").resolve("project");
        ModProject project = ProjectFactory.create("out", projectRoot);
        ProjectIo.save(project, projectRoot);
        Path note = projectRoot.resolve("notes.txt");
        Files.writeString(note, "do not delete");

        ModExporter.buildFolder(project, WORK, List.of());

        assertTrue(Files.exists(note),
            "a case-variant ancestor target must be refused pruning, exactly like the project itself");
        assertTrue(Files.exists(projectRoot.resolve("modmaker/project.json")),
            "the authoring layer inside the project must survive too");
    }

    @Test
    void aDifferentlyCasedTargetInsideTheProjectIsRefused() throws IOException {
        assumeTrue(isCaseInsensitiveFs(), "needs a case-insensitive filesystem");
        deleteRecursively(WORK);
        Path projectRoot = WORK.resolve("Nest").resolve("project");
        ModProject project = ProjectFactory.create("nest-mod", projectRoot);
        ProjectIo.save(project, projectRoot);
        // Case-insensitively the destination is inside the project folder, where every build would
        // nest another copy of the mod. Same segment count as the project path, different case.
        Path destination = WORK.resolve("nest").resolve("PROJECT").resolve("nested");

        assertThrows(IOException.class,
            () -> ModExporter.buildFolder(project, destination, List.of()));
    }

    @Test
    void vcsMetadataAndDevNoiseAreNotShipped() throws IOException {
        deleteRecursively(WORK);
        Path projectRoot = WORK.resolve("project");
        ModProject project = ProjectFactory.create("noise-mod", projectRoot);
        ProjectIo.save(project, projectRoot);

        // A mod folder that doubles as a git working tree plus build tooling. .git/objects used to
        // be packed into the zip, every blob registered as a game asset.
        Files.createDirectories(projectRoot.resolve(".git/objects"));
        Files.writeString(projectRoot.resolve(".git/config"), "[core]\n");
        Files.writeString(projectRoot.resolve(".gitignore"), "build/\n");
        Files.writeString(projectRoot.resolve(".gitattributes"), "* -text\n");
        Files.writeString(projectRoot.resolve("README.md"), "developer notes");
        Files.createDirectories(projectRoot.resolve("build/classes"));
        Files.writeString(projectRoot.resolve("build/classes/noise.class"), "binary");

        Path buildDir = WORK.resolve("out");
        ModExporter.buildFolder(project, buildDir, List.of());
        Path built = buildDir.resolve(project.internalName());

        assertFalse(Files.exists(built.resolve(".git/config")), ".git must not ship");
        assertFalse(Files.exists(built.resolve(".gitignore")), ".gitignore must not ship");
        assertFalse(Files.exists(built.resolve(".gitattributes")), ".gitattributes must not ship");
        assertFalse(Files.exists(built.resolve("README.md")), "README is not mod payload");
        assertFalse(Files.exists(built.resolve("build/classes/noise.class")),
            "build output must not ship");
        // The mod's own payload still ships.
        assertTrue(Files.exists(built.resolve("mod.hjson")), "meta still ships");
    }

    @Test
    void zipExcludesDotSegmentsAndDevNoise() throws IOException {
        deleteRecursively(WORK);
        Path projectRoot = WORK.resolve("project");
        ModProject project = ProjectFactory.create("noise-mod", projectRoot);
        ProjectIo.save(project, projectRoot);

        Files.createDirectories(projectRoot.resolve(".git/objects"));
        Files.writeString(projectRoot.resolve(".git/config"), "[core]\n");
        Files.writeString(projectRoot.resolve("README.md"), "developer notes");
        Files.createDirectories(projectRoot.resolve("sprites"));
        Files.writeString(projectRoot.resolve("sprites/icon.png"), "not really a png");

        Path zip = WORK.resolve("noise-mod.zip");
        ModExporter.buildZip(project, zip, List.of());

        List<String> names = zipEntryNames(zip);
        assertTrue(names.stream().noneMatch(name -> name.contains(".git")),
            "no dot segment may reach the zip: " + names);
        assertTrue(names.stream().noneMatch(name -> name.endsWith("README.md")),
            "README must not reach the zip: " + names);
        assertTrue(names.stream().anyMatch(name -> name.endsWith("sprites/icon.png")),
            "real assets still ship: " + names);
    }

    private static List<String> zipEntryNames(Path zip) throws IOException {
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(zip.toFile())) {
            return archive.stream().map(entry -> entry.getName()).sorted().toList();
        }
    }

    /** True when the filesystem ignores path case, so case-variant paths name the same folder. */
    private static boolean isCaseInsensitiveFs() throws IOException {
        Path probe = Files.createTempDirectory("mm-case-probe-");
        try {
            String name = probe.getFileName().toString();
            return Files.exists(probe.resolveSibling(name.toUpperCase(java.util.Locale.ROOT)));
        } finally {
            deleteRecursively(probe);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

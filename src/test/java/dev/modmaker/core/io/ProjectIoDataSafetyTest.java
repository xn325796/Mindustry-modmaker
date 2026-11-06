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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression tests for data-correctness fixes: verbatim files with a differing extension case
 * must not be deleted by their own save, parse-failed records must survive format conversion,
 * and a stale meta file must not resurrect or win over the real one.
 */
class ProjectIoDataSafetyTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    @Test
    void extensionCaseChangeDoesNotDeleteTheJustWrittenFile() throws IOException {
        // A mod ships content/blocks/DOV.JSON; the save normalises to DOV.json. On case-insensitive
        // filesystems these are the same file - the stale-path delete used to remove it.
        Path root = Path.of("build", "data-safety", "case-ext").toAbsolutePath();
        deleteRecursively(root);
        ModPackage pkg = new ModPackage();
        pkg.metaFile = "mod.json";
        ModPackage.SourceContent source = new ModPackage.SourceContent();
        source.path = "content/blocks/DOV.JSON";
        source.name = "DOV";
        source.ext = "json";
        source.type = ContentType.block;
        source.text = "{ \"type\": \"GenericCrafter\" }";
        source.fields.put("type", new JsonPrimitive("GenericCrafter"));
        pkg.contents.add(source);

        ModProject project = new ModImporter(registry).importPackage(pkg).project();
        ProjectIo.unpack(project, pkg, root);
        ProjectIo.save(project, root);

        assertTrue(Files.exists(root.resolve("content/blocks/DOV.json")),
            "the file must survive its own save");
        assertTrue(Files.readString(root.resolve("content/blocks/DOV.json"))
            .contains("GenericCrafter"));
        assertFalse(project.contents.get(0).dirty, "the verbatim record stays clean");
    }

    @Test
    void parseFailedRecordsSurviveFormatConversion() throws IOException {
        // A file that failed to parse has an empty field tree; converting must not rewrite it
        // as an empty object - it keeps the original bytes until fixed by hand.
        Path root = Path.of("build", "data-safety", "parse-failed").toAbsolutePath();
        deleteRecursively(root);
        ModPackage pkg = new ModPackage();
        pkg.metaFile = "mod.json";

        ModPackage.SourceContent broken = new ModPackage.SourceContent();
        broken.path = "content/blocks/broken.hjson";
        broken.name = "broken";
        broken.ext = "hjson";
        broken.type = ContentType.block;
        broken.text = "type: GenericCrafter\nbroken: {";
        broken.parseError = "Unexpected end of input";
        pkg.contents.add(broken);
        ModPackage.SourceContent fine = new ModPackage.SourceContent();
        fine.path = "content/items/steel.json";
        fine.name = "steel";
        fine.ext = "json";
        fine.type = ContentType.item;
        fine.text = "{ }";
        fine.fields.put("hardness", new JsonPrimitive(2));
        pkg.contents.add(fine);

        ModProject project = new ModImporter(registry).importPackage(pkg).project();
        ProjectIo.unpack(project, pkg, root);
        assertTrue(project.contents.get(0).parseFailed, "test setup: the broken file is flagged");

        dev.modmaker.core.io.FormatConverter.convert(project, "json");
        ProjectIo.save(project, root);

        assertEquals("hjson", project.contents.get(0).ext, "the broken record keeps its format");
        assertEquals("type: GenericCrafter\nbroken: {",
            Files.readString(root.resolve("content/blocks/broken.hjson")).stripTrailing(),
            "the original bytes are preserved verbatim");
        assertTrue(Files.exists(root.resolve("content/items/steel.json")));
    }

    @Test
    void externallyRenamedMetaIsAdoptedInsteadOfResurrected() throws IOException {
        Path root = Path.of("build", "data-safety", "meta-drift").toAbsolutePath();
        deleteRecursively(root);
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        ModPackage pkg = new ModPackage();
        pkg.metaFile = "mod.json";
        ProjectIo.unpack(project, pkg, root);
        assertTrue(Files.exists(root.resolve("modmaker/project.json")));

        // The user renames the meta file outside the tool, then reopens the project.
        Files.move(root.resolve("mod.json"), root.resolve("mod.hjson"));
        ModProject reopened = ProjectIo.open(root, registry);
        assertEquals("mod.hjson", reopened.metaFile, "the recorded name is gone; keep the probe");
        ProjectIo.save(reopened, root);
        assertTrue(Files.exists(root.resolve("mod.hjson")));
        assertFalse(Files.exists(root.resolve("mod.json")), "nothing resurrects the old name");
    }

    @Test
    void duplicateMetaFilesAreNormalisedToTheRecordedName() throws IOException {
        // The project's meta is mod.hjson; a stray mod.json appears (the game probes mod.json
        // FIRST, so the stray would shadow the real meta). Reopening must restore the recorded
        // name and clean the stray up on the next save.
        Path root = Path.of("build", "data-safety", "meta-dupe").toAbsolutePath();
        deleteRecursively(root);
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        ModPackage pkg = new ModPackage();
        pkg.metaFile = "mod.hjson";
        ProjectIo.unpack(project, pkg, root);
        Files.write(root.resolve("mod.json"), "name: \"My Mod\"\n".getBytes());

        ModProject reopened = ProjectIo.open(root, registry);
        assertEquals("mod.hjson", reopened.metaFile, "the authoring layer's name wins");
        assertEquals("mod.json", reopened.previousMetaFile);
        ProjectIo.save(reopened, root);
        assertTrue(Files.exists(root.resolve("mod.hjson")));
        assertFalse(Files.exists(root.resolve("mod.json")), "the stray meta is cleaned up");
    }

    @Test
    void buildTargetInsideTheProjectIsRefused() throws IOException {
        // Building into a subfolder of the project would nest a copy of the mod into itself
        // and grow on every build.
        Path root = Path.of("build", "data-safety", "self-nest").toAbsolutePath();
        deleteRecursively(root);
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        ModPackage pkg = new ModPackage();
        pkg.metaFile = "mod.json";
        ProjectIo.unpack(project, pkg, root);

        assertThrows(IOException.class,
            () -> ModExporter.buildFolder(project, root.resolve("build-out"), List.of()));
        assertThrows(IOException.class,
            () -> ModExporter.buildZip(project, root.resolve("mod.zip"), List.of()));
        // Building to the project's PARENT stays legal (that is how tests and run.bat use it).
        assertDoesNotThrow(() -> ModExporter.buildFolder(project, root.getParent(), List.of()));
    }

    @Test
    void editingOneStringKeepsTheBundlesCommentsAndLayout() throws IOException {
        Path root = Path.of("build", "data-safety", "bundle-lines").toAbsolutePath();
        deleteRecursively(root);
        String bundle = "# Kit bundle\n\n# content names\nblock.kit-kiln.name = Kiln\n"
            + "item.kit-dust.name = \nui.kit-title = Kit\n";
        ModPackage pkg = new ModPackage();
        pkg.metaFile = "mod.json";
        pkg.meta.name = "Kit";
        pkg.metaText = "{ \"name\": \"Kit\" }";
        pkg.bundleFiles.put("bundles/bundle.properties", bundle);
        ModPackage.SourceContent content = new ModPackage.SourceContent();
        content.path = "content/blocks/kiln.json";
        content.name = "kiln";
        content.ext = "json";
        content.type = ContentType.block;
        content.text = "{ \"type\": \"GenericCrafter\" }";
        content.fields.put("type", new JsonPrimitive("GenericCrafter"));
        pkg.contents.add(content);
        ModPackage.SourceContent item = new ModPackage.SourceContent();
        item.path = "content/items/dust.json";
        item.name = "dust";
        item.ext = "json";
        item.type = ContentType.item;
        item.text = "{ \"hardness\": 1 }";
        pkg.contents.add(item);

        ModProject project = new ModImporter(registry).importPackage(pkg).project();
        ProjectIo.unpack(project, pkg, root);
        ProjectIo.save(project, root);
        Path file = root.resolve("bundles").resolve("bundle.properties");
        assertEquals(bundle, Files.readString(file), "an untouched bundle is written back as it was");

        // Edit one string the way the strings panel does, then save again.
        project.strings.put("block.kit-kiln.name", "en", "Kiln II");
        project.strings.markBundleDirty("en");
        ProjectIo.save(project, root);

        String after = Files.readString(file);
        assertEquals(2, after.lines().filter(line -> line.startsWith("#")).count(),
            "the comments survive: " + after);
        assertEquals(bundle.lines().count(), after.lines().count(),
            "no line is added and none is lost: " + after);
        assertEquals("# Kit bundle\n\n# content names\nblock.kit-kiln.name = Kiln II\n"
            + "item.kit-dust.name = \nui.kit-title = Kit\n", after,
            "only the edited line differs - a key the file already ships empty keeps its line");
    }

    /** Removes files this tool manages that no longer correspond to anything in the project. */
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

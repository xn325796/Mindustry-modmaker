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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Bulk format conversion: flipping a project between .json and .hjson rewrites every file in the
 * new format, removes the old ones (content by pruning, meta explicitly), and survives a
 * reopen with identical field trees.
 */
class FormatConverterTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    private static ModProject project() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";

        ContentRecord json = ContentRecord.create("r1", ContentType.item, "steel-ingot");
        json.fields.put("hardness", new JsonPrimitive(3));
        project.contents.add(json);

        ContentRecord hjson = ContentRecord.create("r2", ContentType.block, "kiln");
        hjson.ext = "hjson";
        hjson.setClassName("GenericCrafter");
        hjson.fields.put("craftTime", new JsonPrimitive(60));
        project.contents.add(hjson);
        return project;
    }

    /** Unpacks the project with a plain mod.json meta so conversion has something to rename. */
    private static ModProject unpacked(Path root) throws IOException {
        ModProject project = project();
        ModPackage pkg = new ModPackage();
        pkg.metaFile = "mod.json";
        ProjectIo.unpack(project, pkg, root);
        return project;
    }

    @Test
    void conversionRewritesFilesAndPrunesTheOldOnes() throws IOException {
        Path root = Path.of("build", "format-convert", "to-hjson").toAbsolutePath();
        deleteRecursively(root);
        ModProject project = unpacked(root);
        assertTrue(Files.exists(root.resolve("mod.json")), "test setup: json meta");
        assertTrue(Files.exists(root.resolve("content/items/steel-ingot.json")));
        assertTrue(Files.exists(root.resolve("content/blocks/kiln.hjson")));

        assertEquals(1, FormatConverter.pendingCount(project, "hjson"),
            "before converting, the json record is pending");
        FormatConverter.convert(project, "hjson");
        assertEquals(0, FormatConverter.pendingCount(project, "hjson"),
            "after converting, nothing is pending");
        ProjectIo.save(project, root);

        assertTrue(Files.exists(root.resolve("content/items/steel-ingot.hjson")));
        assertFalse(Files.exists(root.resolve("content/items/steel-ingot.json")),
            "the old json file is pruned");
        assertFalse(Files.exists(root.resolve("mod.json")), "the old meta file is removed");
        assertTrue(Files.exists(root.resolve("mod.hjson")));
        String meta = Files.readString(root.resolve("mod.hjson"));
        assertTrue(meta.contains("name:"), "the meta is written as hjson, not canonical json");

        // Reopen sees the same data.
        ModProject reopened = ProjectIo.open(root, registry);
        assertEquals(2, reopened.contents.size());
        assertEquals(3, reopened.contentByName(ContentType.item, "steel-ingot")
            .fields.get("hardness").getAsInt());
        assertEquals(60, reopened.contentByName(ContentType.block, "kiln")
            .fields.get("craftTime").getAsInt());
    }

    @Test
    void convertingBackToJsonIsSymmetric() throws IOException {
        Path root = Path.of("build", "format-convert", "round-trip").toAbsolutePath();
        deleteRecursively(root);
        ModProject project = unpacked(root);

        FormatConverter.convert(project, "hjson");
        ProjectIo.save(project, root);
        FormatConverter.convert(project, "json");
        ProjectIo.save(project, root);

        assertTrue(Files.exists(root.resolve("mod.json")));
        assertFalse(Files.exists(root.resolve("mod.hjson")));
        assertTrue(Files.exists(root.resolve("content/items/steel-ingot.json")));
        assertTrue(Files.exists(root.resolve("content/blocks/kiln.json")),
            "the hjson block is converted too");
        assertFalse(Files.exists(root.resolve("content/blocks/kiln.hjson")));

        ModProject reopened = ProjectIo.open(root, registry);
        assertEquals("json", reopened.contentByName(ContentType.block, "kiln").ext);
        assertEquals(60, reopened.contentByName(ContentType.block, "kiln")
            .fields.get("craftTime").getAsInt());
        assertEquals(0, FormatConverter.pendingCount(reopened, "json"), "nothing left to convert");
    }

    @Test
    void convertingTwiceIsANoop() throws IOException {
        Path root = Path.of("build", "format-convert", "noop").toAbsolutePath();
        deleteRecursively(root);
        ModProject project = unpacked(root);

        FormatConverter.convert(project, "hjson");
        assertEquals(0, FormatConverter.pendingCount(project, "hjson"));
        assertFalse(FormatConverter.metaPending(project, "hjson"));
        assertFalse(project.contents.get(1).dirty,
            "the record that already was .hjson stays clean");
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

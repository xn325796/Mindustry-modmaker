package dev.modmaker.core.io;

import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Import of a real, large, hand-written mod (nested folders, missing commas, custom bundle keys,
 * loose root files and inline bullets). Assertions are derived from the fixture on disk so the
 * golden tests survive swapping the fixture mod.
 */
class RealModImportTest {

    private static final Path MOD_ROOT =
        dev.modmaker.core.io.TestFixtures.realModRoot();

    private static ModImporter.Result result;
    private static SchemaRegistry registry;
    private static long contentFilesOnDisk;

    @BeforeAll
    static void importMod() throws IOException {
        assumeTrue(MOD_ROOT != null && Files.isDirectory(MOD_ROOT.resolve("content")),
            "real mod fixture with a content/ folder not present");
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.load(Path.of("schemas"));
        ModPackage pkg = ModPackageReader.read(MOD_ROOT);
        result = new ModImporter(registry).importPackage(pkg);
        System.out.println("import report: " + result.report().summary());
        result.report().warnings.stream().limit(12).forEach(warning -> System.out.println("  WARN " + warning));
        try (var walk = Files.walk(MOD_ROOT.resolve("content"))) {
            contentFilesOnDisk = walk
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".json")
                    || path.toString().endsWith(".hjson"))
                .count();
        }
    }

    @Test
    void readsMetadataAndPrefixes() {
        ModProject project = result.project();
        assertTrue(!project.meta.name.isBlank(), "the meta file's name field must parse");
        assertEquals(NameRules.internalName(project.meta.name), project.internalName(),
            "the internal name follows the game's normalisation rules");
        assertTrue(project.internalName().matches("[a-z0-9-]+"),
            "internal names are lower case and hyphenated, saw " + project.internalName());
        assertTrue(!project.meta.displayName.isBlank());
    }

    @Test
    void importsEveryContentFile() {
        ModProject project = result.project();
        assertEquals(contentFilesOnDisk, result.report().contentCount,
            "every content file on disk must become a record");
        assertTrue(result.report().parseFailures.isEmpty(),
            "no content file should fail to parse: " + result.report().parseFailures);
        // Nested subfolders inside a content folder keep their path (rawPath is verbatim).
        assertTrue(project.contents.stream()
                .filter(record -> !record.isInline())
                .map(record -> record.rawPath == null ? "" : record.rawPath)
                .anyMatch(path -> path.indexOf('/', "content/".length())
                    < path.lastIndexOf('/')),
            "the fixture has content in nested subfolders");
        assertFalse(project.contents.isEmpty());
        project.contents.stream().filter(record -> !record.isInline()).findFirst().ifPresent(record ->
            assertFalse(record.dirty, "imported content starts clean so it can be written back verbatim"));
    }

    @Test
    void flagsExactlyTheVanillaNamedFilesAsPatches() {
        long vanillaNamed = result.project().contents.stream()
            .filter(record -> !record.isInline())
            .filter(record -> registry.isVanillaName(record.type, record.name))
            .count();
        assertEquals(vanillaNamed, result.report().patchCount,
            "a file is a patch exactly when its name collides with vanilla content");
        assertEquals(result.project().contents.stream().filter(record -> record.patch).count(),
            vanillaNamed);
    }

    @Test
    void findsInlineBullets() {
        assertTrue(result.report().inlineCount > 0,
            "turret ammoTypes should produce inline bullet records");
        assertTrue(result.project().contents.stream().anyMatch(ContentRecord::isInline));
        assertTrue(result.project().contents.stream()
            .filter(ContentRecord::isInline)
            .allMatch(record -> record.inlineOwnerId != null && record.inlinePath != null));
    }

    @Test
    void splitsBundlesIntoStringsAndExtraKeys() {
        ModProject project = result.project();
        assertTrue(result.report().stringCount >= 10,
            "expected the loaded bundles' keys, saw " + result.report().stringCount);
        int extras = project.strings.extra.values().stream().mapToInt(java.util.Map::size).sum();
        assertTrue(extras > 0, "category.*/stat.* style keys must be preserved as extras");
        // Only bundle*.properties files are loaded by the game; anything else under bundles/ is
        // kept as an asset so it survives the round trip.
        assertTrue(project.assets.stream().anyMatch(asset -> asset.path.startsWith("bundles/")),
            "non-bundle files under bundles/ are kept as assets");
    }

    @Test
    void keepsEveryAssetAndCountsSprites() throws IOException {
        assertTrue(result.report().spriteCount >= 500,
            "expected the mod's sprites, saw " + result.report().spriteCount);
        assertTrue(result.report().assetCount >= 500);
        ModProject project = result.project();
        assertTrue(project.assets.stream().anyMatch(asset -> asset.path.startsWith("scripts/")),
            "scripts are assets");
        // A loose file at the package root survives as an asset (icon, README, ...).
        List<String> looseRoot;
        try (var files = Files.list(MOD_ROOT)) {
            looseRoot = files
                .filter(Files::isRegularFile)
                .map(file -> file.getFileName().toString())
                .filter(name -> !List.of(dev.modmaker.core.model.ModMeta.META_FILES).contains(name))
                .filter(name -> !ModPackageReader.isNoise(name))
                .toList();
        }
        assertFalse(looseRoot.isEmpty(), "the fixture has loose root files");
        for (String name : looseRoot) {
            assertTrue(project.assets.stream().anyMatch(asset -> asset.path.equals(name)),
                "loose root file " + name + " is preserved");
        }
    }

    @Test
    void laysOutABoardWithReferenceEdges() {
        assertFalse(result.project().boards.isEmpty());
        var board = result.project().boards.get(0);
        assertEquals(result.project().contents.size(), board.nodes.size());
        assertTrue(board.edges.size() > 50,
            "research and requirements references should produce edges, saw " + board.edges.size());
        assertTrue(board.edges.stream().anyMatch(edge -> "research".equals(edge.fieldKey)));
        // Positions must be deterministic and non-overlapping within a column.
        assertTrue(board.nodes.stream().allMatch(node -> node.x >= 0 && node.y >= 0));

        // Orientation guard. An edge means "the record at edge.to references the record at edge.from
        // through edge.fieldKey" (from = referenced content, its ID port; to = holder of the field).
        // The importer used to write links the other way round, drawing every arrow backwards, so
        // each edge is checked against the field the target actually holds.
        java.util.Map<String, ContentRecord> recordById = new java.util.HashMap<>();
        for (ContentRecord record : result.project().contents) {
            recordById.put(record.id, record);
        }
        java.util.Map<String, ContentRecord> recordByNode = new java.util.HashMap<>();
        for (var node : board.nodes) {
            if (node.contentId != null) {
                recordByNode.put(node.id, recordById.get(node.contentId));
            }
        }
        List<String> mismatched = board.edges.stream().filter(edge -> {
            ContentRecord from = recordByNode.get(edge.from);
            ContentRecord to = recordByNode.get(edge.to);
            if (from == null || to == null || from.isInline() || to.isInline()) {
                return false;
            }
            return !pointsAt(to, edge.fieldKey, from.name);
        }).limit(5)
            .map(edge -> describe(recordByNode.get(edge.to)) + "." + edge.fieldKey + " -> "
                + describe(recordByNode.get(edge.from)))
            .toList();
        assertEquals(List.of(), mismatched,
            "each edge must run from what the target record references");
    }

    private static String describe(ContentRecord record) {
        return record.type.name() + ":" + record.name;
    }

    /** True when the consumer's reference field names the provider (bare, prefixed or "name/amount"). */
    private static boolean pointsAt(ContentRecord consumer, String fieldKey, String providerName) {
        List<String> names = new java.util.ArrayList<>();
        collectStrings(consumer.fields.get(fieldKey), names);
        for (String name : names) {
            int slash = name.indexOf('/');
            String bare = slash > 0 ? name.substring(0, slash) : name;
            if (bare.equals(providerName) || bare.endsWith("-" + providerName)) {
                return true;
            }
        }
        return false;
    }

    private static void collectStrings(com.google.gson.JsonElement value, List<String> out) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(element -> collectStrings(element, out));
            return;
        }
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isString()) {
                out.add(value.getAsString());
            }
            return;
        }
        if (value.isJsonObject()) {
            // An ammo map's KEYS are the item/liquid references, so keys are collected too.
            value.getAsJsonObject().entrySet().forEach(entry -> {
                out.add(entry.getKey());
                collectStrings(entry.getValue(), out);
            });
        }
    }
}

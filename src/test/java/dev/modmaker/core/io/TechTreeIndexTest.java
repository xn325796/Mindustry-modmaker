package dev.modmaker.core.io;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.TechTreeRules;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The tech tree the records describe: parents, roots, and the cases the game warns about. */
class TechTreeIndexTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadSchema() throws Exception {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    private static ModProject project() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        return project;
    }

    /** A record with no research at all: content that is not part of the tech tree. */
    private static ContentRecord record(ModProject project, String name) {
        ContentRecord record = ContentRecord.create("id-" + name, ContentType.block, name);
        project.contents.add(record);
        return record;
    }

    private static ContentRecord rootNode(ModProject project, String name) {
        ContentRecord record = record(project, name);
        JsonObject research = new JsonObject();
        research.addProperty("root", true);
        record.fields.put(TechTreeRules.FIELD, research);
        return record;
    }

    private static ContentRecord childOf(ModProject project, String name, String parent) {
        ContentRecord record = record(project, name);
        record.fields.put(TechTreeRules.FIELD, new JsonPrimitive(parent));
        return record;
    }

    @Test
    void rootsAndChildrenAreLinkedByTheResearchParent() {
        ModProject project = project();
        rootNode(project, "core");
        childOf(project, "washer", "core");
        childOf(project, "press", "washer");

        TechTreeIndex.Forest forest = TechTreeIndex.build(project, registry);

        assertEquals(1, forest.roots().size());
        TechTreeIndex.Entry core = forest.roots().get(0);
        assertEquals("core", core.label());
        assertEquals(TechTreeIndex.Problem.none, core.problem());
        assertEquals(1, core.children().size());
        assertEquals("washer", core.children().get(0).label());
        assertEquals("press", core.children().get(0).children().get(0).label());
        assertTrue(forest.unassigned().isEmpty(), "every record here carries research");
    }

    @Test
    void bothTheBareAndThePrefixedParentNameReachTheModsOwnNode() {
        ModProject project = project();
        String internal = project.internalName();
        rootNode(project, "core");
        childOf(project, "bare-child", "core");
        childOf(project, "prefixed-child", internal + "-core");

        TechTreeIndex.Forest forest = TechTreeIndex.build(project, registry);

        assertEquals(1, forest.roots().size());
        assertEquals(2, forest.roots().get(0).children().size(),
            "both spellings the game's parent lookup accepts must attach");
        assertTrue(forest.unresolvedParents().isEmpty());
    }

    @Test
    void aParentThatIsNotATreeNodeLeavesTheChildUnresolved() {
        ModProject project = project();
        // The record exists, but it carries no research, so it never enters TechTree.all and the
        // game's lookup cannot find it.
        record(project, "no-research");
        childOf(project, "child", "no-research");

        TechTreeIndex.Forest forest = TechTreeIndex.build(project, registry);

        assertEquals(1, forest.unresolvedParents().size());
        assertTrue(forest.roots().stream()
            .anyMatch(entry -> entry.problem() == TechTreeIndex.Problem.unresolvedParent));
    }

    @Test
    void aMissingParentIsFlaggedAndStillShown() {
        ModProject project = project();
        childOf(project, "orphan", "no-such-content");

        TechTreeIndex.Forest forest = TechTreeIndex.build(project, registry);

        assertEquals(1, forest.unresolvedParents().size());
        TechTreeIndex.Entry orphan = forest.unresolvedParents().get(0);
        assertEquals("orphan", orphan.label());
        assertTrue(forest.roots().contains(orphan), "an unresolvable node is still visible, as its own root");
    }

    @Test
    void aNodeWithNeitherParentNorRootIsFlaggedAsIgnored() {
        ModProject project = project();
        ContentRecord lonely = record(project, "lonely");
        lonely.fields.put(TechTreeRules.FIELD, new JsonObject());

        TechTreeIndex.Forest forest = TechTreeIndex.build(project, registry);

        assertEquals(1, forest.ignored().size());
        assertEquals("lonely", forest.ignored().get(0).label());
        assertTrue(forest.unresolvedParents().isEmpty());
    }

    @Test
    void aPatchCarryingResearchIsRefusedAndKeptOutOfTheTree() {
        ModProject project = project();
        ContentRecord patch = childOf(project, "kiln", "router");
        patch.patch = true;

        TechTreeIndex.Forest forest = TechTreeIndex.build(project, registry);

        assertEquals(1, forest.patchesWithResearch().size());
        assertEquals(TechTreeIndex.Problem.patchRefused, forest.patchesWithResearch().get(0).problem());
        assertTrue(forest.roots().isEmpty(), "the game never creates a node for a patch");
    }

    @Test
    void aVanillaParentBecomesAReadOnlyNode() {
        ModProject project = project();
        childOf(project, "smelter", "router");

        TechTreeIndex.Forest forest = TechTreeIndex.build(project, registry);

        assertEquals(1, forest.roots().size());
        TechTreeIndex.Entry vanilla = forest.roots().get(0);
        assertTrue(vanilla.vanilla());
        assertEquals("router", vanilla.label());
        assertNull(vanilla.contentId(), "vanilla content has no record here");
        assertEquals(1, vanilla.children().size());
        assertEquals("smelter", vanilla.children().get(0).label());
    }

    /**
     * The parent lookup scans a list the vanilla trees filled first (ContentParser.java:1401,
     * ContentLoader.java:72-79), so a bare name the mod and vanilla both define binds to vanilla -
     * exactly what the reference validator warns about. Only the prefixed spelling reaches the
     * mod's own node.
     */
    @Test
    void aBareNameVanillaAlsoDefinesBindsToTheVanillaNode() {
        ModProject project = project();
        rootNode(project, "kiln");
        childOf(project, "press", "kiln");
        childOf(project, "press2", project.internalName() + "-kiln");

        TechTreeIndex.Forest forest = TechTreeIndex.build(project, registry);

        assertEquals(2, forest.roots().size());
        TechTreeIndex.Entry vanilla = forest.roots().stream()
            .filter(TechTreeIndex.Entry::vanilla).findFirst().orElseThrow();
        assertEquals("kiln", vanilla.label());
        assertEquals("press", vanilla.children().get(0).label());
        TechTreeIndex.Entry own = forest.roots().stream()
            .filter(entry -> !entry.vanilla()).findFirst().orElseThrow();
        assertEquals("kiln", own.label());
        assertEquals("press2", own.children().get(0).label());
    }

    @Test
    void aRecordWithoutResearchIsListedAsUnassigned() {
        ModProject project = project();
        rootNode(project, "core");
        record(project, "plain");

        TechTreeIndex.Forest forest = TechTreeIndex.build(project, registry);

        assertEquals(1, forest.unassigned().size());
        assertEquals("plain", forest.unassigned().get(0).label());
        assertEquals(1, forest.roots().size());
    }
}

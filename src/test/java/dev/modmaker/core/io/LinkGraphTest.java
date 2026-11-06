package dev.modmaker.core.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.CanvasNode;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The link graph is the one place that decides what a reference edge means: {@code from} is the
 * referenced content (its ID port), {@code to} is the record whose reference field {@code fieldKey}
 * holds that reference. The importer used to write edges the other way round, which drew every
 * imported arrow backwards and left the field behind when the edge was deleted - the canvas and the
 * records were two different truths. These tests pin the single convention down.
 */
class LinkGraphTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.loadDefault();
    }

    private static ContentRecord item(String name) {
        return ContentRecord.create("item-" + name, ContentType.item, name);
    }

    private static ContentRecord block(String name, String... items) {
        ContentRecord record = ContentRecord.create("block-" + name, ContentType.block, name);
        JsonArray requirements = new JsonArray();
        for (String item : items) {
            JsonObject stack = new JsonObject();
            stack.addProperty("item", item);
            stack.addProperty("amount", 1);
            requirements.add(stack);
        }
        record.fields.put("requirements", requirements);
        return record;
    }

    private static CanvasNode node(String nodeId, String contentId) {
        CanvasNode node = new CanvasNode();
        node.id = nodeId;
        node.contentId = contentId;
        return node;
    }

    /** A project whose board carries one card per record but no links yet. */
    private static ModProject project(ContentRecord... records) {
        ModProject project = new ModProject();
        project.meta.name = "test-mod";
        Board board = new Board();
        board.id = "b1";
        board.file = "main.canvas";
        for (ContentRecord record : records) {
            project.contents.add(record);
            board.nodes.add(node("n-" + record.id, record.id));
        }
        project.boards.add(board);
        return project;
    }

    private static CanvasEdge edge(String from, String to, String fieldKey) {
        CanvasEdge edge = new CanvasEdge();
        edge.id = "e-" + from + "-" + to;
        edge.from = from;
        edge.to = to;
        edge.fieldKey = fieldKey;
        return edge;
    }

    @Test
    void edgesPointFromTheReferencedContentToTheFieldThatHoldsIt() {
        ContentRecord steel = item("steel");
        ContentRecord smelter = block("smelter", "steel");
        ModProject project = project(steel, smelter);

        List<CanvasEdge> edges = LinkGraph.derive(project, project.boards.get(0), registry);
        assertEquals(1, edges.size());
        CanvasEdge edge = edges.get(0);
        assertEquals("n-" + steel.id, edge.from, "the arrow leaves the referenced item");
        assertEquals("n-" + smelter.id, edge.to, "and enters the record that requires it");
        assertEquals("requirements", edge.fieldKey);
        assertEquals("steel", edge.label);
        // The field key must name a field the *target* record actually has, or the canvas would
        // draw the arrow into an input port that does not exist.
        String targetClass = registry.resolveClass(smelter.type, smelter.className());
        assertTrue(registry.fieldsOf(targetClass).stream()
            .anyMatch(field -> field.name().equals(edge.fieldKey) && field.isReference()));
    }

    @Test
    void derivationIsDeterministic() {
        ModProject project = project(item("steel"), item("nickel"),
            block("smelter", "steel", "nickel"));
        List<String> first = keys(LinkGraph.derive(project, project.boards.get(0), registry));
        List<String> again = keys(LinkGraph.derive(project, project.boards.get(0), registry));
        assertEquals(first, again);
        assertEquals(2, first.size());
    }

    @Test
    void diffReportsOrphansMissingAndStaleLinks() {
        ContentRecord steel = item("steel");
        ContentRecord smelter = block("smelter", "steel");
        ModProject project = project(steel, smelter);
        Board board = project.boards.get(0);

        assertFalse(LinkGraph.diff(project, board, registry).clean(), "no links drawn yet");
        board.edges.addAll(LinkGraph.derive(project, board, registry));
        assertTrue(LinkGraph.diff(project, board, registry).clean(),
            "a board holding the derived links matches the records");

        // A card whose content is gone, the real link removed, and a link pointing the wrong way.
        board.nodes.add(node("n-ghost", "no-such-record"));
        board.edges.clear();
        board.edges.add(edge("n-" + smelter.id, "n-" + steel.id, "requirements"));

        LinkGraph.Diff diff = LinkGraph.diff(project, board, registry);
        assertEquals(1, diff.orphanNodes(), "the ghost card has no record");
        assertEquals(1, diff.missingEdges(), "the real reference has no link");
        assertEquals(1, diff.staleEdges(), "the backwards link is not what the records say");
        assertFalse(diff.clean());
    }

    @Test
    void linksIntoVanillaCarriersAreNeverStaleAndSurviveARebuild() {
        ContentRecord smelter = block("smelter", "steel");
        ModProject project = project(smelter);
        Board board = project.boards.get(0);

        // A carrier card bound to a vanilla name, plus the author's hand-drawn reference into it.
        CanvasNode carrier = new CanvasNode();
        carrier.id = "n-carrier";
        carrier.refName = "copper";
        carrier.refType = "item";
        board.nodes.add(carrier);
        board.edges.add(edge("n-carrier", "n-" + smelter.id, "requirements"));

        assertEquals(0, LinkGraph.diff(project, board, registry).staleEdges(),
            "a link into a card with no record is the author's, not the importer's");

        LinkGraph.Rebuild rebuild = LinkGraph.rebuild(project, board, registry);
        assertEquals(0, rebuild.removed(), "rebuild must not drop the vanilla link");
        assertTrue(keys(rebuild.edges()).contains("n-carrier->n-" + smelter.id + "#requirements"));
    }

    @Test
    void rebuildAddsMissingLinksAndDropsStaleOnes() {
        ContentRecord steel = item("steel");
        ContentRecord smelter = block("smelter", "steel");
        ModProject project = project(steel, smelter);
        Board board = project.boards.get(0);
        board.edges.add(edge("n-" + smelter.id, "n-" + steel.id, "requirements"));

        LinkGraph.Rebuild rebuild = LinkGraph.rebuild(project, board, registry);
        assertEquals(1, rebuild.added());
        assertEquals(1, rebuild.removed());
        assertEquals(keys(LinkGraph.derive(project, board, registry)), keys(rebuild.edges()));
    }

    private static List<String> keys(List<CanvasEdge> edges) {
        Set<String> keys = new LinkedHashSet<>();
        for (CanvasEdge edge : edges) {
            keys.add(edge.from + "->" + edge.to + "#" + edge.fieldKey);
        }
        return List.copyOf(keys);
    }

    // --- structured multi-reference groups -------------------------------------------------------

    @Test
    void structFieldsDeriveEdgesByTheirShape() {
        ContentRecord factory = ContentRecord.create("b-ground-factory", ContentType.block,
            "ground-factory");
        ContentRecord dagger = ContentRecord.create("u-dagger", ContentType.unit, "dagger");
        ContentRecord copper = item("copper");
        ContentRecord crafter = ContentRecord.create("b-crafter", ContentType.block, "crafter");

        // Unit requirement: {block: ground-factory, requirements: [copper/5]}.
        JsonObject req = new JsonObject();
        req.addProperty("block", "ground-factory");
        JsonArray costs = new JsonArray();
        costs.add(new com.google.gson.JsonPrimitive("copper/5"));
        req.add("requirements", costs);
        dagger.fields.put("requirements", req);

        // Consume group: {items: [copper/10]}.
        JsonObject group = new JsonObject();
        JsonArray items = new JsonArray();
        items.add(new com.google.gson.JsonPrimitive("copper/10"));
        group.add("items", items);
        crafter.fields.put("consumes", group);

        ModProject structProject = project(factory, dagger, copper, crafter);
        var keys = keys(LinkGraph.derive(structProject, structProject.boards.get(0), registry));
        assertTrue(keys.contains("n-b-ground-factory->n-u-dagger#requirements"),
            "the unit's requirement names its factory block, saw " + keys);
        assertTrue(keys.contains("n-item-copper->n-u-dagger#requirements"),
            "the cost stack inside the UnitReq is an edge too, saw " + keys);
        assertTrue(keys.contains("n-item-copper->n-b-crafter#consumes"),
            "a consume group's items derive edges, saw " + keys);
    }

    @Test
    void ammoMapKeysDeriveEdgesToTheItems() {
        ContentRecord copper = item("copper");
        ContentRecord turret = ContentRecord.create("b-duo", ContentType.block, "duo");
        turret.setClassName("ItemTurret");
        JsonObject ammo = new JsonObject();
        ammo.add("copper", new JsonObject());
        turret.fields.put("ammoTypes", ammo);

        ModProject project = project(copper, turret);
        var keys = keys(LinkGraph.derive(project, project.boards.get(0), registry));
        assertTrue(keys.contains("n-item-copper->n-b-duo#ammoTypes"),
            "the ammo map key is the item reference, saw " + keys);
    }
}

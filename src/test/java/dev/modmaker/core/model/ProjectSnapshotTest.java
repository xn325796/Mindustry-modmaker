package dev.modmaker.core.model;

import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot is what makes background validation safe: it has to be a real copy, or the worker
 * would still be reading the FX thread's field trees.
 */
class ProjectSnapshotTest {

    private ModProject project() {
        ModProject project = new ModProject();
        project.meta.name = "snapshot-test";
        project.metaFile = "mod.json";
        project.meta.contentOrder.add("thing");
        project.strings.put("item.snapshot-test-thing.name", "en", "Thing");

        ContentRecord record = ContentRecord.create("c1", ContentType.item, "thing");
        JsonObject requirements = new JsonObject();
        requirements.addProperty("amount", 3);
        record.fields.put("requirements", requirements);
        project.contents.add(record);

        Board board = new Board();
        board.id = "b1";
        CanvasNode node = new CanvasNode();
        node.id = "n1";
        node.contentId = "c1";
        board.nodes.add(node);
        project.boards.add(board);

        project.assets.add(new AssetRef("sprites/blocks/environment/floor.png", 12, "sprites"));
        return project;
    }

    @Test
    void mutatingTheOriginalDoesNotChangeTheSnapshot() {
        ModProject project = project();
        ModProject snapshot = project.snapshot();

        project.contents.get(0).name = "renamed";
        project.contents.get(0).fields.get("requirements").getAsJsonObject().addProperty("amount", 9);
        project.boards.get(0).nodes.get(0).x = 500;
        project.strings.put("item.snapshot-test-thing.name", "en", "Renamed");

        assertEquals("thing", snapshot.contents.get(0).name);
        assertEquals(3, snapshot.contents.get(0).fields.get("requirements").getAsJsonObject()
            .get("amount").getAsInt());
        assertEquals(0, snapshot.boards.get(0).nodes.get(0).x, 0.001);
        assertEquals("Thing", snapshot.strings.get("item.snapshot-test-thing.name", "en"));
    }

    @Test
    void mutatingTheSnapshotDoesNotChangeTheOriginal() {
        ModProject project = project();
        ModProject snapshot = project.snapshot();

        snapshot.contents.get(0).name = "other";
        snapshot.contents.get(0).fields.remove("requirements");
        snapshot.meta.contentOrder.clear();
        snapshot.assets.clear();

        assertEquals("thing", project.contents.get(0).name);
        assertTrue(project.contents.get(0).fields.containsKey("requirements"));
        assertEquals(1, project.meta.contentOrder.size());
        assertEquals(1, project.assets.size());
    }

    @Test
    void recordsAndNestedValuesAreNewObjects() {
        ModProject project = project();
        ModProject snapshot = project.snapshot();

        assertNotSame(project.contents.get(0), snapshot.contents.get(0));
        assertNotSame(project.contents.get(0).fields.get("requirements"),
            snapshot.contents.get(0).fields.get("requirements"),
            "nested values must be deep-copied: editors mutate them in place");
        assertNotSame(project.meta, snapshot.meta);
        assertNotSame(project.strings, snapshot.strings);
        assertNotSame(project.boards.get(0), snapshot.boards.get(0));
        assertFalse(snapshot.contents.isEmpty());
    }
}

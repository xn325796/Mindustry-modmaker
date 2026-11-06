package dev.modmaker.core.io;

import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.CanvasNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carrier cards that point at vanilla content carry a direct reference name instead of a record id;
 * the canvas file must round-trip that binding unchanged.
 */
class JsonCanvasTest {

    @Test
    void refNameCarriersRoundTrip() {
        Board board = new Board();
        board.id = "b1";
        board.file = "main.canvas";
        board.name = "main";

        CanvasNode carrier = new CanvasNode();
        carrier.id = "n1";
        carrier.kind = CanvasNode.KIND_CONTENT;
        carrier.refName = "copper";
        carrier.refType = "item";
        carrier.x = 10;
        carrier.y = 20;
        board.nodes.add(carrier);

        CanvasNode recordCard = new CanvasNode();
        recordCard.id = "n2";
        recordCard.contentId = "c42";
        board.nodes.add(recordCard);

        var json = JsonCanvas.toCanvas(null, board);
        Board restored = JsonCanvas.fromCanvas(json.toString(), "main.canvas", "b1");

        CanvasNode restoredCarrier = restored.nodes.get(0);
        assertEquals("copper", restoredCarrier.refName);
        assertEquals("item", restoredCarrier.refType);
        assertNull(restoredCarrier.contentId);
        assertEquals(10, restoredCarrier.x, 0.001);

        CanvasNode restoredRecordCard = restored.nodes.get(1);
        assertEquals("c42", restoredRecordCard.contentId);
        assertNull(restoredRecordCard.refName);
    }

    /** Reroute waypoints (report P2-9) live in the edge's "mindustry" extension, in order. */
    @Test
    void rerouteWaypointsRoundTrip() {
        Board board = new Board();
        board.id = "b1";
        board.file = "main.canvas";

        CanvasNode first = new CanvasNode();
        first.id = "n1";
        first.kind = CanvasNode.KIND_CONTENT;
        first.contentId = "c1";
        board.nodes.add(first);
        CanvasNode second = new CanvasNode();
        second.id = "n2";
        second.kind = CanvasNode.KIND_CONTENT;
        second.contentId = "c2";
        board.nodes.add(second);

        CanvasEdge plain = new CanvasEdge();
        plain.id = "e1";
        plain.from = "n1";
        plain.to = "n2";
        plain.fieldKey = "requirements";
        board.edges.add(plain);

        CanvasEdge bent = new CanvasEdge();
        bent.id = "e2";
        bent.from = "n2";
        bent.to = "n1";
        bent.fieldKey = "research";
        bent.reroutes.add(new double[] {30, 40});
        bent.reroutes.add(new double[] {50.5, -12});
        board.edges.add(bent);

        var json = JsonCanvas.toCanvas(null, board);
        Board restored = JsonCanvas.fromCanvas(json.toString(), "main.canvas", "b1");

        assertTrue(restored.edges.get(0).reroutes.isEmpty(),
            "an edge without waypoints stays without waypoints");
        CanvasEdge restoredBent = restored.edges.get(1);
        assertEquals(2, restoredBent.reroutes.size());
        assertEquals(30, restoredBent.reroutes.get(0)[0], 0.001);
        assertEquals(40, restoredBent.reroutes.get(0)[1], 0.001);
        assertEquals(50.5, restoredBent.reroutes.get(1)[0], 0.001);
        assertEquals(-12, restoredBent.reroutes.get(1)[1], 0.001);
        assertFalse(json.getAsJsonArray("edges").get(0).getAsJsonObject()
            .getAsJsonObject("mindustry").has("reroutes"), "the empty list is never written");
    }
}

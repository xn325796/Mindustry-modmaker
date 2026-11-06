package dev.modmaker.core.layout;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canvas layout the research dialog draws: the numbers here are the game's constants
 * (nodeSize 60, gaps 20), and the shapes are the ones ResearchDialog.treeLayout produces - a lone
 * chain flows downward, and the root's children split into a half below and a half above it.
 */
class TechTreeCanvasLayoutTest {

    private static TechTreeCanvasLayout.Box node(String name, TechTreeCanvasLayout.Box... children) {
        TechTreeCanvasLayout.Box box = new TechTreeCanvasLayout.Box(name);
        box.children.addAll(List.of(children));
        return box;
    }

    @Test
    void aLoneChainFlowsDownwardOneLevelPerEightyPixels() {
        TechTreeCanvasLayout.Box leaf = node("leaf");
        TechTreeCanvasLayout.Box mid = node("mid", leaf);
        TechTreeCanvasLayout.Box root = node("root", mid);
        TechTreeCanvasLayout.layout(root);

        // Single child: the whole tree is the "top half", so depth grows along +Y. Levels are
        // nodeSize + gap = 80 apart, and a single-child chain keeps the parent's x (mid-point).
        assertEquals(0, mid.x - root.x, 0.001);
        assertEquals(TechTreeCanvasLayout.NODE_SIZE + TechTreeCanvasLayout.SPACING,
            mid.y - root.y, 0.001);
        assertEquals(0, leaf.x - mid.x, 0.001);
        assertEquals(TechTreeCanvasLayout.NODE_SIZE + TechTreeCanvasLayout.SPACING,
            leaf.y - mid.y, 0.001);
    }

    @Test
    void theRootsChildrenSplitIntoABelowAndAnAboveHalf() {
        // Four children: ceil(4/2)=2 go below (TreeLocation.top), the rest above (bottom) - the
        // game's exact half split (ResearchDialog.java:336-337), then the halves are re-aligned.
        TechTreeCanvasLayout.Box a = node("a");
        TechTreeCanvasLayout.Box b = node("b");
        TechTreeCanvasLayout.Box c = node("c");
        TechTreeCanvasLayout.Box d = node("d");
        TechTreeCanvasLayout.Box root = node("root", a, b, c, d);
        TechTreeCanvasLayout.layout(root);

        assertTrue(a.y > root.y && b.y > root.y, "the first half hangs below the root");
        assertTrue(c.y < root.y && d.y < root.y, "the second half hangs above the root");
        // Siblings of one half are exactly one nodeSize+gap apart along x.
        assertEquals(TechTreeCanvasLayout.NODE_SIZE + TechTreeCanvasLayout.SPACING,
            Math.abs(b.x - a.x), 0.001);
        assertEquals(TechTreeCanvasLayout.NODE_SIZE + TechTreeCanvasLayout.SPACING,
            Math.abs(d.x - c.x), 0.001);
        // The root sits centred between its halves: both passes put it at the same x, and the
        // shift has aligned the two passes' root y.
        assertEquals(0, root.x, 0.001);
        assertEquals(a.y, c.y + 2 * (TechTreeCanvasLayout.NODE_SIZE + TechTreeCanvasLayout.SPACING),
            0.001, "level 1 below mirrors level 1 above around the root");
    }

    @Test
    void layoutIsDeterministicAcrossRuns() {
        TechTreeCanvasLayout.Box root = node("root", node("a"), node("b"), node("c"));
        TechTreeCanvasLayout.layout(root);
        double[] first = new double[] {root.x, root.y, root.children.get(0).x};
        TechTreeCanvasLayout.Box again = node("root", node("a"), node("b"), node("c"));
        TechTreeCanvasLayout.layout(again);
        assertEquals(first[0], again.x, 0.001);
        assertEquals(first[1], again.y, 0.001);
        assertEquals(first[2], again.children.get(0).x, 0.001);
    }
}

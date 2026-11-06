package dev.modmaker.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM tests for the minimap projection maths and the LOD threshold rule (report P1-6/7).
 * No JavaFX toolkit is started here: everything lives in {@link MinimapMath}, which is why the
 * maths could be split out of the panes in the first place.
 */
class MinimapMathTest {

    private static final double EPS = 1e-9;

    @Test
    void fitIsAspectPreservingCentredAndInsideTheMargin() {
        MinimapMath.Projection p = MinimapMath.fit(-100, -50, 300, 150, 160, 110, 8);
        // 400x200 content into a 144x94 box: the width is the binding axis at scale 0.36.
        assertEquals(0.36, p.scale(), 1e-12);
        assertEquals(8, p.x(-100), EPS, "content left edge sits on the margin");
        assertEquals(152, p.x(300), EPS, "content right edge sits on the margin");
        assertEquals(19, p.y(-50), EPS, "the shorter axis is centred, not stretched");
        assertEquals(91, p.y(150), EPS);
        assertEquals(80, p.x(100), EPS, "the content centre lands on the map centre");
        assertEquals(55, p.y(50), EPS);
    }

    @Test
    void fitOfDegenerateContentFallsBackToUnitScale() {
        MinimapMath.Projection p = MinimapMath.fit(5, 5, 5, 5, 160, 110, 8);
        assertEquals(1, p.scale(), EPS, "an empty canvas must not divide by a zero content box");
        double[] rect = MinimapMath.viewportMapRect(0, 0, 1, 1400, 900, p, 160, 110);
        for (double value : rect) {
            assertFalse(Double.isNaN(value), "the viewport rect stays finite: " + value);
        }
    }

    @Test
    void viewportWorldRectInvertsTheViewTransform() {
        // screen = world * zoom + pan: pan(10,20) at zoom 2 shows world -5..45 x -10..15.
        double[] rect = MinimapMath.viewportWorldRect(10, 20, 2, 100, 50);
        assertEquals(-5, rect[0], EPS);
        assertEquals(-10, rect[1], EPS);
        assertEquals(45, rect[2], EPS);
        assertEquals(15, rect[3], EPS);
    }

    @Test
    void viewportWorldRectGuardsAgainstZeroZoom() {
        double[] rect = MinimapMath.viewportWorldRect(0, 0, 0, 100, 50);
        assertEquals(0, rect[0], EPS);
        assertEquals(100, rect[2], EPS, "a zero zoom reads as 1:1 instead of infinity");
    }

    @Test
    void viewportMapRectClampsIntoTheMapBox() {
        MinimapMath.Projection identity = new MinimapMath.Projection(1, 0, 0);
        double[] inside = MinimapMath.viewportMapRect(0, 0, 1, 50, 50, identity, 160, 110);
        assertEquals(0, inside[0], EPS);
        assertEquals(50, inside[2], EPS);
        assertEquals(50, inside[3], EPS);

        // A window wider and taller than the map: every edge clamps, never overshoots.
        double[] clamped = MinimapMath.viewportMapRect(-100, -100, 1, 400, 400, identity, 160, 110);
        assertEquals(100, clamped[0], EPS);
        assertEquals(100, clamped[1], EPS);
        assertEquals(60, clamped[2], EPS, "right edge clamps at the map width");
        assertEquals(10, clamped[3], EPS, "bottom edge clamps at the map height");
    }

    @Test
    void mapPointInvertsTheProjectionAndClampsToTheBox() {
        MinimapMath.Projection p = new MinimapMath.Projection(0.5, 10, 0);
        assertEquals(200, MinimapMath.worldXAtMap(110, 160, p), EPS);
        assertEquals(-20, MinimapMath.worldXAtMap(-5, 160, p), EPS, "left of the map clamps to x=0");
        assertEquals(300, MinimapMath.worldXAtMap(200, 160, p), EPS, "right of the map clamps to the width");
        MinimapMath.Projection q = new MinimapMath.Projection(0.5, 0, 10);
        assertEquals(90, MinimapMath.worldYAtMap(55, 110, q), EPS);
    }

    @Test
    void panToCenterRoundTripsThroughTheViewportRect() {
        double worldX = 321;
        double worldY = -77;
        double zoom = 0.7;
        double panX = MinimapMath.panToCenterX(worldX, zoom, 1400);
        double panY = MinimapMath.panToCenterY(worldY, zoom, 900);
        double[] rect = MinimapMath.viewportWorldRect(panX, panY, zoom, 1400, 900);
        assertEquals(worldX, (rect[0] + rect[2]) / 2, 1e-6,
            "the jumped-on world point must end up at the viewport centre");
        assertEquals(worldY, (rect[1] + rect[3]) / 2, 1e-6);
    }

    @Test
    void lodEngagesStrictlyBelowTheThreshold() {
        assertTrue(MinimapMath.lodActive(0.39, 0.4), "tech tree: under 0.4 the labels go");
        assertFalse(MinimapMath.lodActive(0.4, 0.4), "exactly at the threshold keeps full detail");
        assertFalse(MinimapMath.lodActive(0.41, 0.4));
        assertTrue(MinimapMath.lodActive(0.24, 0.25), "content canvas: under 0.25 the titles go");
        assertFalse(MinimapMath.lodActive(0.25, 0.25));
    }
}

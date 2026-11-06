package dev.modmaker.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM tests for the planet-lane maths (report P2-10): bounding boxes per planet, drop
 * hit-testing (including overlapping lanes) and the stable colour choice. No JavaFX toolkit.
 */
class PlanetLanesTest {

    private static final double EPS = 1e-9;

    @Test
    void buildGroupsOnePaddedBoxPerPlanetInFirstSeenOrder() {
        List<PlanetLanes.Lane> lanes = PlanetLanes.build(List.of(
            new PlanetLanes.Member("erpulo", 0, 0),
            new PlanetLanes.Member("serpulo", 400, 0),
            new PlanetLanes.Member("erpulo", 60, 0)), 30, 20);
        assertEquals(2, lanes.size(), "two planets make two lanes");
        assertEquals("erpulo", lanes.get(0).planet(), "lanes keep the first-seen order");
        assertEquals(-50, lanes.get(0).minX(), EPS, "the two erpulo nodes share one box, padded");
        assertEquals(110, lanes.get(0).maxX(), EPS);
        assertEquals(-50, lanes.get(0).minY(), EPS);
        assertEquals(50, lanes.get(0).maxY(), EPS);
        assertEquals("serpulo", lanes.get(1).planet());
    }

    @Test
    void buildSkipsMembersWithoutAPlanetAndEmptyInput() {
        assertTrue(PlanetLanes.build(List.of(new PlanetLanes.Member(null, 0, 0),
            new PlanetLanes.Member("  ", 10, 10)), 30, 20).isEmpty(),
            "planetless nodes join no lane");
        assertTrue(PlanetLanes.build(List.of(), 30, 20).isEmpty());
    }

    @Test
    void laneAtHitsTheContainingLaneAndReturnsNullForBlankSpace() {
        List<PlanetLanes.Lane> lanes = PlanetLanes.build(List.of(
            new PlanetLanes.Member("erpulo", 0, 0),
            new PlanetLanes.Member("serpulo", 400, 0)), 30, 20);
        assertNotNull(PlanetLanes.laneAt(lanes, 0, 0));
        assertEquals("erpulo", PlanetLanes.laneAt(lanes, 50, 50).planet(),
            "inclusive borders: a drop on the padded corner still counts as inside");
        assertNull(PlanetLanes.laneAt(lanes, 200, 0), "the gap between lanes is blank space");
    }

    @Test
    void laneAtPrefersTheSmallestContainingLane() {
        PlanetLanes.Lane big = new PlanetLanes.Lane("big", -1000, -1000, 1000, 1000);
        PlanetLanes.Lane small = new PlanetLanes.Lane("small", -10, -10, 10, 10);
        assertEquals("small", PlanetLanes.laneAt(List.of(big, small), 0, 0).planet(),
            "the tighter group wins where lanes overlap");
        assertEquals("small", PlanetLanes.laneAt(List.of(small, big), 0, 0).planet(),
            "the answer does not depend on the lane order");
    }

    @Test
    void colorIsStableCaseInsensitiveAndAPaletteHex() {
        assertEquals(PlanetLanes.colorFor("erpulo"), PlanetLanes.colorFor("erpulo"),
            "the same planet draws the same colour every render");
        assertEquals(PlanetLanes.colorFor("Erpulo"), PlanetLanes.colorFor("erpulo"),
            "the key is matched case-insensitively");
        assertTrue(PlanetLanes.colorFor("serpulo").matches("#[0-9A-F]{6}"),
            "the palette entry is a web hex colour");
        assertNotNull(PlanetLanes.colorFor(null), "a null key still resolves to a colour");
    }
}

package dev.modmaker.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure routing maths behind the canvas edge styles (report P2-9): style parsing and the
 * toolbar cycle, the three polyline shapes through reroute waypoints, the arrow angle and the
 * waypoint hit test. No JavaFX toolkit needed.
 */
class EdgePathMathTest {

    @Test
    void styleParsesSidecarKeysAndCyclesThroughTheToolbarOrder() {
        assertEquals(EdgePathMath.Style.BEZIER, EdgePathMath.Style.parse(null));
        assertEquals(EdgePathMath.Style.BEZIER, EdgePathMath.Style.parse("bezier"));
        assertEquals(EdgePathMath.Style.ORTHOGONAL, EdgePathMath.Style.parse("ORTHOGONAL"));
        assertEquals(EdgePathMath.Style.STRAIGHT, EdgePathMath.Style.parse(" straight "));
        assertEquals(EdgePathMath.Style.BEZIER, EdgePathMath.Style.parse("spaghetti"));
        assertEquals("orthogonal", EdgePathMath.Style.ORTHOGONAL.key());
        assertEquals(EdgePathMath.Style.ORTHOGONAL, EdgePathMath.Style.BEZIER.next());
        assertEquals(EdgePathMath.Style.STRAIGHT, EdgePathMath.Style.ORTHOGONAL.next());
        assertEquals(EdgePathMath.Style.BEZIER, EdgePathMath.Style.STRAIGHT.next());
    }

    @Test
    void viaPointsDropsMalformedWaypointsFromUserEditedFiles() {
        assertEquals(0, EdgePathMath.viaPoints(null).size());
        List<double[]> raw = new ArrayList<>();
        raw.add(null);
        raw.add(new double[] {5});
        raw.add(new double[] {1, 2});
        assertEquals(1, EdgePathMath.viaPoints(raw).size());
    }

    @Test
    void bezierSegmentUsesTheHistoricHorizontalTangentShape() {
        double[] segment = EdgePathMath.bezierSegment(0, 10, 100, 30);
        assertEquals(0, segment[0], 1e-9);
        assertEquals(10, segment[1], 1e-9);
        assertEquals(50, segment[2], 1e-9);
        assertEquals(10, segment[3], 1e-9);
        assertEquals(50, segment[4], 1e-9);
        assertEquals(30, segment[5], 1e-9);
        assertEquals(100, segment[6], 1e-9);
        assertEquals(30, segment[7], 1e-9);
    }

    @Test
    void straightPointsRunThroughEveryWaypointInOrder() {
        List<double[]> points = EdgePathMath.straightPoints(0, 0, 100, 50,
            List.of(new double[] {40, 10}, new double[] {70, 30}));
        assertEquals(4, points.size());
        assertEquals(0, points.get(0)[0], 1e-9);
        assertEquals(40, points.get(1)[0], 1e-9);
        assertEquals(70, points.get(2)[0], 1e-9);
        assertEquals(100, points.get(3)[0], 1e-9);
        assertEquals(50, points.get(3)[1], 1e-9);
    }

    @Test
    void straightPointsCollapseWaypointsThatSitOnNeighbours() {
        List<double[]> points = EdgePathMath.straightPoints(0, 0, 100, 0,
            List.of(new double[] {0, 0}, new double[] {50, 0}, new double[] {100, 0}));
        assertEquals(3, points.size(), "coincident neighbours must not add zero-length segments");
    }

    @Test
    void orthogonalPointsStayAxisAlignedThroughBends() {
        List<double[]> points = EdgePathMath.orthogonalPoints(0, 0, 100, 40, null);
        assertEquals(List.of(0d, 0d, 50d, 0d, 50d, 40d, 100d, 40d), flatten(points));
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            assertTrue(a[0] == b[0] || a[1] == b[1],
                "segment " + i + " must be horizontal or vertical");
        }
    }

    @Test
    void orthogonalPointsAddNoBendToAlreadyAxisAlignedLegs() {
        List<double[]> points = EdgePathMath.orthogonalPoints(0, 0, 100, 60,
            List.of(new double[] {100, 0}));
        assertEquals(List.of(0d, 0d, 100d, 0d, 100d, 60d), flatten(points));
    }

    @Test
    void arrowAngleFollowsTheFinalDirectionOfEachStyle() {
        // Bezier: the last control point shares the end's y, so the arrow enters horizontally.
        assertEquals(0, EdgePathMath.arrowAngle(EdgePathMath.Style.BEZIER, 0, 0, 100, 40, null), 1e-9);
        // A reroute past the end flips the final leg's direction.
        assertEquals(Math.PI,
            EdgePathMath.arrowAngle(EdgePathMath.Style.BEZIER, 0, 0, 100, 40,
                List.of(new double[] {200, 20})), 1e-9);
        // Straight: along the last segment.
        assertEquals(Math.PI / 2,
            EdgePathMath.arrowAngle(EdgePathMath.Style.STRAIGHT, 0, 0, 100, 40,
                List.of(new double[] {100, 0})), 1e-9);
        // Orthogonal: the final leg is horizontal into the port.
        assertEquals(0, EdgePathMath.arrowAngle(EdgePathMath.Style.ORTHOGONAL, 0, 0, 100, 40, null),
            1e-9);
    }

    @Test
    void hitReroutePicksTheNearestWaypointInsideTheRadius() {
        List<double[]> reroutes = List.of(new double[] {10, 10}, new double[] {30, 10});
        assertEquals(0, EdgePathMath.hitReroute(12, 10, reroutes, 7));
        assertEquals(1, EdgePathMath.hitReroute(31, 12, reroutes, 7));
        assertEquals(-1, EdgePathMath.hitReroute(20, 10, reroutes, 5));
        assertEquals(-1, EdgePathMath.hitReroute(0, 0, null, 7));
    }

    private static List<Double> flatten(List<double[]> points) {
        List<Double> out = new ArrayList<>();
        for (double[] point : points) {
            out.add(point[0]);
            out.add(point[1]);
        }
        return out;
    }
}

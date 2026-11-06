package dev.modmaker.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure routing maths behind the canvas edge styles (report P2-9): the bezier, orthogonal and
 * straight shapes a connection takes from its source port to its target port, optionally bending
 * through reroute waypoints. No JavaFX state, so the maths is unit-testable on a plain JVM.
 *
 * <p>All points are world coordinates. A waypoint list is untrusted input (it comes from a
 * user-editable .canvas file), so every entry point here tolerates nulls and short pairs.
 */
final class EdgePathMath {

    private EdgePathMath() {
    }

    /** The three routing styles the workspace toolbar cycles through. */
    enum Style {
        BEZIER, ORTHOGONAL, STRAIGHT;

        /** Lower-case key stored in the project sidecar. */
        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Parses a sidecar value; unknown or missing falls back to the historic bezier shape. */
        static Style parse(String value) {
            if (value == null) {
                return BEZIER;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return BEZIER;
            }
        }

        /** The next style in the toolbar cycle. */
        Style next() {
            return switch (this) {
                case BEZIER -> ORTHOGONAL;
                case ORTHOGONAL -> STRAIGHT;
                case STRAIGHT -> BEZIER;
            };
        }
    }

    /**
     * The usable waypoints, in order: nulls and malformed pairs dropped. Everything downstream
     * (path building, arrow angle, hit tests) reads the edge's reroutes through this filter.
     */
    static List<double[]> viaPoints(List<double[]> reroutes) {
        List<double[]> out = new ArrayList<>();
        if (reroutes != null) {
            for (double[] point : reroutes) {
                if (point != null && point.length >= 2) {
                    out.add(point);
                }
            }
        }
        return out;
    }

    /**
     * One bezier leg's control points: horizontal tangents at both ends meeting at the leg's
     * mid-x - the S-curve shape the canvas has always drawn. Returns
     * {@code {x1, y1, cx1, cy1, cx2, cy2, x2, y2}}.
     */
    static double[] bezierSegment(double x1, double y1, double x2, double y2) {
        double midX = x1 + (x2 - x1) * 0.5;
        return new double[] {x1, y1, midX, y1, midX, y2, x2, y2};
    }

    /** The straight polyline: start, every waypoint in order, end. */
    static List<double[]> straightPoints(double x1, double y1, double x2, double y2,
        List<double[]> reroutes) {
        List<double[]> points = new ArrayList<>();
        addPoint(points, x1, y1);
        for (double[] point : viaPoints(reroutes)) {
            addPoint(points, point[0], point[1]);
        }
        addPoint(points, x2, y2);
        return points;
    }

    /**
     * The orthogonal polyline: every leg bends through its mid-x, so the path is axis-aligned
     * (horizontal, vertical, horizontal) and enters/exits the same way the bezier does. Adjacent
     * coincident points are collapsed, so a waypoint already on a straight leg adds no zero-length
     * segment.
     */
    static List<double[]> orthogonalPoints(double x1, double y1, double x2, double y2,
        List<double[]> reroutes) {
        List<double[]> points = new ArrayList<>();
        addPoint(points, x1, y1);
        double prevX = x1;
        double prevY = y1;
        for (double[] point : viaPoints(reroutes)) {
            appendBend(points, prevX, prevY, point[0], point[1]);
            addPoint(points, point[0], point[1]);
            prevX = point[0];
            prevY = point[1];
        }
        appendBend(points, prevX, prevY, x2, y2);
        addPoint(points, x2, y2);
        return points;
    }

    /** The two mid-x bend points of one leg; nothing when the leg is already axis-aligned. */
    private static void appendBend(List<double[]> points, double x1, double y1, double x2, double y2) {
        if (x1 == x2 || y1 == y2) {
            return;
        }
        double midX = x1 + (x2 - x1) * 0.5;
        addPoint(points, midX, y1);
        addPoint(points, midX, y2);
    }

    private static void addPoint(List<double[]> points, double x, double y) {
        if (!points.isEmpty()) {
            double[] last = points.get(points.size() - 1);
            if (last[0] == x && last[1] == y) {
                return;
            }
        }
        points.add(new double[] {x, y});
    }

    /**
     * The angle (radians) the path travels as it reaches the target port, for the arrow head.
     * The bezier's last control point sits at the end's y on the final leg's mid-x, which makes
     * the arrow horizontal into the port; the polylines point along their final segment.
     */
    static double arrowAngle(Style style, double x1, double y1, double x2, double y2,
        List<double[]> reroutes) {
        List<double[]> via = viaPoints(reroutes);
        if (style == Style.BEZIER) {
            double prevX = x1;
            for (double[] point : via) {
                prevX = point[0];
            }
            double midX = prevX + (x2 - prevX) * 0.5;
            // The last control point shares the end's y: the tangent into the port is horizontal.
            return Math.atan2(0.0, x2 - midX);
        }
        List<double[]> points = style == Style.ORTHOGONAL
            ? orthogonalPoints(x1, y1, x2, y2, reroutes)
            : straightPoints(x1, y1, x2, y2, reroutes);
        if (points.size() >= 2) {
            double[] before = points.get(points.size() - 2);
            return Math.atan2(y2 - before[1], x2 - before[0]);
        }
        return Math.atan2(y2 - y1, x2 - x1);
    }

    /** The index of the waypoint under a world point, or -1 when nothing is within the radius. */
    static int hitReroute(double worldX, double worldY, List<double[]> reroutes, double radius) {
        int best = -1;
        double bestDistance = radius;
        List<double[]> via = viaPoints(reroutes);
        for (int i = 0; i < via.size(); i++) {
            double[] point = via.get(i);
            double distance = Math.hypot(point[0] - worldX, point[1] - worldY);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}

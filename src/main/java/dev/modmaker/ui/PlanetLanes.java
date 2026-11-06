package dev.modmaker.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure maths behind the tech-tree planet lanes (report P2-10): group the record nodes by their
 * {@code research.planet} value into one coloured bounding box per planet, hit-test a drop point
 * against those boxes, and pick a stable colour for a planet key.
 *
 * <p>No JavaFX state, so the maths is unit-testable on a plain JVM (same split as
 * {@link MinimapMath}). The colours are inline rather than CSS classes because the number of
 * planets is unbounded - the same call the batch-B card head makes with {@code ContentType.color()}.
 */
final class PlanetLanes {

    /** One node's contribution to a lane: its planet key and its world-space centre. */
    record Member(String planet, double x, double y) { }

    /** A planet's lane box in world coordinates, already padded. */
    record Lane(String planet, double minX, double minY, double maxX, double maxY) {

        double width() {
            return maxX - minX;
        }

        double height() {
            return maxY - minY;
        }

        /** Inclusive on every edge: a drop exactly on the border still counts as inside. */
        boolean contains(double x, double y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }

        private double area() {
            return Math.max(0, width()) * Math.max(0, height());
        }
    }

    /**
     * A fixed dark-theme-friendly palette; the lane colour is palette[index of the planet key's
     * stable hash], so the same planet always draws the same colour, in any order, on any JVM
     * (String.hashCode is specified, unlike Object.hashCode).
     */
    private static final String[] PALETTE = {
        "#5B8DEF", "#E06C75", "#98C379", "#D19A66", "#C678DD",
        "#56B6C2", "#E5C07B", "#7FAEE5", "#B58FDB", "#6FBF8F"};

    private PlanetLanes() {
    }

    /**
     * One padded box per distinct planet among {@code members}, in first-seen order. Members
     * without a planet key (null/blank) join no lane; an empty input yields an empty list. Each
     * member's node square (centre +/- {@code half}) is grown by {@code pad} on every side.
     */
    static List<Lane> build(List<Member> members, double half, double pad) {
        Map<String, double[]> boxes = new LinkedHashMap<>();
        for (Member member : members) {
            String planet = member.planet();
            if (planet == null || planet.isBlank()) {
                continue;
            }
            double minX = member.x() - half;
            double minY = member.y() - half;
            double maxX = member.x() + half;
            double maxY = member.y() + half;
            double[] box = boxes.get(planet);
            if (box == null) {
                boxes.put(planet, new double[] {minX, minY, maxX, maxY});
            } else {
                box[0] = Math.min(box[0], minX);
                box[1] = Math.min(box[1], minY);
                box[2] = Math.max(box[2], maxX);
                box[3] = Math.max(box[3], maxY);
            }
        }
        List<Lane> lanes = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : boxes.entrySet()) {
            double[] box = entry.getValue();
            lanes.add(new Lane(entry.getKey(), box[0] - pad, box[1] - pad, box[2] + pad,
                box[3] + pad));
        }
        return lanes;
    }

    /**
     * The lane a drop point falls into, or null for blank space. When overlapping lanes both
     * contain the point the smallest one wins: the tighter (more specific) group is what the user
     * meant to drop into. Ties resolve to the earlier lane, so the answer is deterministic.
     */
    static Lane laneAt(List<Lane> lanes, double x, double y) {
        Lane best = null;
        for (Lane lane : lanes) {
            if (!lane.contains(x, y)) {
                continue;
            }
            if (best == null || lane.area() < best.area()) {
                best = lane;
            }
        }
        return best;
    }

    /** The lane colour for a planet key: stable across renders, case-insensitive, never null. */
    static String colorFor(String planet) {
        String key = planet == null ? "" : planet.toLowerCase(Locale.ROOT);
        return PALETTE[Math.floorMod(key.hashCode(), PALETTE.length)];
    }
}

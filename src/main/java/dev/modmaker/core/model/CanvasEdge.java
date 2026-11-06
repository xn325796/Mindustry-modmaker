package dev.modmaker.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A reference between two content nodes.
 *
 * <p>{@link #from} is the referenced content - the card whose ID port the arrow leaves - and
 * {@link #to} is the record whose reference field {@link #fieldKey} holds that reference, i.e. the
 * card whose input port the arrow enters. {@code dev.modmaker.core.io.LinkGraph} derives edges in
 * this orientation, and the canvas, {@code ProjectController.connect} and {@code disconnect} all
 * read it the same way, so the arrow is never drawn backwards.
 */
public final class CanvasEdge {
    public String id;
    public String from;
    public String to;
    public String fieldKey;
    public String label = "";
    /**
     * Reroute waypoints (report P2-9): world-space points the wire bends through, in order.
     * Not part of JSON Canvas proper; stored in the edge's "mindustry" extension.
     */
    public List<double[]> reroutes = new ArrayList<>();

    /** A detached copy for read-only work off the model's thread. */
    public CanvasEdge snapshot() {
        CanvasEdge copy = new CanvasEdge();
        copy.id = id;
        copy.from = from;
        copy.to = to;
        copy.fieldKey = fieldKey;
        copy.label = label;
        copy.reroutes = new ArrayList<>(reroutes.size());
        for (double[] point : reroutes) {
            copy.reroutes.add(point == null ? null : point.clone());
        }
        return copy;
    }
}

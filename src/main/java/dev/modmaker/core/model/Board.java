package dev.modmaker.core.model;

import java.util.ArrayList;
import java.util.List;

/** A board is one JSON Canvas file: nodes plus the reference edges between them. */
public final class Board {
    public String id;
    public String name = "Main";
    /** File name inside the project's boards/ folder. */
    public String file = "main.canvas";
    public List<CanvasNode> nodes = new ArrayList<>();
    public List<CanvasEdge> edges = new ArrayList<>();
    public Viewport viewport = new Viewport();
    /**
     * Edge routing style (report P2-9): "bezier", "orthogonal" or "straight". Not part of JSON
     * Canvas; kept in the project sidecar next to the viewport, like the viewport a pure
     * authoring preference that never marks the project dirty.
     */
    public String edgeStyle = "bezier";

    /** Not part of JSON Canvas; kept in the project sidecar so a board reopens where it was left. */
    public static final class Viewport {
        public double x;
        public double y;
        public double zoom = 1;
    }

    /** A detached copy for read-only work off the model's thread. */
    public Board snapshot() {
        Board copy = new Board();
        copy.id = id;
        copy.name = name;
        copy.file = file;
        copy.nodes = new ArrayList<>(nodes.size());
        for (CanvasNode node : nodes) {
            copy.nodes.add(node.snapshot());
        }
        copy.edges = new ArrayList<>(edges.size());
        for (CanvasEdge edge : edges) {
            copy.edges.add(edge.snapshot());
        }
        copy.viewport = new Viewport();
        copy.viewport.x = viewport.x;
        copy.viewport.y = viewport.y;
        copy.viewport.zoom = viewport.zoom;
        copy.edgeStyle = edgeStyle;
        return copy;
    }
}

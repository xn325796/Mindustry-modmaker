package dev.modmaker.core.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * The research dialog's tree layout: a plain BranchTreeLayout run plus the split the game applies
 * at the root, so the editor's canvas draws exactly the shape the game draws
 * (ResearchDialog.treeLayout, ResearchDialog.java:332-372).
 *
 * <p>The root's children are split into two halves: the first {@code ceil(n/2)} laid out with
 * {@code TreeLocation.top} (levels grow downward), the rest with {@code TreeLocation.bottom}
 * (levels grow upward), then the top half is shifted so both halves meet at the root's final Y.
 * Siblings always spread along X; nodeSize and both gaps are the game's values.
 */
public final class TechTreeCanvasLayout {

    /** ResearchDialog.nodeSize (Scl.scl(60f) at scale 1) and the spacing both passes use. */
    public static final float NODE_SIZE = 60f;
    public static final float SPACING = 20f;

    /** One canvas node: payload plus mutable position, laid out in place. */
    public static final class Box implements BranchTreeLayout.Node {
        public final Object payload;
        public final List<Box> children = new ArrayList<>();
        public float x;
        public float y;
        public final float width;
        public final float height;

        public Box(Object payload) {
            this(payload, NODE_SIZE, NODE_SIZE);
        }

        public Box(Object payload, float width, float height) {
            this.payload = payload;
            this.width = width;
            this.height = height;
        }

        @Override
        public List<Box> children() {
            return children;
        }

        @Override
        public float width() {
            return width;
        }

        @Override
        public float height() {
            return height;
        }

        @Override
        public void setX(float x) {
            this.x = x;
        }

        @Override
        public void setY(float y) {
            this.y = y;
        }
    }

    private TechTreeCanvasLayout() {
    }

    /**
     * Positions {@code root} and its whole subtree. The root itself sits between its two layout
     * halves exactly as in the game: the left (top-located) half is shifted by the difference of
     * the two passes' root Y (ResearchDialog.java:355).
     */
    public static void layout(Box root) {
        int total = root.children.size();
        int leftCount = (int) Math.ceil(total / 2f);

        List<Box> children = new ArrayList<>(root.children);
        List<Box> leftHalf = children.subList(0, leftCount);
        List<Box> rightHalf = children.subList(leftCount, total);

        root.children.clear();
        root.children.addAll(leftHalf);
        new BranchTreeLayout(BranchTreeLayout.TreeLocation.top, SPACING, SPACING).layout(root);
        float lastY = root.y;

        if (!rightHalf.isEmpty()) {
            root.children.clear();
            root.children.addAll(rightHalf);
            new BranchTreeLayout(BranchTreeLayout.TreeLocation.bottom, SPACING, SPACING).layout(root);
            // Re-align: the top half moves by the difference of the two roots' Y so the root sits
            // between its upward- and downward-growing halves.
            shift(leftHalf, root.y - lastY);
        }

        root.children.clear();
        root.children.addAll(children);
    }

    /** ResearchDialog.shift: moves a subtree (and all of its descendants) by a Y offset. */
    private static void shift(List<Box> children, float amount) {
        for (Box node : children) {
            node.y += amount;
            if (!node.children.isEmpty()) {
                shift(node.children, amount);
            }
        }
    }
}

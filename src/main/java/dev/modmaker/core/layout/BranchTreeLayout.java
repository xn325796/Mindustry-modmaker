package dev.modmaker.core.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-by-line port of {@code mindustry.ui.layout.BranchTreeLayout} (v160.5), which is itself the
 * abego TreeLayout walker the game's research dialog runs. Kept in the pure-Java core so the layout
 * the editor draws is provably the layout the game draws, down to the same spacing constants.
 *
 * <p>Only the parts the research dialog exercises are kept: it always uses uniform square nodes
 * ({@code width == height == nodeSize}), {@code TreeAlignment.awayFromRoot} and the {@code
 * TreeLocation.top}/{@code bottom} pair (ResearchDialog.java:340-353), so {@code calcWidth()}
 * degenerates to {@code width()} and no bounds tracking is needed (the editor canvas clamps by its
 * own viewport, not by a measured rect). Everything else - firstWalk, apportion, executeShifts,
 * secondWalk and the level axis rules - mirrors the game file statement for statement.
 *
 * <p>Positions: for {@code top}/{@code bottom} the level axis is Y (depth) and siblings spread
 * along X; {@code left}/{@code right} swap the axes, exactly as the game's {@code
 * isLevelChangeInYAxis()} decides (BranchTreeLayout.java:47-50, 295-325).
 */
public final class BranchTreeLayout {

    public enum TreeLocation { top, left, bottom, right }

    /** A node the layout positions. {@code children()} may be empty but not null. */
    public interface Node {
        List<? extends Node> children();

        float width();

        float height();

        void setX(float x);

        void setY(float y);
    }

    public TreeLocation rootLocation = TreeLocation.top;
    /** The research dialog sets both gaps to 20 (ResearchDialog.java:333, 341, 351). */
    public float gapBetweenLevels = 10;
    public float gapBetweenNodes = 10;

    private final List<Float> sizeOfLevel = new ArrayList<>();

    public BranchTreeLayout(TreeLocation rootLocation, float gapBetweenLevels, float gapBetweenNodes) {
        this.rootLocation = rootLocation;
        this.gapBetweenLevels = gapBetweenLevels;
        this.gapBetweenNodes = gapBetweenNodes;
    }

    /** Lays out the tree rooted at {@code root}, writing x/y back onto every node. */
    public void layout(Node root) {
        Walker wrapper = new Walker(root, null);
        sizeOfLevel.clear();
        firstWalk(wrapper, null);
        calcSizeOfLevels(wrapper, 0);
        secondWalk(wrapper, -wrapper.prelim, 0, 0);
    }

    /** Largest node thickness per level (BranchTreeLayout.calcSizeOfLevels). */
    private void calcSizeOfLevels(Walker node, int level) {
        float oldSize;
        if (sizeOfLevel.size() <= level) {
            sizeOfLevel.add(0f);
            oldSize = 0;
        } else {
            oldSize = sizeOfLevel.get(level);
        }

        float size = getNodeThickness(node);
        if (oldSize < size) {
            sizeOfLevel.set(level, size);
        }

        if (!node.isLeaf()) {
            for (Walker child : node.children) {
                calcSizeOfLevels(child, level + 1);
            }
        }
    }

    private float getSizeOfLevel(int level) {
        return sizeOfLevel.get(level);
    }

    // --- the walker ------------------------------------------------------------------------------

    /** Internal node carrying the algorithm state; mirrors arc's TreeNode<T> shape. */
    private static final class Walker {
        final Node node;
        Walker parent;
        Walker[] children;
        float width;
        float height;
        float x;
        float y;
        float prelim;
        float mode;
        float shift;
        float change;
        Walker thread;
        Walker ancestor;
        int number = -1;

        Walker(Node node, Walker parent) {
            this.node = node;
            this.parent = parent;
            this.width = node.width();
            this.height = node.height();
            List<? extends Node> kids = node.children();
            this.children = new Walker[kids.size()];
            for (int i = 0; i < kids.size(); i++) {
                children[i] = new Walker(kids.get(i), this);
            }
            this.ancestor = this;
        }

        boolean isLeaf() {
            return children.length == 0;
        }

        /** Arc's TreeNode.calcWidth: the plain width (uniform squares in our case). */
        float calcWidth() {
            return width;
        }
    }

    private float getWidthOrHeightOfNode(Walker node, boolean returnWidth) {
        return returnWidth ? node.calcWidth() : node.height;
    }

    private float getNodeThickness(Walker node) {
        return getWidthOrHeightOfNode(node, !isLevelChangeInYAxis());
    }

    private float getNodeSize(Walker node) {
        return getWidthOrHeightOfNode(node, isLevelChangeInYAxis());
    }

    private boolean isLevelChangeInYAxis() {
        return rootLocation == TreeLocation.top || rootLocation == TreeLocation.bottom;
    }

    private int getLevelChangeSign() {
        return rootLocation == TreeLocation.bottom || rootLocation == TreeLocation.right ? -1 : 1;
    }

    private float getGapBetweenNodes(Walker a, Walker b) {
        return gapBetweenNodes;
    }

    private float getDistance(Walker v, Walker w) {
        float sizeOfNodes = getNodeSize(v) + getNodeSize(w);
        return sizeOfNodes / 2 + getGapBetweenNodes(v, w);
    }

    private Walker nextLeft(Walker v) {
        return v.isLeaf() ? v.thread : v.children[0];
    }

    private Walker nextRight(Walker v) {
        return v.isLeaf() ? v.thread : v.children[v.children.length - 1];
    }

    private int getNumber(Walker node, Walker parentNode) {
        if (node.number == -1) {
            int number = 1;
            for (Walker child : parentNode.children) {
                child.number = number++;
            }
        }
        return node.number;
    }

    private Walker ancestor(Walker vIMinus, Walker parentOfV, Walker defaultAncestor) {
        Walker ancestor = vIMinus.ancestor;
        return ancestor.parent == parentOfV ? ancestor : defaultAncestor;
    }

    private void moveSubtree(Walker wMinus, Walker wPlus, Walker parent, float shift) {
        int subtrees = getNumber(wPlus, parent) - getNumber(wMinus, parent);
        wPlus.change = wPlus.change - shift / subtrees;
        wPlus.shift = wPlus.shift + shift;
        wMinus.change = wMinus.change + shift / subtrees;
        wPlus.prelim = wPlus.prelim + shift;
        wPlus.mode = wPlus.mode + shift;
    }

    private Walker apportion(Walker v, Walker defaultAncestor, Walker leftSibling, Walker parentOfV) {
        if (leftSibling == null) {
            return defaultAncestor;
        }

        Walker vOPlus = v;
        Walker vIPlus = v;
        Walker vIMinus = leftSibling;

        Walker vOMinus = parentOfV.children[0];

        float sIPlus = vIPlus.mode;
        float sOPlus = vOPlus.mode;
        float sIMinus = vIMinus.mode;
        float sOMinus = vOMinus.mode;

        Walker nextRightVIMinus = nextRight(vIMinus);
        Walker nextLeftVIPlus = nextLeft(vIPlus);

        while (nextRightVIMinus != null && nextLeftVIPlus != null) {
            vIMinus = nextRightVIMinus;
            vIPlus = nextLeftVIPlus;
            vOMinus = nextLeft(vOMinus);
            vOPlus = nextRight(vOPlus);
            vOPlus.ancestor = v;
            float shift = (vIMinus.prelim + sIMinus)
                - (vIPlus.prelim + sIPlus)
                + getDistance(vIMinus, vIPlus);

            if (shift > 0) {
                moveSubtree(ancestor(vIMinus, parentOfV, defaultAncestor), v, parentOfV, shift);
                sIPlus = sIPlus + shift;
                sOPlus = sOPlus + shift;
            }
            sIMinus += vIMinus.mode;
            sIPlus += vIPlus.mode;
            sOMinus += vOMinus.mode;
            sOPlus += vOPlus.mode;

            nextRightVIMinus = nextRight(vIMinus);
            nextLeftVIPlus = nextLeft(vIPlus);
        }

        if (nextRightVIMinus != null && nextRight(vOPlus) == null) {
            vOPlus.thread = nextRightVIMinus;
            vOPlus.mode += sIMinus - sOPlus;
        }

        if (nextLeftVIPlus != null && nextLeft(vOMinus) == null) {
            vOMinus.thread = nextLeftVIPlus;
            vOMinus.mode += sIPlus - sOMinus;
            defaultAncestor = v;
        }
        return defaultAncestor;
    }

    private void executeShifts(Walker v) {
        float shift = 0;
        float change = 0;

        for (int i = v.children.length - 1; i >= 0; i--) {
            Walker w = v.children[i];
            change = change + w.change;
            w.prelim += shift;
            w.mode += shift;
            shift += w.shift + change;
        }
    }

    private void firstWalk(Walker v, Walker leftSibling) {
        if (v.isLeaf()) {
            if (leftSibling != null) {
                v.prelim = leftSibling.prelim + getDistance(v, leftSibling);
            }
        } else {
            Walker defaultAncestor = v.children[0];
            Walker previousChild = null;
            for (Walker w : v.children) {
                firstWalk(w, previousChild);
                defaultAncestor = apportion(w, defaultAncestor, previousChild, v);
                previousChild = w;
            }
            executeShifts(v);
            float midpoint = (v.children[0].prelim + v.children[v.children.length - 1].prelim) / 2f;
            if (leftSibling != null) {
                v.prelim = leftSibling.prelim + getDistance(v, leftSibling);
                v.mode = v.prelim - midpoint;
            } else {
                v.prelim = midpoint;
            }
        }
    }

    private void secondWalk(Walker v, float m, int level, float levelStart) {
        int levelChangeSign = getLevelChangeSign();
        boolean levelChangeOnYAxis = isLevelChangeInYAxis();
        float levelSize = getSizeOfLevel(level);

        float x = v.prelim + m;

        float y = levelStart + levelSize - levelChangeSign * (getNodeThickness(v) / 2);

        if (!levelChangeOnYAxis) {
            float t = x;
            x = y;
            y = t;
        }

        v.node.setX(x);
        v.node.setY(y);

        if (!v.isLeaf()) {
            float nextLevelStart = levelStart + (levelSize + gapBetweenLevels) * levelChangeSign;
            for (Walker w : v.children) {
                secondWalk(w, m + v.mode, level + 1, nextLevelStart);
            }
        }
    }
}

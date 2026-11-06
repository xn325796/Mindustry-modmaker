package dev.modmaker.ui;

import com.google.gson.JsonElement;
import dev.modmaker.core.fmt.TechTreeRules;
import dev.modmaker.core.io.TechTreeIndex;
import dev.modmaker.core.model.ContentRecord;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The unlock tri-state (report P1-8) derived from the tree structure alone. The editor holds a
 * static tree definition, not a campaign, so "unlocked" follows the game's own rule: a
 * {@code root:true} node is open the moment a run starts, everything hanging off an intact parent
 * chain is researchable, and whatever the chain never reaches (no parent, an unresolvable parent,
 * a parent cycle) stays locked.
 *
 * <p>Pure logic over the forest {@link TechTreeIndex} has already parsed: the render layer only
 * consumes the resulting map and never walks the tree itself.
 */
final class TechUnlockState {

    /** The three research states, named exactly as the canvas' style classes. */
    enum Unlock {
        /** A {@code root:true} node: open from the start of the game. */
        unlocked,
        /** Its parent chain reaches a root (or a vanilla anchor) intact. */
        researchable,
        /** The chain never reaches a root; the whole broken subtree is locked. */
        locked
    }

    private TechUnlockState() {
    }

    /** The records the game would open its tree with: non-patch, non-inline, {@code root:true}. */
    static Set<String> rootRecordIds(List<ContentRecord> records) {
        Set<String> ids = new LinkedHashSet<>();
        for (ContentRecord record : records) {
            if (record.isInline() || !TechTreeRules.allowsResearch(record.patch)) {
                continue;
            }
            JsonElement research = record.fields.get(TechTreeRules.FIELD);
            if (research != null && !research.isJsonNull() && TechTreeRules.isRoot(research)) {
                ids.add(record.id);
            }
        }
        return ids;
    }

    /**
     * One state per record-backed entry of the forest. A structural root only counts as unlocked
     * when it is a true {@code root:true} node: the index also surfaces broken chains (problem
     * roots, cycle survivors) as roots, and those lock everything below them. A vanilla anchor
     * group is an open parent the game ships, so its children are researchable; the group itself
     * has no record and stays out of the map.
     */
    static Map<String, Unlock> derive(TechTreeIndex.Forest forest, Set<String> trueRootIds) {
        Map<String, Unlock> states = new LinkedHashMap<>();
        for (TechTreeIndex.Entry root : forest.roots()) {
            walk(root, rootState(root, trueRootIds), states);
        }
        return Map.copyOf(states);
    }

    private static void walk(TechTreeIndex.Entry entry, Unlock state, Map<String, Unlock> states) {
        if (entry.contentId() != null) {
            states.put(entry.contentId(), state);
        }
        Unlock childState = state == Unlock.locked ? Unlock.locked : Unlock.researchable;
        for (TechTreeIndex.Entry child : entry.children()) {
            walk(child, childState, states);
        }
    }

    private static Unlock rootState(TechTreeIndex.Entry root, Set<String> trueRootIds) {
        if (root.vanilla()) {
            return Unlock.unlocked;
        }
        if (root.problem() != TechTreeIndex.Problem.none) {
            return Unlock.locked;
        }
        return trueRootIds.contains(root.contentId()) ? Unlock.unlocked : Unlock.locked;
    }
}

package dev.modmaker.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.TechTreeRules;
import dev.modmaker.core.io.TechTreeIndex;
import dev.modmaker.core.model.ContentRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unlock tri-state (report P1-8) is pure tree logic over the forest {@link TechTreeIndex}
 * parses - no JavaFX toolkit needed, so it lives apart from the FX smoke suite.
 */
class TechUnlockStateTest {

    @Test
    void rootIsUnlockedAndItsWholeChainResearchable() {
        TechTreeIndex.Entry grand = entry("g");
        TechTreeIndex.Entry child = entry("c", grand);
        TechTreeIndex.Entry root = entry("r", child);
        Map<String, TechUnlockState.Unlock> states = TechUnlockState.derive(forest(root), Set.of("r"));
        assertEquals(TechUnlockState.Unlock.unlocked, states.get("r"), "root:true opens the run");
        assertEquals(TechUnlockState.Unlock.researchable, states.get("c"));
        assertEquals(TechUnlockState.Unlock.researchable, states.get("g"),
            "depth does not matter: an intact chain reaches the root");
    }

    @Test
    void brokenParentLocksTheWholeSubtree() {
        TechTreeIndex.Entry under = entry("u");
        TechTreeIndex.Entry unresolved = entry("x", TechTreeIndex.Problem.unresolvedParent, under);
        TechTreeIndex.Entry ignored = entry("i", TechTreeIndex.Problem.ignored);
        Map<String, TechUnlockState.Unlock> states =
            TechUnlockState.derive(forest(unresolved, ignored), Set.of());
        assertEquals(TechUnlockState.Unlock.locked, states.get("x"));
        assertEquals(TechUnlockState.Unlock.locked, states.get("u"),
            "a node hanging off a broken parent never reaches the root either");
        assertEquals(TechUnlockState.Unlock.locked, states.get("i"));
    }

    @Test
    void cycleSurvivorRootIsLocked() {
        // The index surfaces parent cycles as plain problem-free roots; they are not root:true,
        // so the game never opens them and neither does the editor.
        TechTreeIndex.Entry cycleNode = entry("c");
        Map<String, TechUnlockState.Unlock> states =
            TechUnlockState.derive(forest(cycleNode), Set.of());
        assertEquals(TechUnlockState.Unlock.locked, states.get("c"));
    }

    @Test
    void multiRootForestKeepsEveryTreeIndependent() {
        TechTreeIndex.Entry first = entry("a", entry("b"));
        TechTreeIndex.Entry second = entry("c", entry("d"));
        Map<String, TechUnlockState.Unlock> states =
            TechUnlockState.derive(forest(first, second), Set.of("a", "c"));
        assertEquals(TechUnlockState.Unlock.unlocked, states.get("a"));
        assertEquals(TechUnlockState.Unlock.researchable, states.get("b"));
        assertEquals(TechUnlockState.Unlock.unlocked, states.get("c"));
        assertEquals(TechUnlockState.Unlock.researchable, states.get("d"));
    }

    @Test
    void vanillaAnchorMakesItsChildrenResearchable() {
        TechTreeIndex.Entry modChild = entry("m");
        TechTreeIndex.Entry vanilla = new TechTreeIndex.Entry(null, "copper", true,
            TechTreeIndex.Problem.none, List.of(modChild));
        Map<String, TechUnlockState.Unlock> states =
            TechUnlockState.derive(forest(vanilla), Set.of());
        assertEquals(TechUnlockState.Unlock.researchable, states.get("m"),
            "a game-shipped parent is an open link, exactly like a root");
        assertEquals(1, states.size(), "the vanilla group itself has no record to state");
    }

    @Test
    void rootRecordIdsPicksOnlyTrueRoots() {
        ContentRecord root = record("r1", true, false);
        ContentRecord child = record("c", false, false);
        ContentRecord patch = record("p", true, true);
        ContentRecord inline = record("i", true, false);
        inline.inlineOwnerId = "host";
        Set<String> ids = TechUnlockState.rootRecordIds(List.of(root, child, patch, inline));
        assertEquals(Set.of("r1"), ids, "root:true only: no bare parents, no patches, no inlines");
    }

    private static TechTreeIndex.Forest forest(TechTreeIndex.Entry... roots) {
        return new TechTreeIndex.Forest(List.of(roots), List.of(), List.of(), List.of(), List.of());
    }

    private static TechTreeIndex.Entry entry(String id, TechTreeIndex.Entry... children) {
        return entry(id, TechTreeIndex.Problem.none, children);
    }

    private static TechTreeIndex.Entry entry(String id, TechTreeIndex.Problem problem,
        TechTreeIndex.Entry... children) {
        return new TechTreeIndex.Entry(id, id, false, problem, List.of(children));
    }

    private static ContentRecord record(String id, boolean root, boolean patch) {
        ContentRecord record = new ContentRecord();
        record.id = id;
        record.type = ContentType.block;
        record.name = id;
        record.patch = patch;
        if (root) {
            JsonObject research = new JsonObject();
            research.addProperty("root", true);
            record.fields.put(TechTreeRules.FIELD, research);
        } else {
            record.fields.put(TechTreeRules.FIELD, new JsonPrimitive("other"));
        }
        return record;
    }
}

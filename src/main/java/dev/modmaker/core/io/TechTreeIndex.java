package dev.modmaker.core.io;

import com.google.gson.JsonElement;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.TechTreeRules;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The tech tree as the records describe it: a derived view over every record's {@code research}
 * field, never a second copy of it.
 *
 * <p>Faithful to {@code ContentParser.java:1337-1418}:
 * <ul>
 *   <li>a node exists for any record that carries a {@code research} value, whatever its shape, so
 *       it can be someone else's parent ({@code TechNode} is registered in its constructor,
 *       TechTree.java:88);</li>
 *   <li>a patch may not carry research at all (ContentParser.java:1338-1341) - those records are
 *       reported separately and never join the tree;</li>
 *   <li>a node that is neither {@code root} nor names a parent is left unattached, which the game
 *       warns about and ignores (ContentParser.java:1414-1416);</li>
 *   <li>a parent that is neither a mod node nor a vanilla name leaves the node detached, which the
 *       game warns about too (ContentParser.java:1403-1405);</li>
 *   <li>vanilla trees are built before any mod loads (ContentLoader.java:72-79), so a bare name that
 *       exists in both binds to the vanilla node - only the {@code <mod>-name} spelling reaches the
 *       mod's own node (ContentParser.java:1401).</li>
 * </ul>
 */
public final class TechTreeIndex {

    /** Why a record is not a plain member of the tree, mirroring the game's warnings. */
    public enum Problem {
        none,
        /** Neither root nor a parent the game can find; the game warns and never attaches it. */
        ignored,
        /** The name in {@code parent} matches no mod node and no known vanilla content. */
        unresolvedParent,
        /** A patch: the game refuses to let it modify the tech tree. */
        patchRefused
    }

    /** One tree node. {@code contentId} is null for vanilla nodes the mod only points at. */
    public record Entry(String contentId, String label, boolean vanilla, Problem problem,
        List<Entry> children) {
    }

    /**
     * The whole picture: the forest to display plus the records that are deliberately not in it.
     * {@code unresolvedParents} and {@code ignored} entries also appear in {@code roots} - their
     * children still hang off them in the game, since their nodes do exist in {@code TechTree.all}.
     */
    public record Forest(List<Entry> roots, List<Entry> unassigned, List<Entry> unresolvedParents,
        List<Entry> ignored, List<Entry> patchesWithResearch) {
    }

    private TechTreeIndex() {
    }

    public static Forest build(ModProject project, SchemaRegistry registry) {
        String internal = project.internalName();
        List<ContentRecord> records = new ArrayList<>();
        for (ContentRecord record : project.contents) {
            if (!record.isInline()) {
                records.add(record);
            }
        }

        // Every record that carries research is a node someone may name as a parent, whatever the
        // value looks like - the game registers the node before validating it.
        Map<String, ContentRecord> nodesByName = new LinkedHashMap<>();
        for (ContentRecord record : records) {
            if (isTreeNode(record)) {
                nodesByName.putIfAbsent(record.name, record);
                nodesByName.putIfAbsent(record.fullName(internal), record);
            }
        }

        List<ContentRecord> rootRecords = new ArrayList<>();
        List<ContentRecord> ignoredRecords = new ArrayList<>();
        List<ContentRecord> unresolvedRecords = new ArrayList<>();
        List<Entry> unassigned = new ArrayList<>();
        List<Entry> refused = new ArrayList<>();
        Map<String, List<ContentRecord>> modChildren = new LinkedHashMap<>();
        Map<String, List<ContentRecord>> vanillaChildren = new LinkedHashMap<>();

        for (ContentRecord record : records) {
            JsonElement research = researchOf(record);
            if (research == null) {
                unassigned.add(new Entry(record.id, record.name, false, Problem.none, List.of()));
                continue;
            }
            if (!TechTreeRules.allowsResearch(record.patch)) {
                refused.add(new Entry(record.id, record.name, false, Problem.patchRefused, List.of()));
                continue;
            }
            if (TechTreeRules.isRoot(research)) {
                rootRecords.add(record);
                continue;
            }
            String parent = TechTreeRules.parent(research);
            if (parent == null) {
                ignoredRecords.add(record);
                continue;
            }
            ContentRecord parentRecord = null;
            if (parent.startsWith(internal + "-")) {
                parentRecord = nodesByName.get(parent);
            } else if (isVanilla(registry, parent)) {
                vanillaChildren.computeIfAbsent(parent.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                    .add(record);
                continue;
            } else {
                parentRecord = nodesByName.get(parent);
            }
            if (parentRecord != null) {
                modChildren.computeIfAbsent(parentRecord.name, key -> new ArrayList<>()).add(record);
            } else {
                unresolvedRecords.add(record);
            }
        }

        Set<String> placed = new LinkedHashSet<>();
        List<Entry> roots = new ArrayList<>();
        for (ContentRecord record : rootRecords) {
            roots.add(buildEntry(record, Problem.none, modChildren, placed));
        }
        for (ContentRecord record : unresolvedRecords) {
            roots.add(buildEntry(record, Problem.unresolvedParent, modChildren, placed));
        }
        for (ContentRecord record : ignoredRecords) {
            roots.add(buildEntry(record, Problem.ignored, modChildren, placed));
        }
        for (Map.Entry<String, List<ContentRecord>> group : vanillaChildren.entrySet()) {
            List<Entry> children = new ArrayList<>();
            for (ContentRecord child : group.getValue()) {
                children.add(buildEntry(child, Problem.none, modChildren, placed));
            }
            roots.add(new Entry(null, group.getKey(), true, Problem.none, List.copyOf(children)));
        }
        // A parent chain that loops would leave nodes unreachable from any root; surface them rather
        // than silently dropping them from the view. Must run last, or a node already claimed by a
        // vanilla group would also appear as a spurious root.
        for (ContentRecord record : records) {
            if (isTreeNode(record) && !placed.contains(record.id)) {
                roots.add(buildEntry(record, Problem.none, modChildren, placed));
            }
        }

        unassigned.sort(Comparator.comparing(entry -> entry.label()));
        return new Forest(List.copyOf(roots), List.copyOf(unassigned),
            problemEntries(roots, Problem.unresolvedParent), problemEntries(roots, Problem.ignored),
            List.copyOf(refused));
    }

    /** The field's value, or null when the record carries no research at all. */
    private static JsonElement researchOf(ContentRecord record) {
        JsonElement research = record.fields.get(TechTreeRules.FIELD);
        return research == null || research.isJsonNull() ? null : research;
    }

    private static boolean isTreeNode(ContentRecord record) {
        return researchOf(record) != null && TechTreeRules.allowsResearch(record.patch);
    }

    private static boolean isVanilla(SchemaRegistry registry, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (ContentType type : ContentType.values()) {
            if (registry.vanillaNames(type).contains(lower)) {
                return true;
            }
        }
        return false;
    }

    private static Entry buildEntry(ContentRecord record, Problem problem,
        Map<String, List<ContentRecord>> modChildren, Set<String> placed) {
        placed.add(record.id);
        List<Entry> children = new ArrayList<>();
        for (ContentRecord child : modChildren.getOrDefault(record.name, List.of())) {
            if (!placed.contains(child.id)) {
                children.add(buildEntry(child, Problem.none, modChildren, placed));
            }
        }
        return new Entry(record.id, record.name, false, problem, List.copyOf(children));
    }

    /** The entries carrying a given problem, in display order and without duplicates. */
    private static List<Entry> problemEntries(List<Entry> roots, Problem problem) {
        List<Entry> found = new ArrayList<>();
        for (Entry root : roots) {
            if (root.problem() == problem) {
                found.add(root);
            }
        }
        return List.copyOf(found);
    }
}

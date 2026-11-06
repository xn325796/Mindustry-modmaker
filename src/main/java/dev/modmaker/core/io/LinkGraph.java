package dev.modmaker.core.io;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.Ids;
import dev.modmaker.core.fmt.TechTreeRules;
import dev.modmaker.core.json.JsonPath;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.CanvasNode;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;
import dev.modmaker.core.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reference links a project's records imply, plus how far a board has drifted from them.
 *
 * <p>Edge orientation is fixed here once and for all: {@code from} is the <em>referenced</em>
 * content - the card whose ID port the arrow leaves - and {@code to} is the record whose reference
 * field {@code fieldKey} holds the reference, i.e. the card whose input port the arrow enters. The
 * canvas ({@code NodeCanvasPane}), {@code ProjectController.connect} and this class all use that
 * one convention, so an imported link, a hand-drawn link and a rebuilt link mean the same thing.
 * (Before this class existed the importer wrote links the other way round, which drew every imported
 * arrow backwards and made deleting one leave the field behind.)
 *
 * <p>Only links between two cards that both resolve to records are derived. A link that touches a
 * card with no record - a carrier bound to a vanilla name, or a detached card - is the author's,
 * not the importer's, and is left alone by {@link #rebuild}.
 */
public final class LinkGraph {

    private LinkGraph() {
    }

    /** How far a board has drifted from the records it is supposed to mirror. */
    public record Diff(int orphanNodes, int missingEdges, int staleEdges) {
        public boolean clean() {
            return orphanNodes == 0 && missingEdges == 0 && staleEdges == 0;
        }

        public String summary() {
            return orphanNodes + " orphan card(s), " + missingEdges + " missing link(s), "
                + staleEdges + " stale link(s)";
        }
    }

    /** A board's rebuilt link list plus what changed, for the caller's log and undo stack. */
    public record Rebuild(List<CanvasEdge> edges, int added, int removed) {
    }

    /** The links the records imply, restricted to nodes present on the board. Deterministic. */
    public static List<CanvasEdge> derive(ModProject project, Board board, SchemaRegistry registry) {
        Map<String, String> nodeByContent = nodeByContent(board);
        Map<String, List<ContentRecord>> recordsByName = new LinkedHashMap<>();
        for (ContentRecord record : project.contents) {
            if (!record.isInline()) {
                recordsByName.computeIfAbsent(record.name, ignored -> new ArrayList<>()).add(record);
            }
        }

        List<CanvasEdge> edges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String internal = project.internalName();

        for (ContentRecord record : project.contents) {
            String recordNode = nodeByContent.get(record.id);
            if (recordNode == null) {
                continue;
            }
            if (record.isInline()) {
                // Containment: the nested record is the referenced content, its owner holds the
                // field that contains it.
                String ownerNode = nodeByContent.get(record.inlineOwnerId);
                if (ownerNode != null) {
                    add(edges, seen, recordNode, ownerNode, firstSegment(record.inlinePath),
                        JsonPath.leafName(record.inlinePath));
                }
                continue;
            }

            String className = registry.resolveClass(record.type, record.className());
            if (className != null) {
                for (FieldDef field : registry.fieldsOf(className)) {
                    if (!field.isReference()) {
                        continue;
                    }
                    JsonElement value = record.fields.get(field.name());
                    if (value == null) {
                        continue;
                    }
                    for (String name : referenceNames(value, field)) {
                        link(edges, seen, recordsByName, nodeByContent, record, name, field, internal,
                            field.name());
                    }
                }
            }

            // research is the only way mod content reaches the tech tree; the schema types it
            // loosely, so it is handled explicitly.
            String parent = TechTreeRules.parent(record.fields.get(TechTreeRules.FIELD));
            if (parent != null) {
                link(edges, seen, recordsByName, nodeByContent, record, parent, null, internal,
                    TechTreeRules.FIELD);
            }
        }
        return edges;
    }

    /** Compares a board against the records: orphan cards, links that are missing, links that lie. */
    public static Diff diff(ModProject project, Board board, SchemaRegistry registry) {
        int orphanNodes = 0;
        for (CanvasNode node : board.nodes) {
            if (node.contentId != null && project.contentById(node.contentId) == null) {
                orphanNodes++;
            }
        }

        Set<String> derived = new LinkedHashSet<>();
        for (CanvasEdge edge : derive(project, board, registry)) {
            derived.add(key(edge));
        }
        Set<String> existing = new LinkedHashSet<>();
        int staleEdges = 0;
        for (CanvasEdge edge : board.edges) {
            String key = key(edge);
            existing.add(key);
            // A link into a card with no record (vanilla carrier, detached card) is not the
            // importer's business, so its absence from the derived set is not a lie.
            if (!derived.contains(key) && resolvesBothEnds(project, board, edge)) {
                staleEdges++;
            }
        }
        int missingEdges = 0;
        for (String key : derived) {
            if (!existing.contains(key)) {
                missingEdges++;
            }
        }
        return new Diff(orphanNodes, missingEdges, staleEdges);
    }

    /**
     * Recomputes a board's links from the records. Links between two record-backed cards become
     * exactly what the records imply (existing edge objects are reused so their ids stay stable);
     * links that touch a card with no record are carried over untouched, so a hand-drawn reference
     * to vanilla content is never dropped.
     */
    public static Rebuild rebuild(ModProject project, Board board, SchemaRegistry registry) {
        List<CanvasEdge> derived = derive(project, board, registry);

        Map<String, CanvasEdge> existingByKey = new LinkedHashMap<>();
        List<CanvasEdge> kept = new ArrayList<>();
        for (CanvasEdge edge : board.edges) {
            existingByKey.put(key(edge), edge);
            if (!resolvesBothEnds(project, board, edge)) {
                kept.add(edge);
            }
        }

        Set<String> before = new LinkedHashSet<>(existingByKey.keySet());
        List<CanvasEdge> edges = new ArrayList<>();
        Set<String> after = new LinkedHashSet<>();
        for (CanvasEdge edge : derived) {
            after.add(key(edge));
            CanvasEdge prior = existingByKey.get(key(edge));
            edges.add(prior != null ? prior : copy(edge));
        }
        for (CanvasEdge edge : kept) {
            if (after.add(key(edge))) {
                edges.add(edge);
            }
        }

        int added = 0;
        for (String key : after) {
            if (!before.contains(key)) {
                added++;
            }
        }
        int removed = 0;
        for (String key : before) {
            if (!after.contains(key)) {
                removed++;
            }
        }
        return new Rebuild(edges, added, removed);
    }

    // --- helpers --------------------------------------------------------------------------------

    private static void link(List<CanvasEdge> edges, Set<String> seen,
        Map<String, List<ContentRecord>> recordsByName, Map<String, String> nodeByContent,
        ContentRecord record, String targetName, FieldDef field, String internal, String fieldKey) {
        ContentRecord target = resolveTarget(recordsByName, targetName, field, internal);
        if (target == null) {
            return;
        }
        String fromNode = nodeByContent.get(target.id);
        String toNode = nodeByContent.get(record.id);
        if (fromNode == null || toNode == null || fromNode.equals(toNode)) {
            return;
        }
        add(edges, seen, fromNode, toNode, fieldKey, targetName);
    }

    private static void add(List<CanvasEdge> edges, Set<String> seen, String from, String to,
        String fieldKey, String label) {
        CanvasEdge edge = new CanvasEdge();
        edge.from = from;
        edge.to = to;
        edge.fieldKey = fieldKey;
        edge.label = label;
        if (!seen.add(key(edge))) {
            return;
        }
        edge.id = Ids.uid("e");
        edges.add(edge);
    }

    private static CanvasEdge copy(CanvasEdge edge) {
        CanvasEdge clone = new CanvasEdge();
        clone.id = Ids.uid("e");
        clone.from = edge.from;
        clone.to = edge.to;
        clone.fieldKey = edge.fieldKey;
        clone.label = edge.label;
        return clone;
    }

    private static String key(CanvasEdge edge) {
        return edge.from + "->" + edge.to + "#" + edge.fieldKey;
    }

    private static Map<String, String> nodeByContent(Board board) {
        Map<String, String> byContent = new LinkedHashMap<>();
        for (CanvasNode node : board.nodes) {
            if (node.contentId != null) {
                byContent.putIfAbsent(node.contentId, node.id);
            }
        }
        return byContent;
    }

    private static ContentRecord recordOfNode(ModProject project, Board board, String nodeId) {
        for (CanvasNode node : board.nodes) {
            if (node.id != null && node.id.equals(nodeId)) {
                return node.contentId == null ? null : project.contentById(node.contentId);
            }
        }
        return null;
    }

    private static boolean resolvesBothEnds(ModProject project, Board board, CanvasEdge edge) {
        return recordOfNode(project, board, edge.from) != null
            && recordOfNode(project, board, edge.to) != null;
    }

    /** Names a reference field points at, tolerating the shapes real mods use. */
    /**
     * The content names one field value references, tolerating every shape the game reads - the
     * canvas's per-block link view uses this to find a record's neighbours.
     */
    public static List<String> referencedNames(JsonElement value, FieldDef field) {
        return referenceNames(value, field);
    }

    private static List<String> referenceNames(JsonElement value, FieldDef field) {
        List<String> names = new ArrayList<>();
        collectNames(value, field, names);
        return names;
    }

    private static void collectNames(JsonElement value, FieldDef field, List<String> out) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        // A turret's ammo map: the KEYS are the item/liquid references (the values are the inline
        // bullets, which the containment links above already cover).
        if (field != null && field.type() == FieldType.struct
            && "ammoMap".equals(field.stackKind()) && value.isJsonObject()) {
            for (String key : value.getAsJsonObject().keySet()) {
                out.add(key);
            }
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                collectNames(element, field, out);
            }
            return;
        }
        if (value.isJsonPrimitive()) {
            if (!value.getAsJsonPrimitive().isString()) {
                return;
            }
            String text = value.getAsString().trim();
            if (text.isEmpty() || text.startsWith("icon-")) {
                return;
            }
            if (field != null && (field.type().isStack() || field.type() == FieldType.struct)) {
                int slash = text.indexOf('/');
                if (slash > 0) {
                    text = text.substring(0, slash);
                }
            }
            out.add(text);
            return;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : new String[] {"item", "liquid", "payload", "block", "unit", "content"}) {
                collectNamedString(object.get(key), field, out);
            }
            // Structured groups nest their references one level down (a UnitReq's requirements
            // array, a consume group's items). Recurse into containers only: an arbitrary string
            // under an unknown key - a bullet's "type", say - must stay untouched.
            for (var entry : object.entrySet()) {
                if (!CONTENT_REFERENCE_KEYS.contains(entry.getKey())
                    && (entry.getValue().isJsonArray() || entry.getValue().isJsonObject())) {
                    collectNames(entry.getValue(), field, out);
                }
            }
        }
    }

    /** A named reference value ("liquid": "water/0.25") - the stack spelling is stripped. */
    private static void collectNamedString(JsonElement named, FieldDef field, List<String> out) {
        if (named == null || !named.isJsonPrimitive() || !named.getAsJsonPrimitive().isString()) {
            return;
        }
        String text = named.getAsString().trim();
        if (text.isEmpty() || text.startsWith("icon-")) {
            return;
        }
        if (field != null && (field.type().isStack() || field.type() == FieldType.struct)) {
            int slash = text.indexOf('/');
            if (slash > 0) {
                text = text.substring(0, slash);
            }
        }
        out.add(text);
    }

    private static final List<String> CONTENT_REFERENCE_KEYS =
        List.of("item", "liquid", "payload", "block", "unit", "content");

    private static ContentRecord resolveTarget(Map<String, List<ContentRecord>> recordsByName,
        String name, FieldDef field, String internal) {
        String bare = name;
        String prefix = internal + "-";
        if (!internal.isEmpty() && bare.startsWith(prefix)) {
            bare = bare.substring(prefix.length());
        }
        ContentRecord untyped = null;
        for (ContentRecord record : recordsByName.getOrDefault(bare, List.of())) {
            if (field == null || field.contentCtype() == null || field.contentCtype().equals("any")
                || record.type.name().equals(field.contentCtype())) {
                return record;
            }
            // Structured groups mix reference kinds (a UnitReq names a factory block AND item
            // costs); remember the first name match of any type as the fallback.
            if (field.type() == FieldType.struct && untyped == null) {
                untyped = record;
            }
        }
        return untyped;
    }

    /**
     * The field an inline path sits under: {@code ammoTypes[0]} and {@code ammoTypes.copper} both
     * name {@code ammoTypes}, so the containment link points at a port the owner really has.
     */
    private static String firstSegment(String path) {
        List<JsonPath.Step> steps = JsonPath.steps(path);
        if (steps.isEmpty()) {
            return path;
        }
        JsonPath.Step first = steps.get(0);
        return first.isIndex() ? path : first.key();
    }
}

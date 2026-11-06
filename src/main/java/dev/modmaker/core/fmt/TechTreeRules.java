package dev.modmaker.core.fmt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading and writing of the {@code research} field, which is the only way mod content enters the
 * tech tree (there are no tech tree files; ContentParser.java:1337-1418 builds nodes from content).
 *
 * <p>A plain string means "parent content name". An object may carry parent, requirements,
 * objectives, planet and root. Everything here mirrors {@code ContentParser.readFields}
 * (ContentParser.java:1348-1396) key for key: keys the game does not read are never written, and a
 * node that only names a parent stays a bare string.
 */
public final class TechTreeRules {

    public static final String FIELD = "research";

    /** The objective types the game ships, i.e. the classes in {@code Objectives} (Objectives.java). */
    public static final List<String> OBJECTIVE_TYPES =
        List.of("Research", "Produce", "SectorComplete", "OnSector", "OnPlanet");

    private TechTreeRules() {
    }

    /**
     * One research node as the game would read it. {@code requirements} entries are kept in the
     * {@code "item/amount"} text form the game accepts for {@code ItemStack} (ContentParser.java:466)
     * and {@code objectives} are kept as raw objects so unknown keys survive an edit untouched.
     */
    public record Node(String parent, boolean root, String name, boolean requiresUnlock,
        String planet, List<String> requirements, List<JsonObject> objectives) {

        public static Node empty() {
            return new Node(null, false, null, false, null, List.of(), List.of());
        }

        /** True when nothing at all is set, i.e. the field carries no research node. */
        public boolean isEmpty() {
            return parent == null && !root && name == null && !requiresUnlock
                && planet == null && requirements.isEmpty() && objectives.isEmpty();
        }

        /** A copy with another parent set (and root cleared, since the game ignores parent then). */
        public Node withParent(String newParent) {
            return new Node(blankToNull(newParent), false, name, requiresUnlock, planet,
                requirements, objectives);
        }

        public Node withRoot(boolean newRoot) {
            return new Node(newRoot ? null : parent, newRoot, name, requiresUnlock, planet,
                requirements, objectives);
        }

        public Node withPlanet(String newPlanet) {
            return new Node(parent, root, name, requiresUnlock, blankToNull(newPlanet),
                requirements, objectives);
        }

        public Node withRequirements(List<String> newRequirements) {
            return new Node(parent, root, name, requiresUnlock, planet,
                List.copyOf(newRequirements), objectives);
        }

        public Node withObjectives(List<JsonObject> newObjectives) {
            return new Node(parent, root, name, requiresUnlock, planet, requirements,
                List.copyOf(newObjectives));
        }
    }

    /** Reads whichever of the seven keys {@code ContentParser.java:1348-1396} reads. */
    public static Node read(JsonElement research) {
        if (research == null || research.isJsonNull()) {
            return Node.empty();
        }
        if (research.isJsonPrimitive()) {
            return new Node(blankToNull(research.getAsString()), false, null, false, null,
                List.of(), List.of());
        }
        if (!research.isJsonObject()) {
            return Node.empty();
        }
        JsonObject object = research.getAsJsonObject();
        return new Node(string(object, "parent"), bool(object, "root"), string(object, "name"),
            bool(object, "requiresUnlock"), string(object, "planet"), requirements(object),
            objectives(object));
    }

    /**
     * Serialises a node the way the game reads it: a bare parent name stays a string, anything else
     * becomes an object. Returns null when there is nothing left to write, so the caller can remove
     * the field entirely.
     */
    public static JsonElement write(Node node) {
        if (node == null || node.isEmpty()) {
            return null;
        }
        boolean bare = !node.root() && node.parent() != null && node.planet() == null
            && node.name() == null && !node.requiresUnlock() && node.requirements().isEmpty()
            && node.objectives().isEmpty();
        if (bare) {
            return new JsonPrimitive(node.parent());
        }
        JsonObject object = new JsonObject();
        if (node.root()) {
            object.addProperty("root", true);
            if (node.name() != null) {
                object.addProperty("name", node.name());
            }
            if (node.requiresUnlock()) {
                object.addProperty("requiresUnlock", true);
            }
        } else if (node.parent() != null) {
            object.addProperty("parent", node.parent());
        }
        if (node.planet() != null) {
            object.addProperty("planet", node.planet());
        }
        if (!node.requirements().isEmpty()) {
            JsonArray requirements = new JsonArray();
            node.requirements().forEach(requirements::add);
            object.add("requirements", requirements);
        }
        if (!node.objectives().isEmpty()) {
            JsonArray objectives = new JsonArray();
            node.objectives().forEach(objective -> objectives.add(objective.deepCopy()));
            object.add("objectives", objectives);
        }
        return object;
    }

    /**
     * {@code ContentParser.java:1338} — a patch (content that overrides vanilla) may not touch the
     * tech tree; the game warns "Modifying the tech tree is not allowed (@)" and ignores the field.
     */
    public static boolean allowsResearch(boolean patch) {
        return !patch;
    }

    /**
     * The JSON key that names an objective's target, straight from the classes in
     * {@code Objectives}: {@code Research}/{@code Produce} hold {@code content},
     * {@code SectorComplete}/{@code OnSector} hold {@code preset}, {@code OnPlanet} holds
     * {@code planet}.
     */
    public static String objectiveTargetKey(String type) {
        return switch (type == null ? "" : type) {
            case "SectorComplete", "OnSector" -> "preset";
            case "OnPlanet" -> "planet";
            default -> "content";
        };
    }

    /** Builds the smallest objective object the game can read: {@code {type: <type>, <key>: <target>}}. */
    public static JsonObject objective(String type, String target) {
        String kind = OBJECTIVE_TYPES.contains(type) ? type : OBJECTIVE_TYPES.get(0);
        JsonObject objective = new JsonObject();
        objective.addProperty("type", kind);
        if (target != null && !target.isBlank()) {
            objective.addProperty(objectiveTargetKey(kind), target.trim());
        }
        return objective;
    }

    /** The parent content name, or null when this is a root node or the value is unusable. */
    public static String parent(JsonElement research) {
        if (research == null || research.isJsonNull()) {
            return null;
        }
        if (research.isJsonPrimitive()) {
            String parent = research.getAsString();
            return parent.isBlank() ? null : parent;
        }
        if (research.isJsonObject()) {
            JsonObject object = research.getAsJsonObject();
            if (isRoot(object)) {
                return null;
            }
            JsonElement parent = object.get("parent");
            if (parent != null && parent.isJsonPrimitive()) {
                String name = parent.getAsString();
                return name.isBlank() ? null : name;
            }
        }
        return null;
    }

    /** ContentParser.java:1394 — research nodes with root:true become their own tech tree. */
    public static boolean isRoot(JsonElement research) {
        return research != null && research.isJsonObject() && isRoot(research.getAsJsonObject());
    }

    private static boolean isRoot(JsonObject research) {
        JsonElement root = research.get("root");
        return root != null && root.isJsonPrimitive() && root.getAsBoolean();
    }

    /** Whether the value is shaped like something the game will accept as a research node. */
    public static boolean isWellFormed(JsonElement research) {
        if (!isRoot(research) && parent(research) == null) {
            // A non-root node without a parent is dropped by the game with a warning.
            return false;
        }
        return true;
    }

    // --- helpers --------------------------------------------------------------------------------

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? blankToNull(value.getAsString()) : null;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
            && value.getAsBoolean();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Reads {@code requirements} as the {@code "item/amount"} strings the game parses. */
    private static List<String> requirements(JsonObject object) {
        JsonElement value = object.get("requirements");
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            String text = requirementText(element);
            if (text != null) {
                out.add(text);
            }
        }
        return out;
    }

    /** One requirement, accepting both accepted shapes: "copper/10" and {item: copper, amount: 10}. */
    private static String requirementText(JsonElement element) {
        if (element == null) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            String text = element.getAsString();
            return text.isBlank() ? null : text;
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject stack = element.getAsJsonObject();
        JsonElement item = stack.get("item");
        if (item == null || !item.isJsonPrimitive()) {
            return null;
        }
        JsonElement amount = stack.get("amount");
        String count = amount != null && amount.isJsonPrimitive() ? amount.getAsString() : "1";
        return item.getAsString() + "/" + count;
    }

    private static List<JsonObject> objectives(JsonObject object) {
        JsonElement value = object.get("objectives");
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element != null && element.isJsonObject()) {
                out.add(element.getAsJsonObject().deepCopy());
            }
        }
        return out;
    }
}

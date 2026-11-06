package dev.modmaker.core.fmt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;

/**
 * Writing and undoing cross-content references inside a record's field tree - the model side of
 * dragging a connection between two cards.
 *
 * <p>Reference values are written as the bare source content name: the game resolves bare names
 * against the current mod first (ContentParser's find/locate helpers), and vanilla targets must not
 * be prefixed anyway. Stack fields become {"item":name,"amount":1} objects, the shape vanilla JSON
 * uses.
 */
public final class ReferenceOps {

    /** Everything needed to undo one {@link #apply}. */
    public record Revert(boolean appended, int appendedIndex, JsonElement previous) {
    }

    private ReferenceOps() {
    }

    public static Revert apply(ContentRecord target, FieldDef field, String sourceName) {
        return apply(target, field, sourceName, null);
    }

    /**
     * Writes one reference into the target's field. {@code sourceTypeName} is the referenced
     * content's type name ("item", "liquid", ...) when known - struct shapes use it to pick the
     * right slot inside a group (a consume group's items vs its liquids); null falls back to the
     * item slot.
     */
    public static Revert apply(ContentRecord target, FieldDef field, String sourceName,
        String sourceTypeName) {
        if (field.type() == FieldType.struct) {
            JsonElement previous = target.fields.get(field.name());
            if (!applyStruct(target, field, sourceName, sourceTypeName)) {
                return null;
            }
            return new Revert(false, -1, previous);
        }
        if (field.type() == FieldType.stackArray) {
            JsonElement previous = target.fields.get(field.name());
            JsonArray array = previous != null && previous.isJsonArray()
                ? previous.getAsJsonArray()
                : new JsonArray();
            int index = array.size();
            array.add(stackEntry(field.stackKind(), sourceName));
            target.fields.put(field.name(), array);
            return new Revert(true, index, previous);
        }
        JsonElement previous = target.fields.get(field.name());
        if (field.type() == FieldType.stack) {
            target.fields.put(field.name(), stackEntry(field.stackKind(), sourceName));
        } else {
            target.fields.put(field.name(), new JsonPrimitive(sourceName));
        }
        return new Revert(false, -1, previous);
    }

    /**
     * Writes one reference into a structured group field. Undo restores the whole previous tree.
     * Returns false (writing nothing) when the shape and the source type cannot combine - wiring
     * an item into a unit's requirements before any factory entry exists, for instance.
     */
    private static boolean applyStruct(ContentRecord target, FieldDef field, String sourceName,
        String sourceTypeName) {
        switch (shape(field)) {
            case "ammoMap" -> {
                // The map key is the reference; an empty object is a valid bullet - the game
                // defaults a missing "type" to BasicBulletType (ContentParser.java:185).
                JsonObject map = objectOrNew(target.fields.get(field.name()));
                if (!map.has(sourceName)) {
                    map.add(sourceName, new JsonObject());
                }
                target.fields.put(field.name(), map);
                return true;
            }
            case "unitReq", "plan" -> {
                // {block/unit, requirements, time} - the game defaults requirements={} and
                // time=600 for UnitReq (ContentParser.UnitReq), so a wired factory creates an
                // entry with an empty cost list that item connections then fill. A wiring that
                // does not match the entry key (an item where a factory belongs, or the other
                // way round) has no slot to land in and is refused.
                String key = shape(field).equals("unitReq") ? "block" : "unit";
                boolean keySource = key.equals(sourceTypeName);
                JsonArray array = arrayOrNew(target.fields.get(field.name()));
                if (keySource) {
                    if (hasNamedEntry(array, key, sourceName)) {
                        return true;
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty(key, sourceName);
                    entry.add("requirements", new JsonArray());
                    array.add(entry);
                    target.fields.put(field.name(), array);
                    return true;
                }
                for (JsonElement element : array) {
                    if (element.isJsonObject() && element.getAsJsonObject().has("requirements")) {
                        JsonArray costs = element.getAsJsonObject().getAsJsonArray("requirements");
                        costs.add(stackEntry("item", sourceName));
                        return true;
                    }
                }
                return false;
            }
            case "consume" -> {
                // One consume group per item/liquid mix: wiring an item appends to the first
                // group's "items", a liquid to its "liquids", creating the group when needed.
                boolean liquid = "liquid".equals(sourceTypeName);
                String slot = liquid ? "liquids" : "items";
                JsonElement current = target.fields.get(field.name());
                if (current != null && current.isJsonObject()) {
                    // Single-object group form: mutate it in place, keep the object shape.
                    fillGroupSlot(current.getAsJsonObject(), slot, liquid, sourceName);
                    target.fields.put(field.name(), current);
                } else {
                    JsonArray array = arrayOrNew(current);
                    JsonObject group = firstGroupWithSlot(array, slot);
                    fillGroupSlot(group, slot, liquid, sourceName);
                    target.fields.put(field.name(), array);
                }
                return true;
            }
            default -> {
                // Unknown shape: fall back to the stack-array behaviour, which is at least a
                // valid structured list.
                JsonArray array = arrayOrNew(target.fields.get(field.name()));
                array.add(stackEntry(null, sourceName));
                target.fields.put(field.name(), array);
                return true;
            }
        }
    }

    private static String shape(FieldDef field) {
        String kind = field.stackKind();
        return kind == null || kind.isEmpty() ? "" : kind;
    }

    private static JsonObject objectOrNew(JsonElement current) {
        return current != null && current.isJsonObject() ? current.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray arrayOrNew(JsonElement current) {
        if (current != null && current.isJsonArray()) {
            return current.getAsJsonArray();
        }
        // A hand-written single object becomes the first array element (the game reads both).
        JsonArray array = new JsonArray();
        if (current != null && current.isJsonObject()) {
            array.add(current);
        }
        return array;
    }

    private static boolean hasNamedEntry(JsonArray array, String key, String sourceName) {
        for (JsonElement element : array) {
            if (element.isJsonObject() && element.getAsJsonObject().has(key)
                && element.getAsJsonObject().get(key).isJsonPrimitive()
                && element.getAsJsonObject().get(key).getAsString().equals(sourceName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The group (object) of a consume array that should receive the given slot - items or liquids:
     * a group already carrying that slot wins, otherwise the first group gains it, and an empty
     * array gets a fresh group.
     */
    private static JsonObject firstGroupWithSlot(JsonArray array, String slot) {
        JsonObject first = null;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject group = element.getAsJsonObject();
            if (group.has(slot)) {
                return group;
            }
            if (first == null) {
                first = group;
            }
        }
        if (first != null) {
            return first;
        }
        JsonObject group = new JsonObject();
        array.add(group);
        return group;
    }

    private static void fillGroupSlot(JsonObject group, String slot, boolean liquid,
        String sourceName) {
        JsonArray stacks = group.has(slot) && group.get(slot).isJsonArray()
            ? group.getAsJsonArray(slot)
            : new JsonArray();
        stacks.add(stackEntry(liquid ? "liquid" : "item", sourceName));
        group.add(slot, stacks);
    }

    public static void revert(ContentRecord target, FieldDef field, Revert revert) {
        if (revert.appended()) {
            JsonElement current = target.fields.get(field.name());
            if (current != null && current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                if (revert.appendedIndex() < array.size()) {
                    array.remove(revert.appendedIndex());
                }
                if (array.isEmpty()) {
                    target.fields.remove(field.name());
                }
            }
            return;
        }
        if (revert.previous() == null) {
            target.fields.remove(field.name());
        } else {
            target.fields.put(field.name(), revert.previous());
        }
    }

    /** Best-effort removal when no revert info exists (an edge loaded from disk, for instance). */
    public static boolean remove(ContentRecord target, FieldDef field, String sourceName) {
        JsonElement current = target.fields.get(field.name());
        if (current == null) {
            return false;
        }
        if (field.type() == FieldType.struct) {
            return removeStruct(target, field, current, sourceName);
        }
        if (current.isJsonArray()) {
            JsonArray array = current.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (matches(array.get(i), field.stackKind(), sourceName)) {
                    array.remove(i);
                    if (array.isEmpty()) {
                        target.fields.remove(field.name());
                    }
                    return true;
                }
            }
            return false;
        }
        if (matches(current, field.stackKind(), sourceName)) {
            target.fields.remove(field.name());
            return true;
        }
        return false;
    }

    private static boolean removeStruct(ContentRecord target, FieldDef field, JsonElement current,
        String sourceName) {
        switch (shape(field)) {
            case "ammoMap" -> {
                if (!current.isJsonObject()) {
                    return false;
                }
                boolean removed = current.getAsJsonObject().remove(sourceName) != null;
                if (removed && current.getAsJsonObject().size() == 0) {
                    target.fields.remove(field.name());
                }
                return removed;
            }
            case "unitReq", "plan" -> {
                String key = shape(field).equals("unitReq") ? "block" : "unit";
                if (current.isJsonPrimitive()) {
                    return false;
                }
                if (current.isJsonObject()) {
                    JsonElement named = current.getAsJsonObject().get(key);
                    if (named != null && named.getAsString().equals(sourceName)) {
                        target.fields.remove(field.name());
                        return true;
                    }
                    return false;
                }
                JsonArray array = current.getAsJsonArray();
                boolean removed = false;
                for (int i = array.size() - 1; i >= 0; i--) {
                    JsonElement element = array.get(i);
                    if (element.isJsonObject() && element.getAsJsonObject().has(key)
                        && element.getAsJsonObject().get(key).getAsString().equals(sourceName)) {
                        array.remove(i);
                        removed = true;
                    }
                }
                if (removed && array.isEmpty()) {
                    target.fields.remove(field.name());
                }
                return removed;
            }
            case "consume" -> {
                JsonElement stripped = stripStack(current, sourceName, "item");
                if (stripped == null) {
                    target.fields.remove(field.name());
                    return true;
                }
                if (!stripped.equals(current)) {
                    target.fields.put(field.name(), stripped);
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Deep-strips every stack entry naming {@code sourceName} from a consume structure. An empty
     * group or array is dropped ({@code null}); a lone consumed group keeps its other keys, such
     * as {@code power}. {@code kind} is the stack key implied by the slot the value sits in.
     */
    private static JsonElement stripStack(JsonElement node, String sourceName, String kind) {
        if (node.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement element : node.getAsJsonArray()) {
                JsonElement cleaned = stripStack(element, sourceName, kind);
                if (cleaned != null) {
                    out.add(cleaned);
                }
            }
            return out.isEmpty() ? null : out;
        }
        if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            if (isStackObject(object, sourceName)) {
                return null;
            }
            JsonObject out = new JsonObject();
            for (var entry : object.entrySet()) {
                String slotKind = entry.getKey().equals("liquids") ? "liquid" : kind;
                JsonElement cleaned = stripStack(entry.getValue(), sourceName, slotKind);
                if (cleaned != null) {
                    out.add(entry.getKey(), cleaned);
                }
            }
            return out.entrySet().isEmpty() ? null : out;
        }
        if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()
            && stackName(node.getAsString()).equals(sourceName)) {
            return null;
        }
        return node;
    }

    /** The content name of a "name/amount" stack spelling, or the text itself. */
    private static String stackName(String text) {
        int slash = text.indexOf('/');
        return slash > 0 ? text.substring(0, slash) : text;
    }

    private static boolean isStackObject(JsonObject object, String sourceName) {
        for (String key : new String[] {"item", "liquid", "payload"}) {
            JsonElement named = object.get(key);
            if (named != null && named.isJsonPrimitive() && named.getAsString().equals(sourceName)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject stackEntry(String stackKind, String sourceName) {
        JsonObject entry = new JsonObject();
        entry.addProperty(stackKind == null || stackKind.isEmpty() ? "item" : stackKind, sourceName);
        entry.addProperty("amount", 1);
        return entry;
    }

    /** The amount the entry referencing {@code sourceName} carries, or -1 when there is none. */
    public static int amountOf(ContentRecord target, FieldDef field, String sourceName) {
        JsonElement entry;
        if (field.type() == FieldType.struct) {
            entry = findStackEntry(target.fields.get(field.name()), sourceName);
        } else {
            entry = entryFor(target.fields.get(field.name()), field.stackKind(), sourceName);
        }
        if (entry == null) {
            return -1;
        }
        if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
            // The "copper/10" spelling carries the amount in the string itself.
            String text = entry.getAsString();
            int slash = text.indexOf('/');
            if (slash > 0) {
                try {
                    return Integer.parseInt(text.substring(slash + 1).trim());
                } catch (NumberFormatException unparseable) {
                    return 1;
                }
            }
            return 1;
        }
        if (entry.isJsonObject()) {
            JsonElement amount = entry.getAsJsonObject().get("amount");
            if (amount != null && amount.isJsonPrimitive() && amount.getAsJsonPrimitive().isNumber()) {
                return amount.getAsInt();
            }
        }
        return 1;
    }

    /**
     * Sets the amount on the stack entry that references {@code sourceName}. A bare-name entry is
     * upgraded to the explicit {"item":name,"amount":n} object. Returns the undo info, or null when
     * the field holds no matching entry.
     */
    public static Revert setAmount(ContentRecord target, FieldDef field, String sourceName,
        int amount) {
        JsonElement current = target.fields.get(field.name());
        if (current == null) {
            return null;
        }
        JsonElement snapshot = current.deepCopy();
        if (field.type() == FieldType.struct) {
            if (current.isJsonPrimitive() && current.getAsJsonPrimitive().isString()
                && stackName(current.getAsString()).equals(sourceName)) {
                // A bare "copper/10" value becomes the explicit object form.
                JsonObject entry = stackEntry("item", sourceName);
                entry.addProperty("amount", amount);
                target.fields.put(field.name(), entry);
                return new Revert(false, -1, snapshot);
            }
            boolean changed = setAmountDeep(current, sourceName, amount, "item");
            return changed ? new Revert(false, -1, snapshot) : null;
        }
        if (current.isJsonArray()) {
            JsonArray array = current.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (matches(array.get(i), field.stackKind(), sourceName)) {
                    array.set(i, withAmount(array.get(i), field.stackKind(), sourceName, amount));
                    return new Revert(false, -1, snapshot);
                }
            }
            return null;
        }
        if (matches(current, field.stackKind(), sourceName)) {
            target.fields.put(field.name(),
                withAmount(current, field.stackKind(), sourceName, amount));
            return new Revert(false, -1, snapshot);
        }
        return null;
    }

    /** Deep-searches a structured field for the stack entry naming {@code sourceName}. */
    private static JsonElement findStackEntry(JsonElement node, String sourceName) {
        if (node == null || node.isJsonNull()) {
            return null;
        }
        if (node.isJsonPrimitive()) {
            return node.getAsJsonPrimitive().isString()
                && stackName(node.getAsString()).equals(sourceName) ? node : null;
        }
        if (node.isJsonArray()) {
            for (JsonElement element : node.getAsJsonArray()) {
                JsonElement found = findStackEntry(element, sourceName);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        JsonObject object = node.getAsJsonObject();
        if (isStackObject(object, sourceName)) {
            return node;
        }
        for (var entry : object.entrySet()) {
            JsonElement found = findStackEntry(entry.getValue(), sourceName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * In-place amount update somewhere inside a structured field. A primitive "name/amount" entry
     * is upgraded to the explicit object; {@code kind} follows the slot the value sits in
     * ("items" → item, "liquids" → liquid).
     */
    private static boolean setAmountDeep(JsonElement node, String sourceName, int amount,
        String kind) {
        if (node == null || node.isJsonNull() || node.isJsonPrimitive()) {
            return false;
        }
        if (node.isJsonArray()) {
            JsonArray array = node.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement element = array.get(i);
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                    && stackName(element.getAsString()).equals(sourceName)) {
                    JsonObject entry = stackEntry(kind, sourceName);
                    entry.addProperty("amount", amount);
                    array.set(i, entry);
                    return true;
                }
                if (setAmountDeep(element, sourceName, amount, kind)) {
                    return true;
                }
            }
            return false;
        }
        JsonObject object = node.getAsJsonObject();
        if (isStackObject(object, sourceName)) {
            object.addProperty("amount", amount);
            return true;
        }
        for (var entry : object.entrySet()) {
            String slotKind = entry.getKey().equals("liquids") ? "liquid"
                : entry.getKey().equals("items") ? "item" : kind;
            if (setAmountDeep(entry.getValue(), sourceName, amount, slotKind)) {
                return true;
            }
        }
        return false;
    }

    private static JsonElement entryFor(JsonElement current, String stackKind, String sourceName) {
        if (current == null) {
            return null;
        }
        if (current.isJsonArray()) {
            for (JsonElement element : current.getAsJsonArray()) {
                if (matches(element, stackKind, sourceName)) {
                    return element;
                }
            }
            return null;
        }
        return matches(current, stackKind, sourceName) ? current : null;
    }

    private static JsonElement withAmount(JsonElement entry, String stackKind, String sourceName,
        int amount) {
        JsonObject object = entry.isJsonObject()
            ? entry.getAsJsonObject().deepCopy()
            : stackEntry(stackKind, sourceName);
        object.addProperty("amount", amount);
        return object;
    }

    private static boolean matches(JsonElement element, String stackKind, String sourceName) {
        if (element.isJsonPrimitive()) {
            return element.getAsString().equals(sourceName);
        }
        if (!element.isJsonObject()) {
            return false;
        }
        JsonElement named = element.getAsJsonObject()
            .get(stackKind == null || stackKind.isEmpty() ? "item" : stackKind);
        return named != null && named.isJsonPrimitive() && named.getAsString().equals(sourceName);
    }
}

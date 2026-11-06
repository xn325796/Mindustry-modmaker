package dev.modmaker.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dotted paths into a JSON tree, with optional array indices: {@code ammoTypes.copper},
 * {@code objectives[0].preset}.
 *
 * <p>Used to point at nested content (a bullet inside a turret's ammoTypes) so it can be shown as its
 * own node and written back into the host file.
 */
public final class JsonPath {

    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)]");
    /** Array indices beyond this bound are treated as absent; a real index can never reach it. */
    private static final int MAX_INDEX = 100_000;

    /** One step of a path: a member name or an array index. */
    public record Step(String key, int index, boolean isIndex) {
        public static Step member(String key) {
            return new Step(key, -1, false);
        }

        public static Step index(int index) {
            return new Step(null, index, true);
        }
    }

    private JsonPath() {
    }

    public static List<Step> steps(String path) {
        List<Step> steps = new ArrayList<>();
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            int bracket = segment.indexOf('[');
            if (bracket < 0) {
                steps.add(Step.member(unescapeSegment(segment)));
                continue;
            }
            String key = unescapeSegment(segment.substring(0, bracket));
            if (!key.isEmpty()) {
                steps.add(Step.member(key));
            }
            Matcher indices = INDEX.matcher(segment.substring(bracket));
            while (indices.find()) {
                int index = safeIndex(indices.group(1));
                if (index >= 0) {
                    steps.add(Step.index(index));
                }
            }
        }
        return steps;
    }

    public static JsonElement get(JsonElement root, String path) {
        JsonElement current = root;
        for (Step step : steps(path)) {
            if (current == null || current.isJsonNull()) {
                return null;
            }
            if (step.isIndex()) {
                if (!current.isJsonArray() || step.index() >= current.getAsJsonArray().size()) {
                    return null;
                }
                current = current.getAsJsonArray().get(step.index());
            } else {
                if (!current.isJsonObject()) {
                    return null;
                }
                current = current.getAsJsonObject().get(step.key());
            }
        }
        return current;
    }

    /** Replaces the value at {@code path}, creating intermediate members when they are missing. */
    public static void set(JsonElement root, String path, JsonElement value) {
        List<Step> steps = steps(path);
        if (steps.isEmpty()) {
            return;
        }
        JsonElement current = root;
        for (int i = 0; i < steps.size() - 1; i++) {
            Step step = steps.get(i);
            Step next = steps.get(i + 1);
            if (step.isIndex()) {
                if (!current.isJsonArray()) {
                    return;
                }
                JsonArray array = padTo(current.getAsJsonArray(), step.index(), next);
                JsonElement element = array.get(step.index());
                // Same compatibility rule as the member branch below: a slot that already holds
                // something of the wrong shape is replaced, not written through.
                if (element == null || element.isJsonNull() || !isCompatible(element, next)) {
                    element = next.isIndex() ? new JsonArray() : new JsonObject();
                    array.set(step.index(), element);
                }
                current = element;
            } else {
                if (!current.isJsonObject()) {
                    return;
                }
                JsonObject object = current.getAsJsonObject();
                JsonElement child = object.get(step.key());
                if (child == null || child.isJsonNull() || !isCompatible(child, next)) {
                    child = next.isIndex() ? new JsonArray() : new JsonObject();
                    object.add(step.key(), child);
                }
                current = child;
            }
        }
        Step last = steps.get(steps.size() - 1);
        if (last.isIndex()) {
            if (!current.isJsonArray()) {
                return;
            }
            JsonArray array = padTo(current.getAsJsonArray(), last.index(), null);
            array.set(last.index(), value == null ? JsonNull.INSTANCE : value);
        } else if (current.isJsonObject()) {
            current.getAsJsonObject().add(last.key(), value == null ? JsonNull.INSTANCE : value);
        }
    }

    private static boolean isCompatible(JsonElement element, Step next) {
        return next.isIndex() ? element.isJsonArray() : element.isJsonObject();
    }

    private static JsonArray padTo(JsonArray array, int index, Step next) {
        while (array.size() <= index) {
            array.add(next != null && next.isIndex() ? new JsonArray() : new JsonObject());
        }
        return array;
    }

    /** The member name the value sits under, e.g. "copper" for "ammoTypes.copper". */
    public static String leafName(String path) {
        List<Step> steps = steps(path);
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (!steps.get(i).isIndex()) {
                return steps.get(i).key();
            }
        }
        return path;
    }

    /** Removes the value at {@code path}. Returns true when something was removed. */
    public static boolean remove(JsonElement root, String path) {
        List<Step> steps = steps(path);
        if (steps.isEmpty()) {
            return false;
        }
        JsonElement parent = root;
        for (int i = 0; i < steps.size() - 1; i++) {
            Step step = steps.get(i);
            if (step.isIndex()) {
                if (!parent.isJsonArray() || step.index() >= parent.getAsJsonArray().size()) {
                    return false;
                }
                parent = parent.getAsJsonArray().get(step.index());
            } else {
                if (!parent.isJsonObject()) {
                    return false;
                }
                parent = parent.getAsJsonObject().get(step.key());
            }
            if (parent == null) {
                return false;
            }
        }
        Step last = steps.get(steps.size() - 1);
        if (last.isIndex()) {
            if (parent.isJsonArray() && last.index() < parent.getAsJsonArray().size()) {
                parent.getAsJsonArray().remove(last.index());
                return true;
            }
            return false;
        }
        return parent.isJsonObject() && parent.getAsJsonObject().remove(last.key()) != null;
    }

    /** Appends a member to a path, escaping structural characters in the member name. */
    public static String child(String path, String key) {
        String escaped = escapeSegment(key);
        return path == null || path.isEmpty() ? escaped : path + "." + escaped;
    }

    /**
     * Escapes a member name so a dot, bracket or tilde inside it cannot be mistaken for path
     * structure when the dotted string is parsed back by {@link #steps}.
     */
    public static String escapeSegment(String key) {
        if (key == null) {
            return null;
        }
        return key.replace("~", "~0").replace(".", "~1")
            .replace("[", "~2").replace("]", "~3");
    }

    /** Reverses {@link #escapeSegment}; unknown tilde sequences are kept literally. */
    public static String unescapeSegment(String segment) {
        StringBuilder out = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '~' && i + 1 < segment.length()) {
                char next = segment.charAt(i + 1);
                switch (next) {
                    case '0' -> out.append('~');
                    case '1' -> out.append('.');
                    case '2' -> out.append('[');
                    case '3' -> out.append(']');
                    default -> {
                        out.append('~');
                        out.append(next);
                    }
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** A bounded array index, or -1 when the text is not a sane index (defense in depth). */
    private static int safeIndex(String text) {
        try {
            int index = Integer.parseInt(text);
            return index >= 0 && index <= MAX_INDEX ? index : -1;
        } catch (NumberFormatException outOfRange) {
            return -1;
        }
    }

    /** Appends an array index to a path. */
    public static String childIndex(String path, int index) {
        return (path == null || path.isEmpty() ? "" : path) + "[" + index + "]";
    }
}

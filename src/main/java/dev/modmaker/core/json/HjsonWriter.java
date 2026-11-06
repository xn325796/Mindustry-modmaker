package dev.modmaker.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * Serialises a field tree as HJSON, the dialect Mindustry mods are hand-written in: a braceless
 * root object, one "key: value" member per line, quoteless strings where that is safe, and braced
 * nested objects.
 *
 * <p>Round trip is the invariant: {@code HjsonReader.read(write(tree))} equals the tree, which is
 * why strings that merely look like numbers, booleans or null are quoted, and why commas, hashes
 * and comment openers force quotes (arc Jval treats them structurally in quoteless values).
 * Comments cannot be recovered from a parsed tree, so a converted file is data-faithful, not
 * byte-faithful.
 *
 * <p>The comma rule is deliberately context-free. Inside an array a comma terminates the element,
 * and in object context the reader first tries the span up to the comma as true/false/null/a number
 * - so an unquoted {@code description: 1,000 credits} is read as the number 1 followed by a stray
 * member, which the game rejects with a {@code JsonParseException}. Quoting every string that
 * contains a comma is therefore the only spelling that re-parses in both contexts.
 */
public final class HjsonWriter {

    public static String write(JsonElement element) {
        return new HjsonWriter().writeTree(element);
    }

    /**
     * Writes a value the way it appears <em>inside</em> a file, so an object keeps its braces.
     * {@link #write} is for a whole file, where the root object is the braceless part; splicing that
     * spelling into "key: ..." would drop the value's braces and break the file.
     */
    public static String writeNested(JsonElement element) {
        StringBuilder out = new StringBuilder();
        new HjsonWriter().writeValue(element, out, "");
        return out.toString();
    }

    private String writeTree(JsonElement element) {
        StringBuilder out = new StringBuilder();
        if (element != null && element.isJsonObject()) {
            writeMembers(element.getAsJsonObject(), out, "", true);
        } else {
            writeValue(element, out, "");
        }
        return out.toString();
    }

    // --- members ---------------------------------------------------------------------------------

    private void writeMembers(JsonObject object, StringBuilder out, String indent,
        boolean braceless) {
        boolean first = true;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!first) {
                out.append('\n');
            }
            first = false;
            out.append(indent).append(writeKey(entry.getKey())).append(": ");
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject()) {
                JsonObject nested = value.getAsJsonObject();
                if (nested.isEmpty()) {
                    out.append("{}");
                } else {
                    out.append("{\n");
                    writeMembers(nested, out, indent + "  ", false);
                    out.append('\n').append(indent).append('}');
                }
            } else {
                writeValue(value, out, indent);
            }
        }
        if (braceless && out.length() > 0) {
            out.append('\n');
        }
    }

    private void writeArray(JsonArray array, StringBuilder out, String indent) {
        boolean allPrimitives = true;
        int length = 0;
        for (JsonElement element : array) {
            if (element == null || element.isJsonPrimitive() || element.isJsonNull()) {
                length += String.valueOf(element).length() + 2;
            } else {
                allPrimitives = false;
                break;
            }
        }
        // No context flag is needed any more: a comma always forces quotes.
        if (allPrimitives && length < 70) {
            // Single line: [a, b, 3] - a comma still terminates an element inside arrays.
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                writeValue(array.get(i), out, indent);
            }
            out.append(']');
            return;
        }
        out.append("[\n");
        for (int i = 0; i < array.size(); i++) {
            out.append(indent).append("  ");
            JsonElement element = array.get(i);
            if (element != null && element.isJsonObject()) {
                JsonObject nested = element.getAsJsonObject();
                if (nested.isEmpty()) {
                    out.append("{}");
                } else {
                    out.append("{\n");
                    writeMembers(nested, out, indent + "  ", false);
                    out.append('\n').append(indent).append("  }");
                }
            } else {
                writeValue(element, out, indent + "  ");
            }
            if (i < array.size() - 1) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append(indent).append(']');
    }

    private void writeValue(JsonElement value, StringBuilder out, String indent) {
        if (value == null || value.isJsonNull()) {
            out.append("null");
        } else if (value.isJsonPrimitive()) {
            out.append(primitiveText(value.getAsJsonPrimitive()));
        } else if (value.isJsonArray()) {
            writeArray(value.getAsJsonArray(), out, indent);
        } else {
            JsonObject object = value.getAsJsonObject();
            if (object.isEmpty()) {
                out.append("{}");
            } else {
                out.append("{\n");
                writeMembers(object, out, indent + "  ", false);
                out.append('\n').append(indent).append('}');
            }
        }
    }

    // --- primitives ------------------------------------------------------------------------------

    private String primitiveText(JsonPrimitive value) {
        if (value.isBoolean() || value.isNumber()) {
            return value.getAsString();
        }
        String text = value.getAsString();
        if (isSafeQuoteless(text)) {
            return text;
        }
        return quote(text);
    }

    private String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * Whether a quoteless spelling re-parses to the same string: it must not look structural
     * (punctuators, comments), literal (true/false/null), number-like, or have padding whitespace
     * (the reader trims quoteless values, and a trailing "true " would re-parse as a boolean).
     */
    private boolean isSafeQuoteless(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        if (first <= ' ' || ",:{}[]\"'#".indexOf(first) >= 0) {
            return false;
        }
        if (text.charAt(text.length() - 1) <= ' ' || text.endsWith(",") || text.endsWith(":")) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < ' ' || c == '#' || c == '"' || c == '\'' || c == '\\' || c == '}'
                || c == ']') {
                return false;
            }
            if (c == '/' && i + 1 < text.length()
                && (text.charAt(i + 1) == '/' || text.charAt(i + 1) == '*')) {
                return false;
            }
        }
        if (text.indexOf(',') >= 0) {
            // Structural in both contexts, so it always forces quotes. Inside an array a comma
            // terminates the element; in object context the reader first tries the span up to the
            // comma as true/false/null/number, so an unquoted "1,000 credits" is read as 1 and the
            // remainder is re-parsed as another member - the game then fails the whole file.
            return false;
        }
        if (text.equals("true") || text.equals("false") || text.equals("null")) {
            return false;
        }
        // Number-like strings ("5", "0.5", "-3", "1e9") would re-parse as numbers.
        return !HjsonReader.looksLikeNumber(text);
    }

    private String writeKey(String key) {
        return key.matches("[A-Za-z0-9_-]+") ? key : quote(key);
    }
}

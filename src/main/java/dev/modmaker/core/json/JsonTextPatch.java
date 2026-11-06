package dev.modmaker.core.json;

import com.google.gson.JsonElement;

import java.util.List;

/**
 * Locates the exact text span of a value inside a JSON/HJSON document and splices a replacement in.
 *
 * <p>Content files are hand-written HJSON: a braceless or braced root, quoteless keys and strings,
 * optional commas, {@code //}, {@code #} and block comments. Re-serialising such a file from its
 * parsed tree loses every comment and every bit of hand alignment. A change confined to one member -
 * a bullet inside a turret's {@code ammoTypes}, say - only needs that member's text replaced, so it
 * is applied to the original bytes instead and everything else is copied through untouched.
 *
 * <p>This is deliberately a locator rather than a parser: it has to find where a value starts and
 * ends and skip strings and comments correctly, nothing more. When a path cannot be located
 * {@link #replace} returns null and the caller falls back to re-serialising the record - lossy but
 * correct. The syntax accepted mirrors {@link HjsonReader} (see its {@code parseBare} for why a
 * comma ends a value in an array but not in an object).
 */
public final class JsonTextPatch {

    /** Half-open character range {@code [start, end)} into the source text. */
    public record Span(int start, int end) {
    }

    private JsonTextPatch() {
    }

    /** The span of the value at {@code path}, or null when it cannot be located. */
    public static Span valueSpan(String source, String path) {
        if (source == null || path == null) {
            return null;
        }
        List<JsonPath.Step> steps = JsonPath.steps(path);
        if (steps.isEmpty()) {
            return null;
        }

        int length = source.length();
        int start = source.startsWith("\uFEFF") ? 1 : 0;
        start = skipTrivia(source, start, length);
        if (start >= length) {
            return null;
        }

        // A braced root holds its members between the braces; a braceless one runs to the end.
        Span container;
        if (source.charAt(start) == '{') {
            int afterObject = skipValue(source, start, length, false);
            if (afterObject < 0) {
                return null;
            }
            container = new Span(start + 1, afterObject - 1);
        } else {
            container = new Span(start, length);
        }
        boolean array = false;

        for (int i = 0; i < steps.size(); i++) {
            JsonPath.Step step = steps.get(i);
            // An index only exists in an array and a name only in an object; a path that gets that
            // wrong is not there, and guessing would splice into the wrong place.
            if (step.isIndex() != array) {
                return null;
            }
            Span value = step.isIndex()
                ? findElement(source, container, step.index())
                : findMember(source, container, step.key());
            if (value == null) {
                return null;
            }
            if (i == steps.size() - 1) {
                return value;
            }
            char first = source.charAt(value.start());
            if (first != '{' && first != '[') {
                return null;
            }
            // Descending: the next step lives inside the value's braces/brackets, not around them.
            int after = skipValue(source, value.start(), length, false);
            if (after < 0 || after - 1 <= value.start()) {
                return null;
            }
            container = new Span(value.start() + 1, after - 1);
            array = first == '[';
        }
        return null;
    }

    /**
     * Replaces the whole value at {@code path} with {@code replacement}, keeping every other byte of
     * {@code source}. Returns null when the path cannot be located. The replacement is re-indented to
     * the line the original value starts on, so a spliced block keeps its neighbours' alignment.
     */
    public static String replace(String source, String path, String replacement) {
        Span span = valueSpan(source, path);
        if (span == null) {
            return null;
        }
        return source.substring(0, span.start()) + reindent(source, span, replacement)
            + source.substring(span.end());
    }

    // --- locating -------------------------------------------------------------------------------

    private static Span findMember(String source, Span container, String key) {
        int p = container.start();
        int end = container.end();
        while (true) {
            p = skipTrivia(source, p, end);
            if (p >= end || source.charAt(p) == '}') {
                return null;
            }
            int keyEnd = skipKey(source, p, end);
            if (keyEnd < 0) {
                return null;
            }
            String found = unquote(source.substring(p, keyEnd));
            p = skipTrivia(source, keyEnd, end);
            if (p >= end || source.charAt(p) != ':') {
                return null;
            }
            p = skipTrivia(source, p + 1, end);
            if (p >= end) {
                return null;
            }
            int valueEnd = skipValue(source, p, end, false);
            if (valueEnd < 0) {
                return null;
            }
            if (found.equals(key)) {
                return new Span(p, valueEnd);
            }
            p = skipTrivia(source, valueEnd, end);
            if (p < end && source.charAt(p) == ',') {
                p++;
            }
        }
    }

    private static Span findElement(String source, Span container, int index) {
        int p = container.start();
        int end = container.end();
        int position = 0;
        while (true) {
            p = skipTrivia(source, p, end);
            if (p >= end || source.charAt(p) == ']') {
                return null;
            }
            int valueEnd = skipValue(source, p, end, true);
            if (valueEnd < 0) {
                return null;
            }
            if (position == index) {
                return new Span(p, valueEnd);
            }
            position++;
            p = skipTrivia(source, valueEnd, end);
            if (p < end && source.charAt(p) == ',') {
                p++;
            }
        }
    }

    private static int skipKey(String source, int p, int end) {
        char c = source.charAt(p);
        if (c == '"') {
            return skipQuoted(source, p, end, '"');
        }
        if (c == '\'') {
            return source.startsWith("'''", p) ? skipRawString(source, p, end)
                : skipQuoted(source, p, end, '\'');
        }
        int i = p;
        while (i < end) {
            char current = source.charAt(i);
            if (current == ':' || current == '\n' || current == '\r' || current == '{'
                || current == '}') {
                break;
            }
            i++;
        }
        return source.substring(p, i).isBlank() ? -1 : i;
    }

    // --- value extents --------------------------------------------------------------------------

    /** Index just past the value starting at {@code p} (trivia already skipped), or -1. */
    private static int skipValue(String source, int p, int end, boolean inArray) {
        char c = source.charAt(p);
        if (c == '{') {
            return skipObject(source, p, end);
        }
        if (c == '[') {
            return skipArray(source, p, end);
        }
        if (c == '"') {
            return skipQuoted(source, p, end, '"');
        }
        if (c == '\'') {
            return source.startsWith("'''", p) ? skipRawString(source, p, end)
                : skipQuoted(source, p, end, '\'');
        }
        return skipBare(source, p, end, inArray);
    }

    private static int skipObject(String source, int p, int end) {
        int i = p + 1;
        while (true) {
            i = skipTrivia(source, i, end);
            if (i >= end) {
                return -1;
            }
            if (source.charAt(i) == '}') {
                return i + 1;
            }
            int keyEnd = skipKey(source, i, end);
            if (keyEnd < 0) {
                return -1;
            }
            i = skipTrivia(source, keyEnd, end);
            if (i >= end || source.charAt(i) != ':') {
                return -1;
            }
            i = skipTrivia(source, i + 1, end);
            if (i >= end) {
                return -1;
            }
            i = skipValue(source, i, end, false);
            if (i < 0) {
                return -1;
            }
            i = skipTrivia(source, i, end);
            if (i < end && source.charAt(i) == ',') {
                i++;
            }
        }
    }

    private static int skipArray(String source, int p, int end) {
        int i = p + 1;
        while (true) {
            i = skipTrivia(source, i, end);
            if (i >= end) {
                return -1;
            }
            if (source.charAt(i) == ']') {
                return i + 1;
            }
            i = skipValue(source, i, end, true);
            if (i < 0) {
                return -1;
            }
            i = skipTrivia(source, i, end);
            if (i < end && source.charAt(i) == ',') {
                i++;
            }
        }
    }

    /**
     * A quoteless value. Mirror of {@code HjsonReader.parseBare}: in an array a comma ends the value,
     * in an object it only ends it when the span up to the comma is a literal, and otherwise the
     * comma is part of the string ("The rampant, filled with danger").
     */
    private static int skipBare(String source, int p, int end, boolean inArray) {
        int i = p;
        while (i < end) {
            char c = source.charAt(i);
            boolean comment = c == '#' || (c == '/' && i + 1 < end
                && (source.charAt(i + 1) == '/' || source.charAt(i + 1) == '*'));
            boolean lineEnd = c == '\n' || c == '\r' || c == ']' || c == '}' || comment;
            if (lineEnd || c == ',') {
                if (isLiteral(source, p, i)) {
                    return trimEnd(source, p, i);
                }
                if (lineEnd || inArray) {
                    return trimEnd(source, p, i);
                }
            }
            i++;
        }
        return trimEnd(source, p, end);
    }

    private static boolean isLiteral(String source, int start, int stop) {
        int s = start;
        int e = stop;
        while (s < e && source.charAt(s) <= ' ') {
            s++;
        }
        while (e > s && source.charAt(e - 1) <= ' ') {
            e--;
        }
        String token = source.substring(s, e);
        char first = token.isEmpty() ? ' ' : token.charAt(0);
        return switch (first) {
            case 'f' -> token.equals("false");
            case 'n' -> token.equals("null");
            case 't' -> token.equals("true");
            default -> (first == '-' || (first >= '0' && first <= '9'))
                && HjsonReader.looksLikeNumber(token);
        };
    }

    private static int trimEnd(String source, int start, int stop) {
        int e = stop;
        while (e > start && source.charAt(e - 1) <= ' ') {
            e--;
        }
        return e;
    }

    // --- strings and trivia ---------------------------------------------------------------------

    private static int skipQuoted(String source, int p, int end, char quote) {
        int i = p + 1;
        while (i < end) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    private static int skipRawString(String source, int p, int end) {
        int close = source.indexOf("'''", p + 3);
        return close < 0 || close + 3 > end ? -1 : close + 3;
    }

    private static int skipTrivia(String source, int p, int end) {
        int i = p;
        while (i < end) {
            char c = source.charAt(i);
            if (c <= ' ') {
                i++;
                continue;
            }
            if (c == '#' || (c == '/' && i + 1 < end && source.charAt(i + 1) == '/')) {
                i = skipToLineEnd(source, i + 1, end);
                continue;
            }
            if (c == '/' && i + 1 < end && source.charAt(i + 1) == '*') {
                int close = source.indexOf("*/", i + 2);
                i = close < 0 || close + 2 > end ? end : close + 2;
                continue;
            }
            break;
        }
        return i;
    }

    private static int skipToLineEnd(String source, int p, int end) {
        int i = p;
        while (i < end && source.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    // --- misc -----------------------------------------------------------------------------------

    private static String unquote(String key) {
        String trimmed = key.trim();
        if (trimmed.length() >= 2
            && (trimmed.charAt(0) == '"' || trimmed.charAt(0) == '\'')
            && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String reindent(String source, Span span, String replacement) {
        String text = replacement.stripTrailing();
        int lineStart = source.lastIndexOf('\n', span.start() - 1) + 1;
        String indent = source.substring(lineStart, span.start());
        if (!indent.isBlank()) {
            // The value starts mid-line (ammoTypes: { name: {...} }); the text keeps its own layout.
            return text;
        }
        return indent.isEmpty() ? text : text.replace("\n", "\n" + indent);
    }

    /** The parsed tree of {@code text}, or null when it does not parse. */
    public static JsonElement parseOrNull(String text) {
        try {
            return HjsonReader.read(text);
        } catch (RuntimeException broken) {
            return null;
        }
    }
}

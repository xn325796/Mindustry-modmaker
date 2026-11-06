package dev.modmaker.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Tolerant reader for the JSON/HJSON dialect Mindustry accepts.
 *
 * <p>Every meta and content file goes through the game's HJSON parser (ContentParser.java:1026 uses
 * Jval.read on the file text), so real mods rely on the lenient forms: slash-slash and hash
 * comments, slash-star blocks, unquoted keys, single quotes, trailing commas, and - most
 * importantly - members separated only by newlines. This reader accepts all of them.
 *
 * <p>The game escapes stray hash characters in .json files before parsing them
 * (ContentParser.java:1026-1029); a backslash-escaped character that is not a known escape is
 * therefore taken literally here too.
 */
public final class HjsonReader {

    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

    /** Caps nesting so a hostile file cannot overflow the stack. */
    private static final int MAX_DEPTH = 256;

    private final String src;
    private int pos;
    private int depth;
    /** Mirrors arc Jval's isArray flag: inside arrays a comma terminates a bare value. */
    private boolean inArray;

    private HjsonReader(String src) {
        this.src = src;
    }

    public static JsonElement read(String text) {
        HjsonReader reader = new HjsonReader(Json.stripBom(text));
        reader.skipTrivia();
        if (!reader.atEnd() && (reader.peek() == '{' || reader.peek() == '[')) {
            JsonElement value = reader.parseValue();
            reader.skipTrivia();
            if (!reader.atEnd()) {
                throw reader.error("Unexpected trailing content");
            }
            return value;
        }
        // Braces for the root object are optional (arc Jval.parse); members are separated by
        // newlines ("color: 647CC2\nhardness: 3"), which real Mindustry mods rely on. When it
        // does not parse as an object, retry as a single scalar value, like Jval does.
        try {
            JsonElement value = reader.parseUnbracedObject();
            reader.skipTrivia();
            if (!reader.atEnd()) {
                throw reader.error("Unexpected trailing content");
            }
            return value;
        } catch (RuntimeException objectFailure) {
            HjsonReader retry = new HjsonReader(reader.src);
            try {
                retry.skipTrivia();
                JsonElement value = retry.parseValue();
                retry.skipTrivia();
                if (!retry.atEnd()) {
                    throw objectFailure;
                }
                return value;
            } catch (RuntimeException ignored) {
                throw objectFailure;
            }
        }
    }

    /**
     * Reads a plain .json file the way ContentParser.parse does (v160.5): every '#' is escaped to
     * "\#" first, so a hash never starts a comment there - it stays a literal inside unquoted
     * values such as colours.
     */
    public static JsonElement readJson(String text) {
        return read(text.replace("#", "\\#"));
    }

    /** {@link #readJson(String)}, required to be an object at the top level. */
    public static JsonObject readJsonObject(String text) {
        JsonElement element = readJson(text);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return element.getAsJsonObject();
    }

    public static JsonObject readObject(String text) {
        JsonElement element = read(text);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return element.getAsJsonObject();
    }

    // --- values ---------------------------------------------------------------------------------

    private JsonElement parseValue() {
        if (++depth > MAX_DEPTH) {
            throw error("JSON nested more than " + MAX_DEPTH + " levels deep");
        }
        try {
            return parseValueInner();
        } finally {
            depth--;
        }
    }

    private JsonElement parseValueInner() {
        skipTrivia();
        if (atEnd()) {
            throw error("Unexpected end of input");
        }
        char c = peek();
        if (c == '{') {
            return parseObject();
        }
        if (c == '[') {
            return parseArray();
        }
        if (c == '"') {
            return new JsonPrimitive(parseQuoted('"'));
        }
        if (c == '\'') {
            return src.startsWith("'''", pos)
                ? new JsonPrimitive(parseRawString())
                : new JsonPrimitive(parseQuoted('\''));
        }
        return parseBare();
    }

    private JsonObject parseObject() {
        expect('{');
        JsonObject object = new JsonObject();
        while (true) {
            skipTrivia();
            if (atEnd()) {
                throw error("Unterminated object");
            }
            if (peek() == '}') {
                pos++;
                return object;
            }
            String key = parseKey();
            skipTrivia();
            expect(':');
            object.add(key, parseValue());
            skipTrivia();
            if (!atEnd() && peek() == ',') {
                pos++;
            }
        }
    }

    /** A braceless root object: "key: value" members separated by newlines until end of input. */
    private JsonObject parseUnbracedObject() {
        JsonObject object = new JsonObject();
        while (true) {
            skipTrivia();
            if (atEnd()) {
                return object;
            }
            String key = parseKey();
            skipTrivia();
            expect(':');
            object.add(key, parseValue());
            skipTrivia();
            if (!atEnd() && peek() == ',') {
                pos++;
            }
        }
    }

    private JsonArray parseArray() {
        expect('[');
        JsonArray array = new JsonArray();
        boolean outer = inArray;
        inArray = true;
        try {
            while (true) {
                skipTrivia();
                if (atEnd()) {
                    throw error("Unterminated array");
                }
                if (peek() == ']') {
                    pos++;
                    return array;
                }
                array.add(parseValue());
                skipTrivia();
                if (!atEnd() && peek() == ',') {
                    pos++;
                }
            }
        } finally {
            // Restore, don't clear: after a nested array closes we are still lexically inside the
            // outer one, and its bare values must keep terminating at commas.
            inArray = outer;
        }
    }

    private String parseKey() {
        skipTrivia();
        char c = peek();
        if (c == '"') {
            return parseQuoted('"');
        }
        if (c == '\'') {
            return parseQuoted('\'');
        }
        int start = pos;
        while (!atEnd()) {
            char current = peek();
            if (current == ':' || current == '\n' || current == '\r' || current == '{' || current == '}') {
                break;
            }
            pos++;
        }
        String key = src.substring(start, pos).trim();
        if (key.isEmpty()) {
            throw error("Expected a key");
        }
        return key;
    }

    private String parseQuoted(char quote) {
        expect(quote);
        StringBuilder out = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw error("Unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == quote) {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (atEnd()) {
                throw error("Unterminated escape");
            }
            char escaped = src.charAt(pos++);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '/' -> out.append('/');
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                case '\'' -> out.append('\'');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw error("Truncated unicode escape");
                    }
                    String hex = src.substring(pos, pos + 4);
                    pos += 4;
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException badHex) {
                        throw error("Bad unicode escape \\u" + hex);
                    }
                }
                // The game rewrites bare '#' to '\#' before parsing, so an unknown escape is literal.
                default -> out.append(escaped);
            }
        }
    }

    /**
     * Triple-quoted multiline string, with arc readMlString's semantics: the first line break is
     * consumed, each continuation line loses up to the opening delimiter's indent, and one
     * trailing newline is dropped. Content on the opening line is kept verbatim.
     */
    private String parseRawString() {
        int lineStart = src.lastIndexOf('\n', pos) + 1;
        int indent = pos - lineStart; // columns before the opening ''' (quote)
        pos += 3;
        int end = src.indexOf("'''", pos);
        if (end < 0) {
            throw error("Unterminated raw string");
        }
        String body = src.substring(pos, end);
        pos = end + 3;
        String[] lines = body.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        // The opening line break is consumed: content starts at lines[1] unless lines[0] has text.
        boolean firstWritten = !lines[0].isBlank();
        if (firstWritten) {
            out.append(lines[0].stripLeading());
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int strip = 0;
            while (strip < indent && strip < line.length()
                && (line.charAt(strip) == ' ' || line.charAt(strip) == '\t')) {
                strip++;
            }
            String stripped = line.substring(strip);
            if (firstWritten) {
                out.append('\n');
            }
            firstWritten = true;
            out.append(stripped);
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * Quoteless value, a faithful port of arc Jval.readTfnns: the value ends at the line end, a
     * closing bracket or a comment. A comma terminates it only inside arrays; in object context
     * the span up to a comma is first tried as true/false/null/number (so {@code a: 1, b: 2}
     * yields two members), and otherwise the comma is simply part of the string - which is how
     * descriptions like "The rampant, filled with danger" survive in real mods.
     */
    private JsonElement parseBare() {
        int start = pos;
        char first = peek();
        if (",:[]}".indexOf(first) >= 0) {
            throw error("Found a punctuator character '" + first
                + "' when expecting a quoteless string (check your syntax)");
        }
        while (true) {
            boolean isComment = !atEnd() && (peek() == '#'
                || (peek() == '/' && pos + 1 < src.length()
                    && (src.charAt(pos + 1) == '/' || src.charAt(pos + 1) == '*')));
            boolean isEol = atEnd() || peek() == '\n' || peek() == '\r' || peek() == ']'
                || peek() == '}' || isComment || (peek() == ',' && inArray);
            if (isEol || peek() == ',') {
                int stop = atEnd() ? src.length() : pos;
                JsonElement literal = tryLiteral(start, stop, first);
                if (literal != null) {
                    return literal;
                }
                if (isEol) {
                    int end = stop;
                    if (end > start && src.charAt(end - 1) == ',') {
                        end--; // trailing comma, consumed into the value in object context
                    }
                    return new JsonPrimitive(src.substring(start, end).trim().replace("\\#", "#"));
                }
            }
            pos++;
        }
    }

    /** True/false/null/number check over the span, mirroring Jval.readTfnns' tfnns switch. */
    private JsonElement tryLiteral(int start, int stop, char first) {
        int s = start, e = stop;
        while (s < e && src.charAt(s) <= ' ') {
            s++;
        }
        while (e > s && src.charAt(e - 1) <= ' ') {
            e--;
        }
        int len = e - s;
        switch (first) {
            case 'f':
                if (len == 5 && src.startsWith("false", s)) {
                    return new JsonPrimitive(false);
                }
                break;
            case 'n':
                if (len == 4 && src.startsWith("null", s)) {
                    return JsonNull.INSTANCE;
                }
                break;
            case 't':
                if (len == 4 && src.startsWith("true", s)) {
                    return new JsonPrimitive(true);
                }
                break;
            default:
                if (first == '-' || (first >= '0' && first <= '9')) {
                    return tryNumber(src.substring(s, e));
                }
        }
        return null;
    }

    private JsonElement tryNumber(String token) {
        if (!NUMBER.matcher(token).matches()) {
            return null;
        }
        boolean integral = token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0;
        try {
            return new JsonPrimitive(integral ? Long.valueOf(Long.parseLong(token)) : new BigDecimal(token));
        } catch (NumberFormatException ignored) {
            // Beyond long: arc's tryParseNumber still parses it (as a double); keep full
            // precision with BigDecimal so the tree matches what the game sees.
            try {
                return new JsonPrimitive(new BigDecimal(token));
            } catch (NumberFormatException absurd) {
                return null;
            }
        }
    }

    // --- trivia ---------------------------------------------------------------------------------

    private void skipTrivia() {
        while (!atEnd()) {
            char c = peek();
            if (Character.isWhitespace(c)) {
                pos++;
                continue;
            }
            if (c == '/' && pos + 1 < src.length()) {
                char next = src.charAt(pos + 1);
                if (next == '/') {
                    skipToLineEnd();
                    continue;
                }
                if (next == '*') {
                    skipBlockComment();
                    continue;
                }
            }
            // HJSON hash comments start anywhere outside strings (arc Jval), not just line starts.
            if (c == '#') {
                skipToLineEnd();
                continue;
            }
            return;
        }
    }

    private void skipToLineEnd() {
        while (!atEnd() && peek() != '\n') {
            pos++;
        }
    }

    private void skipBlockComment() {
        pos += 2;
        while (pos + 1 < src.length() && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
            pos++;
        }
        pos = Math.min(pos + 2, src.length());
    }

    // --- primitives -----------------------------------------------------------------------------

    private void expect(char expected) {
        skipTrivia();
        if (atEnd() || peek() != expected) {
            throw error("Expected '" + expected + "'");
        }
        pos++;
    }

    private boolean atEnd() {
        return pos >= src.length();
    }

    private char peek() {
        return src.charAt(pos);
    }

    /** The value as a plain number ("5", "-0.5", "1e9"), by Jval's number rules. */
    public static boolean looksLikeNumber(String text) {
        return NUMBER.matcher(text).matches();
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < pos && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new IllegalArgumentException(message + " at line " + line + ", column " + column);
    }
}

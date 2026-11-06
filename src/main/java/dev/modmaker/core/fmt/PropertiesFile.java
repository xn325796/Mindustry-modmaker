package dev.modmaker.core.fmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reader/writer for Java .properties, the format of bundles/&lt;name&gt;.properties.
 *
 * <p>The game merges bundles additively with PropertiesUtils.load (Mods.java:651-663) and vanilla
 * bundles ship raw UTF-8 text, so values go out as-is in UTF-8 and only structurally required
 * characters are escaped.
 *
 * <p>A bundle is also a hand-edited document - the sample mod's bundle.properties carries 62 comment
 * lines - so editing follows the file instead of rebuilding it. Parsing keeps every source line, a
 * changed key rewrites only its own line (keeping the key's spelling, its separator, the spacing
 * around it and the line ending), untouched lines are emitted byte for byte, new keys are appended
 * and a key that disappears drops its line. Parsing and writing without an edit yields the input
 * back unchanged, and an edit touches exactly the lines whose values differ.
 */
public final class PropertiesFile {

    /** The parsed values, in the order the file defines them; keys added later are appended. */
    public final LinkedHashMap<String, String> values = new LinkedHashMap<>();

    /**
     * Non-fatal problems found while reading: escapes the game would reject and a leading BOM. The
     * text is still parsed (the file keeps its bytes until the author fixes it), but the importer
     * and the validator report these - the game's loader drops the whole bundle for either of them.
     */
    public final List<String> problems = new ArrayList<>();

    /** Source lines in order, so the document's own structure survives an edit. */
    private final List<Slice> source = new ArrayList<>();

    /** Byte order mark the source started with, re-emitted as it was. */
    private String leading = "";

    /**
     * One logical source line: its exact text with the line ending that followed it, plus - for the
     * lines that define a key - everything needed to rewrite only the value.
     *
     * @param raw        the line as it appears in the file, continuations included
     * @param terminator the line ending that followed it (empty for a last line without one)
     * @param prefix     everything before the value: indentation, key spelling, separator, spacing
     * @param separated  whether the line has an explicit "=" or ":" (a bare key does not)
     */
    private record Slice(String raw, String terminator, String prefix, boolean separated,
        String key, String value) {

        boolean definesKey() {
            return key != null;
        }
    }

    public static PropertiesFile parse(String text) {
        PropertiesFile file = new PropertiesFile();
        file.read(text);
        return file;
    }

    /** Sets a value; a key the file already defines is rewritten in place, a new one is appended. */
    public void put(String key, String value) {
        values.put(key, value);
    }

    /** Drops a key together with the line that defines it. */
    public void remove(String key) {
        values.remove(key);
    }

    /** The file as text: untouched lines verbatim, changed values rewritten, new keys appended. */
    public String write() {
        StringBuilder out = new StringBuilder(leading);
        Map<String, Integer> lastOfKey = new LinkedHashMap<>();
        for (int i = 0; i < source.size(); i++) {
            Slice slice = source.get(i);
            if (slice.definesKey()) {
                lastOfKey.put(slice.key(), i);
            }
        }
        Set<String> written = new LinkedHashSet<>();
        for (int i = 0; i < source.size(); i++) {
            Slice slice = source.get(i);
            if (!slice.definesKey()) {
                out.append(slice.raw()).append(slice.terminator());
                continue;
            }
            written.add(slice.key());
            String value = values.get(slice.key());
            if (value == null) {
                continue;                       // removed: every line defining it goes away
            }
            boolean effective = Integer.valueOf(i).equals(lastOfKey.get(slice.key()));
            if (!effective || value.equals(slice.value())) {
                // An earlier duplicate the game overrides anyway, or an untouched value: the line is
                // emitted exactly as it came in.
                out.append(slice.raw()).append(slice.terminator());
                continue;
            }
            out.append(slice.prefix());
            if (!slice.separated()) {
                out.append('=');
            }
            out.append(escapeValue(value)).append(slice.terminator());
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (written.contains(entry.getKey())) {
                continue;
            }
            if (out.length() > 0 && !endsWithLineBreak(out)) {
                if (endsWithOddBackslashes(out)) {
                    // The last logical line ends in a continuation. Emitting the new key right after
                    // it would make the key part of that value (PropertiesUtils.load joins them), so
                    // the dangling backslash is made literal first: one more backslash keeps the
                    // value's meaning while ending the line.
                    out.append('\\');
                }
                out.append('\n');
            }
            out.append(escapeKey(entry.getKey())).append('=').append(escapeValue(entry.getValue()))
                .append('\n');
        }
        return out.toString();
    }

    // --- reading ---------------------------------------------------------------------------------

    private void read(String text) {
        String body = text;
        if (body.startsWith("\uFEFF")) {
            leading = "\uFEFF";
            body = body.substring(1);
            // The game's loader reads the bytes as they are, so the BOM ends up inside the first
            // key's name there and that entry is dead in game, while this reader sees a clean key.
            // The BOM is preserved on write (the byte-for-byte rule); the author removes it.
            problems.add("the file starts with a UTF-8 BOM: the game keeps it inside the first key's"
                + " name, so that key is unreachable in game");
        }
        List<String> raws = new ArrayList<>();
        List<String> endings = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\n' && c != '\r') {
                continue;
            }
            String ending = c == '\r' && i + 1 < body.length() && body.charAt(i + 1) == '\n'
                ? "\r\n" : String.valueOf(c);
            raws.add(body.substring(start, i));
            endings.add(ending);
            i += ending.length() - 1;
            start = i + 1;
        }
        if (start < body.length()) {
            raws.add(body.substring(start));
            endings.add("");
        }

        // Backslash continuations make several physical lines one logical line. The exact bytes are
        // kept for re-emission; the joined text (escape and break removed) is what gets parsed.
        // java.util.Properties and the game's PropertiesUtils never continue a comment line, so a
        // trailing backslash there is literal text and ends the logical line.
        StringBuilder raw = new StringBuilder();
        StringBuilder joined = new StringBuilder();
        boolean comment = false;
        for (int i = 0; i < raws.size(); i++) {
            String part = raws.get(i);
            raw.append(part);
            if (joined.length() == 0) {
                String head = part.stripLeading();
                comment = !head.isEmpty() && (head.charAt(0) == '#' || head.charAt(0) == '!');
                joined.append(part);
            } else {
                joined.append(part.stripLeading());
            }
            if (endsWithOddBackslashes(part) && !comment) {
                raw.append(endings.get(i));
                joined.setLength(joined.length() - 1);
                continue;
            }
            slice(raw.toString(), joined.toString(), endings.get(i));
            raw.setLength(0);
            joined.setLength(0);
        }
        if (!joined.isEmpty()) {
            slice(raw.toString(), joined.toString(), "");
        }
    }

    /** Records one logical line: comments and blanks as-is, key lines with their value split off. */
    private void slice(String raw, String text, String terminator) {
        String body = text.stripLeading();
        if (body.isEmpty() || body.charAt(0) == '#' || body.charAt(0) == '!') {
            source.add(new Slice(raw, terminator, null, false, null, null));
            return;
        }
        int keyStart = text.length() - body.length();
        int separator = findSeparator(text, keyStart);
        int afterKey = separator < 0 ? text.length() : separator;
        int i = afterKey;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        boolean separated = i < text.length() && (text.charAt(i) == '=' || text.charAt(i) == ':');
        if (separated) {
            i++;
        }
        String value = text.substring(i).stripLeading();
        String prefix = text.substring(0, text.length() - value.length());
        String key = decode(text.substring(keyStart, afterKey).trim(), "bundle key");
        String decoded = decode(value, "bundle value for '" + key + "'");
        source.add(new Slice(raw, terminator, prefix, separated, key, decoded));
        values.put(key, decoded);
    }

    private static boolean endsWithOddBackslashes(CharSequence line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static boolean endsWithLineBreak(CharSequence text) {
        char last = text.charAt(text.length() - 1);
        return last == '\n' || last == '\r';
    }

    private static int findSeparator(String line, int from) {
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                return i;
            }
        }
        return -1;
    }

    static String unescape(String text) {
        return unescape(text, null, null);
    }

    /** Reads one text with the file's problem list, so an unfixable escape is reported. */
    private String decode(String text, String where) {
        return unescape(text, problems, where);
    }

    private static String unescape(String text, List<String> problems, String where) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    Integer code = unicodeEscape(text, i);
                    if (code == null) {
                        // The game's PropertiesUtils.load throws on a \\u without four hex digits and
                        // Mods.buildFiles then drops the whole bundle file, so every string in it
                        // silently falls back. Keep the literal text here - the file still has its
                        // bytes - but say so instead of quietly decoding a different value.
                        if (problems != null) {
                            problems.add((where == null ? "bundle" : where) + ": invalid \\u escape"
                                + " (four hex digits are required); the game rejects the whole file");
                        }
                        // Keep the backslash too: the decoded value must stay the literal text
                        // with the escape marker intact, not "uZZZZ" with the marker gone.
                        out.append('\\').append(next);
                    } else {
                        out.append((char) (int) code);
                        i += 4;
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    /** The character a {@code \\uXXXX} escape at {@code u} denotes, or null when it is malformed. */
    private static Integer unicodeEscape(String text, int u) {
        if (u + 4 >= text.length()) {
            return null;
        }
        String hex = text.substring(u + 1, u + 5);
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) {
                return null;
            }
        }
        return Integer.parseInt(hex, 16);
    }

    static String escapeKey(String key) {
        StringBuilder out = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            // Every whitespace character is structural in a key: the loader and this reader both end
            // the key at the first blank, so an unescaped interior space turned "a b" into the key
            // "a" with the value "b=c". Escaping only the first/last space was not enough.
            boolean structural = c == '\\' || c == '=' || c == ':' || c == '#' || c == '!'
                || Character.isWhitespace(c);
            if (structural) {
                out.append('\\');
            }
            out.append(c == '\n' ? 'n' : c == '\r' ? 'r' : c == '\t' ? 't' : c == '\f' ? 'f' : c);
        }
        return out.toString();
    }

    static String escapeValue(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (i == 0 && (c == ' ' || c == '#' || c == '!')) {
                        out.append('\\');
                    }
                    out.append(c);
                }
            }
        }
        return out.toString();
    }
}

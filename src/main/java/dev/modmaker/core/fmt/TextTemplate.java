package dev.modmaker.core.fmt;

import java.util.List;

/**
 * Fills "{0}", "{1}" placeholders without MessageFormat's quote handling.
 *
 * <p>MessageFormat treats a single quote as the start of a quoted literal, so a plain English
 * sentence like "the game's maximum" renders as "the games maximum". Every pattern in this project
 * uses the simple {n} form, so a direct substitution is sufficient, predictable, and keeps the
 * English fallback a validator finding renders byte-identical to what the UI shows.
 */
public final class TextTemplate {

    private TextTemplate() {
    }

    public static String fill(String pattern, List<?> args) {
        if (pattern == null || args == null || args.isEmpty()) {
            return pattern;
        }
        String out = pattern;
        for (int i = 0; i < args.size(); i++) {
            Object value = args.get(i);
            out = out.replace("{" + i + "}", value == null ? "" : String.valueOf(value));
        }
        return out;
    }
}

package dev.modmaker.core.validate;

import dev.modmaker.core.fmt.TextTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * One finding from {@link Validator}. Levels match what the UI shows: errors are things the game
 * will refuse or misread, warnings are things it tolerates but the author probably did not intend.
 *
 * <p>A finding carries a message <em>key</em> plus its arguments, never a baked-in sentence: the
 * format kernel has no UI and must not depend on the UI's localisation class. {@link #message()}
 * renders the English text for logs, the console and tests; the validation panel renders the key
 * through the user's locale, so the same finding reads Chinese or English with the UI language.
 */
public record Issue(Level level, String key, List<Object> args, String contentId) {

    private static final ResourceBundle ENGLISH = ResourceBundle.getBundle("messages", Locale.ENGLISH);

    public Issue {
        // A copy, not List.copyOf: an argument may legitimately be null.
        args = Collections.unmodifiableList(new ArrayList<>(args));
    }

    public enum Level {
        error,
        warning
    }

    public static Issue error(String key, Object... args) {
        return new Issue(Level.error, key, Arrays.asList(args), null);
    }

    public static Issue warning(String key, Object... args) {
        return new Issue(Level.warning, key, Arrays.asList(args), null);
    }

    public static Issue errorOn(String contentId, String key, Object... args) {
        return new Issue(Level.error, key, Arrays.asList(args), contentId);
    }

    public static Issue warningOn(String contentId, String key, Object... args) {
        return new Issue(Level.warning, key, Arrays.asList(args), contentId);
    }

    /** The English text of this finding, used for logs, the console and test assertions. */
    public String message() {
        return ENGLISH.containsKey(key) ? TextTemplate.fill(ENGLISH.getString(key), args) : key;
    }

    public boolean isError() {
        return level == Level.error;
    }
}

package dev.modmaker.core.validate;

import dev.modmaker.core.fmt.TextTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard for the localised validation messages: a finding carries a key plus arguments, so the only
 * way it can read correctly in both languages is for both bundles to define every key. The English
 * text is what the logs and the other validator tests see; the Chinese text is what the panel shows
 * on a zh_CN machine (which is also the fallback trap that hid the English entry in the language
 * menu before {@code messages_en.properties} existed).
 */
class ValidatorLocalisationTest {

    private static final ResourceBundle EN = ResourceBundle.getBundle("messages", Locale.ENGLISH);
    private static final ResourceBundle ZH = ResourceBundle.getBundle("messages", Locale.SIMPLIFIED_CHINESE);

    @Test
    void everyValidatorKeyExistsInBothBundles() {
        List<String> untranslated = EN.keySet().stream()
            .filter(key -> key.startsWith("validate."))
            .filter(key -> !ZH.containsKey(key))
            .sorted()
            .toList();
        assertEquals(List.of(), untranslated, "every validator message needs a Chinese translation");

        List<String> orphaned = ZH.keySet().stream()
            .filter(key -> key.startsWith("validate."))
            .filter(key -> !EN.containsKey(key))
            .sorted()
            .toList();
        assertEquals(List.of(), orphaned, "a Chinese-only validator key has no English source");
    }

    @Test
    void aFindingRendersInEnglishAndChinese() {
        Issue issue = Issue.error("validate.block.tooLarge", "wall", 32, 16);

        assertEquals("validate.block.tooLarge", issue.key());
        assertEquals(List.of("wall", 32, 16), issue.args());
        // The English text keeps its apostrophe: the renderer must not use MessageFormat, which
        // would eat the quote and turn "game's" into "games".
        assertTrue(issue.message().contains("above the game's maximum of 16"), issue.message());

        String chinese = TextTemplate.fill(ZH.getString(issue.key()), issue.args());
        assertTrue(chinese.contains("超过游戏上限 16"), chinese);
        assertFalse(chinese.contains("maximum"), "the panel must not show the English phrasing: " + chinese);
    }

    @Test
    void aKeyWithoutAPatternFallsBackToTheKeyItself() {
        // The migration renders an un-translated key as-is rather than throwing, which is what makes
        // a missed key visible in the UI instead of crashing the panel.
        assertEquals("validate.not.a.real.key",
            Issue.warning("validate.not.a.real.key", "x").message());
    }
}

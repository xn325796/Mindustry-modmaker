package dev.modmaker.core.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text patch is what keeps a hand-written HJSON file's comments alive when only a nested value
 * changes, so the two things that matter are: it finds the right value, and it touches nothing else.
 */
class JsonTextPatchTest {

    private static final String TURRET = """
        type: ItemTurret
        size: 2
        // the main gun
        ammoTypes: {
          copper: {
            // fast but weak
            type: BasicBulletType
            damage: 10
          }
          lead: {
            type: BasicBulletType
            damage: 42
          }
        }
        // trailing note
        """;

    private static String text(String source, JsonTextPatch.Span span) {
        return source.substring(span.start(), span.end());
    }

    @Test
    void findsMembersStaticStringsCommentsAndNestedValues() {
        JsonTextPatch.Span copper = JsonTextPatch.valueSpan(TURRET, "ammoTypes.copper");
        assertNotNull(copper);
        String copperText = text(TURRET, copper);
        assertTrue(copperText.startsWith("{"), copperText);
        assertTrue(copperText.contains("// fast but weak"), copperText);
        assertTrue(copperText.contains("damage: 10"), copperText);
        assertFalse(copperText.contains("lead"), "the sibling must not be swallowed: " + copperText);

        assertEquals("10", text(TURRET, JsonTextPatch.valueSpan(TURRET, "ammoTypes.copper.damage")));

        // A scalar member, and the trailing comment must not be taken as part of it.
        assertEquals("2", text(TURRET, JsonTextPatch.valueSpan(TURRET, "size")));
        assertEquals("ItemTurret", text(TURRET, JsonTextPatch.valueSpan(TURRET, "type")));
    }

    @Test
    void findsArrayElementsByIndexAndInsideABracedRoot() {
        String array = "ammoTypes: [\n  { type: BasicBulletType, damage: 1 }\n"
            + "  { type: BasicBulletType, damage: 2 }\n]\n";
        JsonTextPatch.Span second = JsonTextPatch.valueSpan(array, "ammoTypes[1]");
        assertNotNull(second);
        assertTrue(text(array, second).contains("damage: 2"));

        String braced = "{\n  type: ItemTurret\n  // gun\n  ammoTypes: {\n"
            + "    copper: { damage: 3 }\n  }\n}\n";
        JsonTextPatch.Span copper = JsonTextPatch.valueSpan(braced, "ammoTypes.copper");
        assertNotNull(copper);
        assertTrue(text(braced, copper).contains("damage: 3"));
    }

    @Test
    void replacingAValueKeepsEveryOtherByte() {
        JsonTextPatch.Span copper = JsonTextPatch.valueSpan(TURRET, "ammoTypes.copper");
        String replacement = "{\n    type: BasicBulletType\n    damage: 999\n  }";

        String patched = JsonTextPatch.replace(TURRET, "ammoTypes.copper", replacement);

        assertEquals(TURRET.substring(0, copper.start()) + replacement + TURRET.substring(copper.end()),
            patched, "the patch must be exactly the value replacement and nothing else");
        assertTrue(patched.contains("// the main gun"), patched);
        assertTrue(patched.contains("// trailing note"), patched);
        assertTrue(patched.contains("damage: 999"), patched);
    }

    @Test
    void indentsASplicedBlockToTheOriginalValuesLine() {
        String array = "ammoTypes: [\n  {\n    damage: 1\n  }\n  {\n    damage: 2\n  }\n]\n";
        String patched = JsonTextPatch.replace(array, "ammoTypes[1]",
            "{\n  damage: 77\n}");
        assertNotNull(patched);
        assertTrue(patched.contains("\n  {\n    damage: 77\n  }\n"), patched);
    }

    @Test
    void skipsStringsThatLookLikeStructure() {
        String tricky = "type: ItemTurret\ndescription: \"a: b, c {d} [e}\"\nmarker: 7\n";
        assertEquals("7", text(tricky, JsonTextPatch.valueSpan(tricky, "marker")));

        String raw = "type: ItemTurret\nnote: '''\n  damage: 1\n  \\}\\}\n'''\nmarker: 8\n";
        assertEquals("8", text(raw, JsonTextPatch.valueSpan(raw, "marker")));
    }

    @Test
    void aCommaInsideAnObjectValueIsPartOfTheString() {
        // The reader only ends a quoteless object value at a comma when the span is a literal, so a
        // description like this must stay one value and the member after it must still be found.
        String host = "description: The rampant, filled with danger\ndamage: 5\n";
        assertEquals("5", text(host, JsonTextPatch.valueSpan(host, "damage")));
    }

    @Test
    void aPathThatIsNotThereIsReportedRatherThanGuessed() {
        assertNull(JsonTextPatch.valueSpan(TURRET, "ammoTypes.titanium"));
        assertNull(JsonTextPatch.valueSpan(TURRET, "ammoTypes.copper.speed"));
        assertNull(JsonTextPatch.valueSpan(TURRET, "ammoTypes[4]"));
        assertNull(JsonTextPatch.replace(TURRET, "ammoTypes.titanium", "{}"));
    }
}

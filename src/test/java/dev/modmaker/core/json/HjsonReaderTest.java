package dev.modmaker.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tolerant JSON/HJSON dialect that real Mindustry mods are written in. */
class HjsonReaderTest {

    @Test
    void readsMembersWithoutCommas() {
        String text = """
            {
                "name": "CMB钢",
                "description": "一种外星材料"
                "color":"123456"
                "hardness": 4
            }
            """;
        JsonObject object = HjsonReader.readObject(text);
        assertEquals("CMB钢", object.get("name").getAsString());
        assertEquals("一种外星材料", object.get("description").getAsString());
        assertEquals("123456", object.get("color").getAsString());
        assertEquals(4, object.get("hardness").getAsInt());
    }

    @Test
    void readsCommentsOfEveryFlavour() {
        String text = """
            // leading line comment
            {
                # hash comment
                "name": "kiln", /* inline block
                                   comment spanning lines */
                "size": 2, // trailing comment
                "health": 320
            }
            """;
        JsonObject object = HjsonReader.readObject(text);
        assertEquals("kiln", object.get("name").getAsString());
        assertEquals(2, object.get("size").getAsInt());
        assertEquals(320, object.get("health").getAsInt());
    }

    @Test
    void aNestedArrayDoesNotStopCommaSeparationInTheOuterOne() {
        // After an inner array closes the parser is still lexically inside the outer one, where the
        // game's Jval keeps terminating bare values at commas. The inArray flag used to be cleared
        // instead of restored, so "a, b" merged into the single string "a, b".
        JsonArray array = HjsonReader.read("[[1], a, b]").getAsJsonArray();
        assertEquals(3, array.size());
        assertEquals(1, array.get(0).getAsJsonArray().get(0).getAsInt());
        assertEquals("a", array.get(1).getAsString());
        assertEquals("b", array.get(2).getAsString());
    }

    @Test
    void readsUnquotedKeysAndBareValues() {
        // Line-separated members, the way the game's HJSON parser requires for unquoted values:
        // on a single line the comma would become part of the string (arc Jval.readTfnns).
        JsonObject object = HjsonReader.readObject("""
            name: turret
            size: 3
            floating: 1.5
            ok: true
            nothing: null
            """);
        assertEquals("turret", object.get("name").getAsString());
        assertEquals(3, object.get("size").getAsInt());
        assertEquals(1.5, object.get("floating").getAsDouble());
        assertTrue(object.get("ok").getAsBoolean());
        assertTrue(object.get("nothing").isJsonNull());
    }

    @Test
    void bareStringValuesKeepTheirCommasLikeTheGame() {
        // In object context a comma only separates literals; a quoteless string swallows it.
        JsonObject object = HjsonReader.readObject("description: The rampant, filled with danger.\nrank: 1");
        assertEquals("The rampant, filled with danger.", object.get("description").getAsString());
        assertEquals(1, object.get("rank").getAsInt());

        JsonObject onOneLine = HjsonReader.readObject("{ a: 1, b: 2 }");
        assertEquals(1, onOneLine.get("a").getAsInt(), "literals still separate at a comma");
        assertEquals(2, onOneLine.get("b").getAsInt());
    }

    @Test
    void readsSingleQuotesAndTrailingCommas() {
        JsonObject object = HjsonReader.readObject("{ 'name': 'kiln', 'tags': ['a', 'b',], }");
        assertEquals("kiln", object.get("name").getAsString());
        JsonArray tags = object.getAsJsonArray("tags");
        assertEquals(2, tags.size());
        assertEquals("b", tags.get(1).getAsString());
    }

    @Test
    void stripsBomAndUnescapesHashWorkaround() {
        // The game rewrites bare '#' to '\#' in .json files before parsing (ContentParser.java:1026).
        String text = "\uFEFF{ \"color\": \"\\#c88a4a\" }";
        assertEquals("#c88a4a", HjsonReader.readObject(text).get("color").getAsString());
    }

    @Test
    void keepsDecimalPrecisionAsText() {
        JsonObject object = HjsonReader.readObject("{ \"cost\": 0.50, \"big\": 123456 }");
        assertEquals("0.50", object.get("cost").getAsString());
        assertEquals("123456", object.get("big").getAsString());
    }

    @Test
    void readsNestedStructures() {
        String text = """
            {
              "ammoTypes": {
                "copper": { "type": "BasicBulletType", "damage": 12 }
              },
              "shownPlanets": ["serpulo", "端点星"],
              "requirements": [{ "item": "copper", "amount": 30 }]
            }
            """;
        JsonObject object = HjsonReader.readObject(text);
        assertEquals(12, object.getAsJsonObject("ammoTypes").getAsJsonObject("copper").get("damage").getAsInt());
        assertEquals("端点星", object.getAsJsonArray("shownPlanets").get(1).getAsString());
        assertEquals(30, object.getAsJsonArray("requirements").get(0).getAsJsonObject().get("amount").getAsInt());
    }

    @Test
    void readsMultilineRawStrings() {
        JsonObject object = HjsonReader.readObject("{ \"desc\": '''\nline one\nline two\n''' }");
        assertTrue(object.get("desc").getAsString().contains("line one"));
        assertTrue(object.get("desc").getAsString().contains("line two"));
    }

    @Test
    void rejectsGenuinelyBrokenInput() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> HjsonReader.readObject("{ \"name\": }"));
        assertTrue(error.getMessage().contains("line"));
        assertFalse(error.getMessage().isBlank());
    }

    @Test
    void roundTripsThroughCanonicalJson() {
        JsonObject original = HjsonReader.readObject("{ name: \"kiln\", size: 2 }");
        JsonObject reparsed = Json.parseObject(Json.write(original));
        assertEquals("kiln", reparsed.get("name").getAsString());
        assertEquals(2, reparsed.get("size").getAsInt());
    }

    @Test
    void hashCommentsStartAnywhereInHjson() {
        // Exoprosopa-style files: value, then a hash comment on the next (or same) line.
        String text = """
            # leading comment
            color: 647CC2
            # Hardness of the item, better drill is required for higher hardness.
            hardness: 3 # trailing comment
            "#literal": value
            """;
        JsonObject object = HjsonReader.readObject(text);
        assertEquals("647CC2", object.get("color").getAsString());
        assertEquals(3, object.get("hardness").getAsInt());
        assertEquals("value", object.get("#literal").getAsString());
    }

    @Test
    void jsonFilesEscapeHashesExactlyLikeTheGame() {
        // ContentParser.parse (v160.5) replaces every '#' with "\#" before parsing .json files,
        // which readEscape resolves back to '#' inside quoted strings - so a quoted colour
        // survives. (An unquoted hash truncates the value in the game too; only quoted hashes
        // are meaningfully supported.)
        JsonObject object = HjsonReader.readJsonObject("{ \"color\": \"#8899aa\" }");
        assertEquals("#8899aa", object.get("color").getAsString());
    }

    @Test
    void multilineStringsStripTheOpeningIndentLikeArc() {
        // arc readMlString: consume the first line break, strip up to the opening delimiter's
        // indent from every continuation line, drop one trailing newline.
        String text = """
            meta:
              '''
              a
                b
              c
              '''
            other: 1
            """;
        JsonObject object = HjsonReader.readObject(text);
        assertEquals("a\n  b\nc", object.get("meta").getAsString());
        assertEquals(1, object.get("other").getAsInt());
    }

    @Test
    void literalsBeyondLongStayNumbers() {
        // arc's tryParseNumber still parses these (as doubles); BigDecimal keeps full precision.
        JsonObject object = HjsonReader.readObject("big: 99999999999999999999");
        assertTrue(object.get("big").getAsJsonPrimitive().isNumber(),
            "beyond-long literals must stay numbers, not strings");
        assertEquals("99999999999999999999", object.get("big").getAsString());
    }
}

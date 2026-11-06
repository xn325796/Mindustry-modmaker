package dev.modmaker.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * HJSON output must round-trip: parse(write(tree)) equals the tree, whatever it contains. The
 * fixture test pins this against every real content file, so a format conversion can never lose
 * data (comments and hand formatting are the accepted, unavoidable loss).
 */
class HjsonWriterTest {

    @BeforeAll
    static void checkSchemas() {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
    }

    @Test
    void writeNestedKeepsAnObjectsBracesWhileAWholeFileStaysBraceless() {
        // A whole file is a braceless root; a value spliced into "key: ..." must keep its braces or
        // the members would leak into the parent object. Both spellings are needed, so both are pinned.
        JsonObject bullet = HjsonReader.readObject("type: BasicBulletType\ndamage: 7\n");

        String nested = HjsonWriter.writeNested(bullet);
        assertTrue(nested.startsWith("{"), nested);
        assertTrue(nested.endsWith("}"), nested);
        assertTrue(nested.contains("damage: 7"), nested);

        String wholeFile = HjsonWriter.write(bullet);
        assertFalse(wholeFile.startsWith("{"), wholeFile);
        assertEquals(HjsonReader.readObject(wholeFile), bullet);
        assertEquals(HjsonReader.readObject(nested), bullet);
    }

    private static void assertRoundTrip(JsonElement tree) {
        String text = HjsonWriter.write(tree);
        JsonElement reparsed = HjsonReader.read(text);
        assertEquals(tree, reparsed, "round trip failed for:\n" + text);
    }

    @Test
    void roundTripsPlainValues() {
        JsonObject tree = new JsonObject();
        tree.addProperty("name", "kiln");
        tree.addProperty("size", 3);
        tree.addProperty("health", 400.5);
        tree.addProperty("flying", true);
        tree.add("hidden", com.google.gson.JsonNull.INSTANCE);
        assertRoundTrip(tree);
    }

    @Test
    void quotesStringsThatWouldMisparse() {
        JsonObject tree = new JsonObject();
        tree.addProperty("empty", "");
        tree.addProperty("numeric", "123");
        tree.addProperty("decimal", "0.50");
        tree.addProperty("negative", "-5");
        tree.addProperty("literal", "true");
        tree.addProperty("hash", "color #8899aa");
        tree.addProperty("lineComment", "no // comment");
        tree.addProperty("blockComment", "no /* comment */");
        tree.addProperty("comma", "ends with comma,");
        tree.addProperty("brace", "closes } early");
        tree.addProperty("bracket", "closes ] early");
        tree.addProperty("quoted", "say \"hi\"");
        tree.addProperty("padded", " padded ");
        tree.addProperty("中文", "中文值");
        tree.addProperty("colon", "a: b");
        assertRoundTrip(tree);
    }

    @Test
    void roundTripsNestedStructures() {
        JsonObject bullet = new JsonObject();
        bullet.addProperty("type", "BasicBulletType");
        bullet.addProperty("damage", 12);
        bullet.addProperty("sprite", "exoprosopa-impale-arrow");

        JsonObject tree = new JsonObject();
        tree.addProperty("type", "ItemTurret");
        tree.addProperty("range", 240);
        tree.add("ammoTypes", new JsonObject());
        tree.getAsJsonObject("ammoTypes").add("01a-01-item-cobalt", bullet);
        JsonArray shown = new JsonArray();
        shown.add(new JsonPrimitive("serpulo"));
        shown.add(new JsonPrimitive("端点星"));
        shown.add(new JsonPrimitive("has, comma"));
        tree.add("shownPlanets", shown);
        tree.add("emptyObject", new JsonObject());
        tree.add("emptyArray", new JsonArray());
        assertRoundTrip(tree);
    }

    @Test
    void roundTripsLargeArraysAndDeepNesting() {
        JsonArray requirements = new JsonArray();
        for (int i = 0; i < 30; i++) {
            JsonObject stack = new JsonObject();
            stack.addProperty("item", "item-" + i);
            stack.addProperty("amount", i * 7);
            requirements.add(stack);
        }
        JsonObject tree = new JsonObject();
        tree.add("requirements", requirements);
        assertRoundTrip(tree);
    }

    @Test
    void keysThatAreNotIdentifiersAreQuoted() {
        JsonObject tree = new JsonObject();
        tree.addProperty("05e-01-transport-pipe", 1);
        tree.addProperty("has space", 2);
        tree.addProperty("true", 3);
        assertRoundTrip(tree);
    }

    @Test
    void objectInsideArrayKeepsArrayCommaRules() {
        // The reader keeps the array flag on for objects nested in arrays: a quoteless string
        // member with a comma must be quoted, or the re-parse breaks the file outright.
        JsonObject inner = new JsonObject();
        inner.addProperty("a", "x,y");
        inner.addProperty("b", "1,2");
        JsonObject tree = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(inner);
        tree.add("arr", arr);
        assertRoundTrip(tree);

        // Nested-array quirk: after the inner array closes, later members parse in object
        // context again - writer and reader must agree on both sides of the transition.
        JsonObject deep = new JsonObject();
        deep.addProperty("a", "x,y");
        JsonArray list = new JsonArray();
        list.add(new JsonPrimitive("p,q"));
        deep.add("list", list);
        JsonObject tree2 = new JsonObject();
        JsonArray arr2 = new JsonArray();
        arr2.add(deep);
        arr2.add(new JsonPrimitive("tail, comma"));
        tree2.add("arr", arr2);
        assertRoundTrip(tree2);
    }

    @Test
    void commaNumberStringsInObjectContextAreQuoted() {
        // Regression: the comma guard used to apply only inside arrays, so an object member written
        // as `description: 1,000 credits` was read back as the number 1 plus a stray member. The
        // game's own parser (arc Jval) fails such a file outright with "Found whitespace in your key
        // name", so a saved mod.hjson/content file became unloadable.
        JsonObject tree = new JsonObject();
        tree.addProperty("description", "1,000 credits for the first unit");
        tree.addProperty("note", "3, 2, 1");
        tree.addProperty("always", "true, always");
        tree.addProperty("numberFirst", "12,345");
        tree.addProperty("ordinary", "The rampant, filled with danger");
        assertRoundTrip(tree);

        String text = HjsonWriter.write(tree);
        assertTrue(text.contains("\"1,000 credits for the first unit\""),
            "a comma string in object context must be quoted, was:\n" + text);
        assertTrue(text.contains("\"3, 2, 1\""), text);
        assertTrue(text.contains("\"true, always\""), text);
        assertTrue(text.contains("\"12,345\""), text);
        // A comma whose prefix is not a literal re-parses fine unquoted; quoting it is also correct,
        // so only the round trip is asserted for it.
        assertTrue(text.contains("The rampant, filled with danger"), text);
    }

    @Test
    void trailingWhitespaceStringsAreQuoted() {
        JsonObject tree = new JsonObject();
        tree.addProperty("boolLike", "true ");
        tree.addProperty("numberLike", "5 ");
        tree.addProperty("decimal", "-3.5 ");
        tree.addProperty("text", "abc ");
        assertRoundTrip(tree);
    }

    @Test
    void bigIntegerLiteralsStayNumbers() {
        JsonObject tree = new JsonObject();
        tree.addProperty("big", new java.math.BigDecimal("99999999999999999999"));
        assertRoundTrip(tree);
    }

    @Test
    void everyFixtureContentFileRoundTripsThroughHjson() throws IOException {
        Path modRoot = dev.modmaker.core.io.TestFixtures.realModRoot();
        assumeTrue(modRoot != null && Files.isDirectory(modRoot), "real mod fixture not present");
        Path content = modRoot.resolve("content");
        assumeTrue(Files.isDirectory(content), "real mod fixture not present");

        int total = 0;
        List<String> mismatches = new java.util.ArrayList<>();
        try (var walk = Files.walk(content)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".json") && !name.endsWith(".hjson")) {
                    continue;
                }
                total++;
                JsonObject original = name.endsWith(".json")
                    ? HjsonReader.readJsonObject(Files.readString(file))
                    : HjsonReader.readObject(Files.readString(file));
                String hjson = HjsonWriter.write(original);
                if (!original.equals(HjsonReader.read(hjson))) {
                    mismatches.add(modRoot.relativize(file).toString());
                }
            }
        }
        assertTrue(total > 100, "expected a substantial fixture, saw " + total);
        assertEquals(List.of(), mismatches, "hjson round trip must be lossless for real data");
    }
}

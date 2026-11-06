package dev.modmaker.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure-logic value formatters of the inspector: the text <-> JSON round trips behind the text,
 * number, stack and string-array editors. No JavaFX toolkit needed.
 */
class RecordInspectorLogicTest {

    @Test
    void plainRendersPrimitiveAndFormatsStructuredValues() {
        assertEquals("", RecordInspector.plain(JsonNull.INSTANCE));
        assertEquals("copper", RecordInspector.plain(new JsonPrimitive("copper")));
        JsonObject object = new JsonObject();
        object.addProperty("a", 1);
        assertEquals(1, dev.modmaker.core.json.Json.parse(RecordInspector.plain(object))
            .getAsJsonObject().get("a").getAsInt());
    }

    @Test
    void stringArrayTextJoinsWithCommaAndFallsBackToPlain() {
        JsonArray array = new JsonArray();
        array.add("copper");
        array.add("lead");
        assertEquals("copper, lead", RecordInspector.stringArrayText(array));
        assertEquals("copper", RecordInspector.stringArrayText(new JsonPrimitive("copper")));
    }

    @Test
    void arrayOfSplitsSkipsBlankAndReturnsNullWhenEmpty() {
        JsonArray array = (JsonArray) RecordInspector.arrayOf("copper, lead, , sand");
        assertEquals(3, array.size());
        assertEquals("copper", array.get(0).getAsString());
        assertEquals("lead", array.get(1).getAsString());
        assertEquals("sand", array.get(2).getAsString());
        assertNull(RecordInspector.arrayOf("  ,  "));
    }

    @Test
    void stackTextReadsItemLiquidAndDefaultsAmountToOne() {
        JsonObject item = new JsonObject();
        item.addProperty("item", "copper");
        item.addProperty("amount", 5);
        assertEquals("copper/5", RecordInspector.stackText(item));

        JsonObject liquid = new JsonObject();
        liquid.addProperty("liquid", "water");
        assertEquals("water/1", RecordInspector.stackText(liquid));

        assertEquals("bare", RecordInspector.stackText(new JsonPrimitive("bare")));
    }

    @Test
    void numberOfParsesIntDoubleAndFallsBackToTheRawText() {
        assertEquals(42, RecordInspector.numberOf("42").getAsLong());
        assertEquals(1.5, RecordInspector.numberOf("1.5").getAsDouble(), 1e-9);
        assertEquals("abc", RecordInspector.numberOf("abc").getAsString());
        assertNull(RecordInspector.numberOf("   "));
    }
}

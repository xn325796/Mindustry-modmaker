package dev.modmaker.core.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dotted paths with array indices. The importer turns nested objects (a bullet inside a turret's
 * {@code ammoTypes}, a weapon inside {@code weapons}) into their own nodes and writes edits back
 * through these paths, so {@code set} must resolve an index exactly like {@code get} does.
 */
class JsonPathTest {

    @Test
    void setThroughAnArrayIndexReachesTheElement() {
        // Regression: the index step used to assign the padded array itself to "current", so the
        // following member write landed on an array and returned silently - the edit was accepted
        // by the inspector and then never written back.
        JsonObject root = HjsonReader.readObject("{weapons:[{bullet:{damage:5}}]}");
        assertEquals("5", JsonPath.get(root, "weapons[0].bullet.damage").getAsString());

        JsonPath.set(root, "weapons[0].bullet.damage", new JsonPrimitive(99));
        assertEquals("99", JsonPath.get(root, "weapons[0].bullet.damage").getAsString(),
            "the edit must land in the element the path names");
    }

    @Test
    void setAndGetAgreeForEveryStepShape() {
        JsonObject root = HjsonReader.readObject("{a:[{b:[{c:1}]}]}");
        JsonPath.set(root, "a[0].b[0].c", new JsonPrimitive(2));
        assertEquals("2", JsonPath.get(root, "a[0].b[0].c").getAsString());

        JsonPath.set(root, "a[1].b[0].c", new JsonPrimitive(3));
        assertEquals("3", JsonPath.get(root, "a[1].b[0].c").getAsString());
        assertEquals("2", JsonPath.get(root, "a[0].b[0].c").getAsString(), "no aliasing between elements");
    }

    @Test
    void setCreatesTheIntermediateMembersItNeeds() {
        JsonObject root = new JsonObject();
        JsonPath.set(root, "ammoTypes.shell.damage", new JsonPrimitive(7));
        assertEquals("7", JsonPath.get(root, "ammoTypes.shell.damage").getAsString());

        JsonPath.set(root, "weapons[2].bullet.damage", new JsonPrimitive(8));
        assertEquals("8", JsonPath.get(root, "weapons[2].bullet.damage").getAsString());
        JsonArray weapons = root.getAsJsonArray("weapons");
        assertEquals(3, weapons.size(), "the missing slots are padded");
        assertTrue(weapons.get(0).isJsonObject(), "padding uses the shape the next step needs");
        assertTrue(weapons.get(2).getAsJsonObject().has("bullet"));
    }

    @Test
    void setReplacesASlotHoldingTheWrongShape() {
        JsonObject root = HjsonReader.readObject("{a:[1]}");
        JsonPath.set(root, "a[0].b", new JsonPrimitive(4));
        assertTrue(root.getAsJsonArray("a").get(0).isJsonObject(),
            "a scalar in the way is replaced, exactly like the member branch does");
        assertEquals("4", JsonPath.get(root, "a[0].b").getAsString());
    }

    @Test
    void removeThroughAnIndexStillWorks() {
        JsonObject root = HjsonReader.readObject("{a:[{b:1},{b:2}]}");
        assertTrue(JsonPath.remove(root, "a[0].b"));
        assertNull(root.getAsJsonArray("a").get(0).getAsJsonObject().get("b"));
        assertFalse(JsonPath.remove(root, "a[9].b"), "an out-of-range index removes nothing");
    }

    @Test
    void memberNamesWithDotsAndBracketsRoundTrip() {
        JsonObject root = HjsonReader.readObject("{}");
        String path = JsonPath.child("ammoTypes", "my.item[1]");
        JsonPath.set(root, path, HjsonReader.readObject("{damage:5}"));

        assertEquals("5", JsonPath.get(root, JsonPath.child(path, "damage")).getAsString(),
            "a dotted/bracketed key round-trips through its escaped path");
        JsonObject ammo = root.getAsJsonObject("ammoTypes");
        assertTrue(ammo.has("my.item[1]"), "the literal key is preserved, not split into structure");
        assertNull(ammo.get("my"), "no synthetic intermediate member may be created");
    }

    @Test
    void oversizedIndexDoesNotHangOrOverflow() {
        JsonObject root = new JsonObject();
        // A legacy/hostile path with an out-of-range index must not throw or pad a giant array.
        JsonPath.set(root, "a[999999999999999999999]", new JsonPrimitive(1));
        assertFalse(root.has("a") && root.get("a").isJsonArray(),
            "no giant array may be materialised from an unbounded index");
    }
}

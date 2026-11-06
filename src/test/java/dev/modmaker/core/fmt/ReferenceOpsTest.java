package dev.modmaker.core.fmt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Writing and undoing references - the model side of dragging a connection between cards. */
class ReferenceOpsTest {

    private static FieldDef field(FieldType type, String stackKind) {
        return new FieldDef("requirements", type, null, List.of(), "any", stackKind, null, true);
    }

    @Test
    void scalarReferenceIsWrittenAndReverted() {
        ContentRecord target = new ContentRecord();
        target.fields.put("requirements", new JsonPrimitive("old"));

        var revert = ReferenceOps.apply(target, field(FieldType.content, null), "steel-ingot");
        assertEquals("steel-ingot", target.fields.get("requirements").getAsString());

        ReferenceOps.revert(target, field(FieldType.content, null), revert);
        assertEquals("old", target.fields.get("requirements").getAsString());

        // A field that had no value before the connection is removed again on revert.
        ContentRecord fresh = new ContentRecord();
        var revertEmpty = ReferenceOps.apply(fresh, field(FieldType.content, null), "steel-ingot");
        ReferenceOps.revert(fresh, field(FieldType.content, null), revertEmpty);
        assertNull(fresh.fields.get("requirements"));
    }

    @Test
    void stackArrayGrowsByEntriesAndShrinksOnRevert() {
        ContentRecord target = new ContentRecord();

        var first = ReferenceOps.apply(target, field(FieldType.stackArray, "item"), "copper");
        var second = ReferenceOps.apply(target, field(FieldType.stackArray, "item"), "lead");
        JsonArray array = target.fields.get("requirements").getAsJsonArray();
        assertEquals(2, array.size());
        assertEquals("copper", array.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(1, array.get(0).getAsJsonObject().get("amount").getAsInt());
        assertEquals("lead", array.get(1).getAsJsonObject().get("item").getAsString());

        ReferenceOps.revert(target, field(FieldType.stackArray, "item"), second);
        assertEquals(1, target.fields.get("requirements").getAsJsonArray().size());
        ReferenceOps.revert(target, field(FieldType.stackArray, "item"), first);
        assertNull(target.fields.get("requirements"), "an emptied array is removed entirely");
    }

    @Test
    void singleStackReplacesAndRestoresAnyPreviousShape() {
        ContentRecord target = new ContentRecord();
        target.fields.put("requirements", new JsonPrimitive("copper/5"));

        ReferenceOps.apply(target, field(FieldType.stack, "item"), "lead");
        JsonObject entry = target.fields.get("requirements").getAsJsonObject();
        assertEquals("lead", entry.get("item").getAsString());

        // Best-effort removal matches by stack kind.
        assertTrue(ReferenceOps.remove(target, field(FieldType.stack, "item"), "lead"));
        assertNull(target.fields.get("requirements"));
    }

    @Test
    void removeMatchesByStackKindAndName() {
        ContentRecord target = new ContentRecord();
        ReferenceOps.apply(target, field(FieldType.stackArray, "item"), "copper");
        ReferenceOps.apply(target, field(FieldType.stackArray, "item"), "lead");

        assertFalse(ReferenceOps.remove(target, field(FieldType.stackArray, "item"), "not-there"));
        assertTrue(ReferenceOps.remove(target, field(FieldType.stackArray, "item"), "copper"));
        assertEquals(1, target.fields.get("requirements").getAsJsonArray().size());
    }

    @Test
    void setAmountUpgradesABareNameAndRestoresThePreviousShape() {
        ContentRecord target = new ContentRecord();
        target.fields.put("requirements", new JsonPrimitive("copper"));
        assertEquals(1, ReferenceOps.amountOf(target, field(FieldType.stack, "item"), "copper"));

        var revert = ReferenceOps.setAmount(target, field(FieldType.stack, "item"), "copper", 25);
        JsonObject entry = target.fields.get("requirements").getAsJsonObject();
        assertEquals("copper", entry.get("item").getAsString());
        assertEquals(25, entry.get("amount").getAsInt());
        assertEquals(25, ReferenceOps.amountOf(target, field(FieldType.stack, "item"), "copper"));

        ReferenceOps.revert(target, field(FieldType.stack, "item"), revert);
        assertEquals("copper", target.fields.get("requirements").getAsString());
    }

    @Test
    void setAmountTouchesOnlyTheNamedEntryInAnArray() {
        ContentRecord target = new ContentRecord();
        ReferenceOps.apply(target, field(FieldType.stackArray, "item"), "copper");
        ReferenceOps.apply(target, field(FieldType.stackArray, "item"), "lead");

        var revert = ReferenceOps.setAmount(target, field(FieldType.stackArray, "item"), "lead", 7);
        JsonArray array = target.fields.get("requirements").getAsJsonArray();
        assertEquals(1, array.get(0).getAsJsonObject().get("amount").getAsInt());
        assertEquals(7, array.get(1).getAsJsonObject().get("amount").getAsInt());

        ReferenceOps.revert(target, field(FieldType.stackArray, "item"), revert);
        assertEquals(1, target.fields.get("requirements").getAsJsonArray()
            .get(1).getAsJsonObject().get("amount").getAsInt());
    }

    @Test
    void setAmountOnAMissingEntryIsANoOp() {
        ContentRecord target = new ContentRecord();
        assertNull(ReferenceOps.setAmount(target, field(FieldType.stack, "item"), "copper", 3));
        assertNull(target.fields.get("requirements"));
        assertEquals(-1, ReferenceOps.amountOf(target, field(FieldType.stack, "item"), "copper"));
    }

    // --- struct shapes: structured multi-reference groups ---------------------------------------

    private static FieldDef structField(String name, String shape, String ctype) {
        return new FieldDef(name, FieldType.struct, null, List.of(), ctype, shape, null, true);
    }

    @Test
    void consumeGroupGrowsByWiredItemsAndLiquids() {
        ContentRecord target = new ContentRecord();
        var items = structField("consumes", "consume", "any");

        ReferenceOps.apply(target, items, "copper", "item");
        ReferenceOps.apply(target, items, "lead", "item");
        ReferenceOps.apply(target, items, "water", "liquid");
        var consumes = target.fields.get("consumes").getAsJsonArray();
        assertEquals(1, consumes.size(), "one group holds both the items and the liquid");
        var group = consumes.get(0).getAsJsonObject();
        assertEquals(2, group.getAsJsonArray("items").size());
        assertEquals("water", group.getAsJsonArray("liquids").get(0).getAsJsonObject().get("liquid").getAsString());

        // Amount editing finds stacks nested in the group.
        assertEquals(1, ReferenceOps.amountOf(target, items, "copper"));
        ReferenceOps.setAmount(target, items, "copper", 5);
        assertEquals(5, ReferenceOps.amountOf(target, items, "copper"));

        // A hand-written "copper/10" string is found and upgraded too (the bare value carries the
        // amount in its text, and the upgrade produces the explicit object form).
        target.fields.put("consumes", new JsonPrimitive("copper/10"));
        assertEquals(10, ReferenceOps.amountOf(target, items, "copper"));
        ReferenceOps.setAmount(target, items, "copper", 3);
        assertEquals(3, target.fields.get("consumes").getAsJsonObject().get("amount").getAsInt());

        // Removing the liquid empties its slot; removing everything dissolves the field.
        target.fields.remove("consumes");
        ReferenceOps.apply(target, items, "copper", "item");
        ReferenceOps.apply(target, items, "water", "liquid");
        assertTrue(ReferenceOps.remove(target, items, "water"));
        assertFalse(ReferenceOps.remove(target, items, "water"));
        assertTrue(ReferenceOps.remove(target, items, "copper"));
        assertNull(target.fields.get("consumes"), "an empty consume structure is removed entirely");
    }

    @Test
    void unitRequirementsWireFactoriesThenFillCosts() {
        ContentRecord target = new ContentRecord();
        var unitReq = structField("requirements", "unitReq", "block");

        // An item wired before any factory entry has no slot to land in and is refused.
        assertNull(ReferenceOps.apply(target, unitReq, "copper", "item"));

        ReferenceOps.apply(target, unitReq, "ground-factory", "block");
        var array = target.fields.get("requirements").getAsJsonArray();
        assertEquals(1, array.size());
        assertEquals("ground-factory", array.get(0).getAsJsonObject().get("block").getAsString());
        assertEquals(0, array.get(0).getAsJsonObject().getAsJsonArray("requirements").size());

        ReferenceOps.apply(target, unitReq, "copper", "item");
        assertEquals(1, array.get(0).getAsJsonObject().getAsJsonArray("requirements").size(),
            "the item lands in the first factory entry's costs");
        assertEquals("copper", array.get(0).getAsJsonObject().getAsJsonArray("requirements")
            .get(0).getAsJsonObject().get("item").getAsString());

        assertTrue(ReferenceOps.remove(target, unitReq, "ground-factory"));
        assertNull(target.fields.get("requirements"));
    }

    @Test
    void ammoMapKeysAreTheReferences() {
        ContentRecord target = new ContentRecord();
        var ammo = structField("ammoTypes", "ammoMap", "item");

        var revert = ReferenceOps.apply(target, ammo, "copper", "item");
        assertTrue(target.fields.get("ammoTypes").getAsJsonObject().has("copper"),
            "wiring an item adds its key");
        assertEquals(0, target.fields.get("ammoTypes").getAsJsonObject()
            .get("copper").getAsJsonObject().entrySet().size(),
            "an empty bullet object is valid - the game defaults type to BasicBulletType");

        ReferenceOps.revert(target, ammo, revert);
        assertNull(target.fields.get("ammoTypes"), "undo removes the created key");

        ReferenceOps.apply(target, ammo, "copper", "item");
        assertTrue(ReferenceOps.remove(target, ammo, "copper"));
        assertNull(target.fields.get("ammoTypes"), "an empty ammo map is removed entirely");
    }

    @Test
    void factoryPlansWireUnitsAndFillCosts() {
        ContentRecord target = new ContentRecord();
        var plans = structField("plans", "plan", "unit");

        assertNull(ReferenceOps.apply(target, plans, "copper", "item"));
        ReferenceOps.apply(target, plans, "dagger", "unit");
        ReferenceOps.apply(target, plans, "copper", "item");
        var entry = target.fields.get("plans").getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("dagger", entry.get("unit").getAsString());
        assertEquals("copper", entry.getAsJsonArray("requirements").get(0)
            .getAsJsonObject().get("item").getAsString());
    }
}

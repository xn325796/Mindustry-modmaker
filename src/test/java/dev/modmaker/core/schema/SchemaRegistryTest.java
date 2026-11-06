package dev.modmaker.core.schema;

import dev.modmaker.core.fmt.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SchemaRegistryTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void load() throws IOException {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")),
            "run `./gradlew schemaBootstrap` first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    @Test
    void hasBothCuratedAndDraftedClasses() {
        assertTrue(registry.isCurated("Item"));
        assertTrue(registry.isCurated("GenericCrafter"));
        assertNotNull(registry.schema("Block"));
        assertFalse(registry.classNames().size() < 100,
            "the source scan should draft well over a hundred classes, saw " + registry.classNames().size());
    }

    @Test
    void curatedUnitTemplatesDoNotInventFields() {
        // The game's unit templates (ClassMap.java:133-136) all extend UnitType and declare no field
        // of their own - each only assigns inherited fields in its constructor (TankUnitType,
        // ErekirUnitType and MissileUnitType are a handful of lines each). A curated field on one of
        // them is therefore fabricated: the entry used to carry TankUnitType.treadPullScale, which
        // appears nowhere in the 160.5 sources, and a MissileUnitType.despawnEffect, which is a
        // BulletType field.
        for (String className : List.of("UnitType", "ErekirUnitType", "TankUnitType",
            "MissileUnitType", "NeoplasmUnitType")) {
            List<String> inherited = registry.fieldsOf("UnitType").stream().map(FieldDef::name).toList();
            for (FieldDef field : registry.fieldsOf(className)) {
                assertTrue(inherited.contains(field.name()),
                    className + " must not declare '" + field.name() + "', which UnitType does not have");
            }
        }
    }

    @Test
    void curatedFieldsWinAndCarryTheirWidgetType() {
        FieldDef color = field(registry.fieldsOf("Item"), "color");
        assertTrue(color.curated());
        assertEquals(FieldType.color, color.type());

        FieldDef requirements = field(registry.fieldsOf("Block"), "requirements");
        assertEquals(FieldType.stackArray, requirements.type());
        assertEquals("item", requirements.stackKind());
        assertTrue(requirements.isReference());

        FieldDef visibility = field(registry.fieldsOf("Block"), "buildVisibility");
        assertEquals(FieldType.enumeration, visibility.type());
        assertTrue(visibility.enumValues().contains("sandboxOnly"));
    }

    @Test
    void inheritedFieldsComeFromTheWholeChain() {
        List<FieldDef> crafter = registry.fieldsOf("GenericCrafter");
        assertNotNull(field(crafter, "craftTime"), "own field");
        assertNotNull(field(crafter, "health"), "inherited from Block");
        assertNotNull(field(crafter, "requirements"), "inherited from Block");
        // Base fields are ordered before the class's own fields.
        assertTrue(indexOf(crafter, "health") < indexOf(crafter, "craftTime"));
    }

    @Test
    void curatedExtendsShortcutsDoNotCutTheScannedChain() {
        // The curated layer spells ItemTurret as extending Block directly, but the game reads the
        // whole Turret -> ReloadTurret -> BaseTurret chain (ContentParser resolves the Java class);
        // the form must offer those fields instead of falling back to raw JSON for them.
        List<FieldDef> turret = registry.fieldsOf("ItemTurret");
        assertNotNull(field(turret, "item"), "own field");
        assertNotNull(field(turret, "shootCone"), "inherited from Turret");
        assertNotNull(field(turret, "maxAmmo"), "inherited from Turret");
        assertNotNull(field(turret, "inaccuracy"), "inherited from Turret");
        assertNotNull(field(turret, "reload"), "inherited from ReloadTurret");
        assertNotNull(field(turret, "range"), "inherited from BaseTurret");
        assertNotNull(field(turret, "health"), "inherited from Block");
    }

    @Test
    void scannedEnumFieldsCarryTheirValues() {
        // Block.group is a BlockGroup enum in the sources; the JSON names a constant
        // ("walls"), so the form renders a combo box instead of raw JSON.
        FieldDef group = field(registry.fieldsOf("Block"), "group");
        assertEquals(FieldType.enumeration, group.type());
        assertTrue(group.enumValues().contains("walls"), group.enumValues().toString());
        assertTrue(group.enumValues().contains("transportation"), group.enumValues().toString());

        // Attribute is not an enum but registers instances through add("name") - same shape.
        FieldDef attribute = field(registry.fieldsOf("ThermalGenerator"), "attribute");
        assertEquals(FieldType.enumeration, attribute.type());
        assertTrue(attribute.enumValues().contains("sand"), attribute.enumValues().toString());
    }

    @Test
    void soundFieldsAreTextBecauseTheGameReadsAssetNames() {
        // ContentParser.java:310-319 resolves a Sound value from a plain string asset name.
        assertEquals(FieldType.text, field(registry.fieldsOf("Turret"), "shootSound").type());
        assertEquals(FieldType.text, field(registry.fieldsOf("UnitType"), "deathSound").type());
    }

    @Test
    void classesForATypeStartWithTheBaseClass() {
        List<String> blocks = registry.classesForType(ContentType.block);
        assertEquals("Block", blocks.get(0));
        assertTrue(blocks.contains("GenericCrafter"));
        assertTrue(blocks.contains("ItemTurret"));
        assertTrue(blocks.contains("Wall"));
        assertEquals("Item", registry.baseClassOf(ContentType.item));
        // baseClassOf reports the hierarchy root; that weather needs an explicit type to be valid
        // is expressed by resolveClass returning null (see below).
        assertEquals("Weather", registry.baseClassOf(ContentType.weather));
    }

    @Test
    void resolvesTheClassForARecord() {
        assertEquals("Block", registry.resolveClass(ContentType.block, null));
        assertEquals("GenericCrafter", registry.resolveClass(ContentType.block, "GenericCrafter"));
        assertEquals("BulletType", registry.resolveClass(ContentType.bullet, null));
        assertNull(registry.resolveClass(ContentType.weather, null));
    }

    @Test
    void knowsVanillaContentNames() {
        Set<String> items = registry.vanillaNames(ContentType.item);
        assertTrue(items.contains("copper"), "vanilla items should include copper, saw " + items.size());
        assertTrue(items.contains("lead"));
        assertTrue(registry.isVanillaName(ContentType.block, "copper-wall"));
        assertTrue(registry.vanillaNames(ContentType.block).size() > 100,
            "expected a large vanilla block list, saw " + registry.vanillaNames(ContentType.block).size());
        assertFalse(registry.isVanillaName(ContentType.block, "my-mod-not-real"));
    }

    @Test
    void adaptiveClassSwitchingStaysInTheSameBranch() {
        // A battery may become a power node or go back to the generic base, but never an item bridge.
        List<String> fromBattery = registry.relatedClasses(ContentType.block, "Battery");
        assertTrue(fromBattery.contains("Battery"));
        assertTrue(fromBattery.contains("Block"), "the base class must stay selectable");
        assertTrue(fromBattery.contains("PowerNode"), fromBattery.toString());
        assertFalse(fromBattery.contains("ItemBridge"), fromBattery.toString());
        assertFalse(fromBattery.contains("GenericCrafter"), fromBattery.toString());
        assertFalse(fromBattery.contains("Wall"), fromBattery.toString());

        // Turrets interoperate with turrets, not with drills.
        List<String> fromItemTurret = registry.relatedClasses(ContentType.block, "ItemTurret");
        assertTrue(fromItemTurret.contains("PowerTurret"), fromItemTurret.toString());
        assertTrue(fromItemTurret.contains("LiquidTurret"), fromItemTurret.toString());
        assertTrue(fromItemTurret.contains("Block"));
        assertFalse(fromItemTurret.contains("Drill"), fromItemTurret.toString());

        // From the base class there is nothing to narrow by: the full list is offered.
        assertEquals(registry.classesForType(ContentType.block),
            registry.relatedClasses(ContentType.block, "Block"));
    }

    private static FieldDef field(List<FieldDef> fields, String name) {
        for (FieldDef def : fields) {
            if (def.name().equals(name)) {
                return def;
            }
        }
        return null;
    }

    private static int indexOf(List<FieldDef> fields, String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}

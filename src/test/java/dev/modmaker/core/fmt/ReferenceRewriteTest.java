package dev.modmaker.core.fmt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference-graph rules on their own: which spellings mean the target, which belong to vanilla,
 * and what survives a rewrite untouched.
 */
class ReferenceRewriteTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.loadDefault();
    }

    private static ContentRecord record(ContentType type, String name) {
        return ContentRecord.create("c-" + type + "-" + name, type, name);
    }

    private static ModProject project() {
        ModProject project = new ModProject();
        project.meta.name = "test-mod";
        return project;
    }

    @Test
    void statusResearchAndPlanetBareNamesStayVanillaFirst() {
        ModProject project = project();
        ContentRecord wet = record(ContentType.status, "wet");
        ContentRecord serpulo = record(ContentType.planet, "serpulo");
        ContentRecord copper = record(ContentType.item, "copper");
        project.contents.addAll(List.of(wet, serpulo, copper));

        ContentRecord bareBullet = record(ContentType.bullet, "bare");
        bareBullet.fields.put("status", new JsonPrimitive("wet"));
        ContentRecord fullBullet = record(ContentType.bullet, "full");
        fullBullet.fields.put("status", new JsonPrimitive("test-mod-wet"));
        ContentRecord bareSector = record(ContentType.sector, "bare-sector");
        bareSector.fields.put("planet", new JsonPrimitive("serpulo"));
        ContentRecord fullSector = record(ContentType.sector, "full-sector");
        fullSector.fields.put("planet", new JsonPrimitive("test-mod-serpulo"));
        ContentRecord bareBlock = record(ContentType.block, "bare-block");
        bareBlock.fields.put("research", new JsonPrimitive("copper"));
        ContentRecord fullBlock = record(ContentType.block, "full-block");
        fullBlock.fields.put("research", new JsonPrimitive("test-mod-copper"));
        project.contents.addAll(List.of(bareBullet, fullBullet, bareSector, fullSector,
            bareBlock, fullBlock));

        assertTrue(ReferenceRewrite.rename(project, registry, wet, "wet", "soggy").changed());
        assertEquals("wet", bareBullet.fields.get("status").getAsString(),
            "a bare status name also naming vanilla keeps binding to vanilla");
        assertEquals("test-mod-soggy", fullBullet.fields.get("status").getAsString(),
            "the prefixed spelling is unambiguous and always follows the rename");

        assertTrue(ReferenceRewrite.rename(project, registry, serpulo, "serpulo", "tellus").changed());
        assertEquals("serpulo", bareSector.fields.get("planet").getAsString());
        assertEquals("test-mod-tellus", fullSector.fields.get("planet").getAsString());

        assertTrue(ReferenceRewrite.rename(project, registry, copper, "copper", "cu").changed());
        assertEquals("copper", bareBlock.fields.get("research").getAsString(),
            "a vanilla-first research parent must not be stolen from vanilla");
        assertEquals("test-mod-cu", fullBlock.fields.get("research").getAsString());
    }

    @Test
    void renameRewritesStacksObjectivesAndUnknownKeys() {
        ModProject project = project();
        ContentRecord alloy = record(ContentType.item, "alloy");
        ContentRecord block = record(ContentType.block, "kiln");
        project.contents.addAll(List.of(alloy, block));

        JsonArray requirements = new JsonArray();
        JsonObject stack = new JsonObject();
        stack.addProperty("item", "alloy");
        stack.addProperty("amount", 7);
        requirements.add(stack);
        requirements.add(new JsonPrimitive("test-mod-alloy/3"));
        block.fields.put("requirements", requirements);

        // Real shape: ObjectMap<Item, BulletType>, so the item is the member NAME.
        JsonObject ammo = new JsonObject();
        JsonObject ammoEntry = new JsonObject();
        ammoEntry.addProperty("type", "BasicBulletType");
        ammoEntry.addProperty("damage", 10);
        ammo.add("alloy", ammoEntry);
        block.fields.put("ammoTypes", ammo);

        JsonObject consumes = new JsonObject();
        JsonArray items = new JsonArray();
        items.add(new JsonPrimitive("alloy/5"));
        consumes.add("items", items);
        consumes.addProperty("power", 2);
        block.fields.put("consumes", consumes);

        JsonObject objective = new JsonObject();
        objective.addProperty("type", "Research");
        objective.addProperty("content", "alloy");
        objective.addProperty("note", "keep me");
        JsonArray objectives = new JsonArray();
        objectives.add(objective);
        JsonArray researchRequirements = new JsonArray();
        researchRequirements.add(new JsonPrimitive("alloy/10"));
        researchRequirements.add(new JsonPrimitive("test-mod-alloy/5"));
        JsonObject research = new JsonObject();
        research.addProperty("parent", "alloy");
        research.add("objectives", objectives);
        research.add("requirements", researchRequirements);
        research.addProperty("requiresUnlock", true);
        block.fields.put("research", research);
        project.meta.contentOrder.add("alloy");

        ReferenceRewrite.Result result = ReferenceRewrite.rename(project, registry, alloy,
            "alloy", "steel");
        assertTrue(result.changed());
        assertEquals(1, result.changes().size(), "one host changed");

        JsonArray rewritten = block.fields.get("requirements").getAsJsonArray();
        assertEquals("steel", rewritten.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(7, rewritten.get(0).getAsJsonObject().get("amount").getAsInt());
        assertEquals("test-mod-steel/3", rewritten.get(1).getAsString(),
            "the amount survives a qualified rewrite");

        JsonObject rewrittenAmmo = block.fields.get("ammoTypes").getAsJsonObject();
        assertTrue(rewrittenAmmo.has("steel"), "the ammo map KEY follows the item rename");
        assertFalse(rewrittenAmmo.has("alloy"));
        assertEquals("BasicBulletType", rewrittenAmmo.getAsJsonObject("steel")
            .get("type").getAsString(), "the non-content key is untouched");
        JsonObject rewrittenConsumes = block.fields.get("consumes").getAsJsonObject();
        assertEquals("steel/5", rewrittenConsumes.getAsJsonArray("items").get(0).getAsString());
        assertEquals(2, rewrittenConsumes.get("power").getAsInt());

        JsonObject rewrittenResearch = block.fields.get("research").getAsJsonObject();
        assertEquals("steel", rewrittenResearch.get("parent").getAsString());
        assertEquals("steel", rewrittenResearch.getAsJsonArray("objectives").get(0)
            .getAsJsonObject().get("content").getAsString());
        assertEquals("keep me", rewrittenResearch.getAsJsonArray("objectives").get(0)
            .getAsJsonObject().get("note").getAsString());
        assertTrue(rewrittenResearch.get("requiresUnlock").getAsBoolean());
        assertEquals("steel/10", rewrittenResearch.getAsJsonArray("requirements")
            .get(0).getAsString(), "research requirements follow the rename too");
        assertEquals("test-mod-steel/5", rewrittenResearch.getAsJsonArray("requirements")
            .get(1).getAsString());
        assertEquals(List.of("steel"), project.meta.contentOrder);
    }

    @Test
    void removeDropsEveryReferenceAndKeepsUnknownKeys() {
        ModProject project = project();
        ContentRecord alloy = record(ContentType.item, "alloy");
        ContentRecord other = record(ContentType.item, "other");
        ContentRecord wet = record(ContentType.status, "wet");
        ContentRecord block = record(ContentType.block, "kiln");
        ContentRecord bulletBare = record(ContentType.bullet, "bullet-bare");
        ContentRecord bullet = record(ContentType.bullet, "bullet");
        project.contents.addAll(List.of(alloy, other, wet, block, bulletBare, bullet));

        JsonArray requirements = new JsonArray();
        JsonObject stack = new JsonObject();
        stack.addProperty("item", "alloy");
        stack.addProperty("amount", 7);
        requirements.add(stack);
        requirements.add(new JsonPrimitive("test-mod-alloy/3"));
        JsonObject keep = new JsonObject();
        keep.addProperty("item", "other");
        keep.addProperty("amount", 1);
        requirements.add(keep);
        block.fields.put("requirements", requirements);

        JsonObject ammo = new JsonObject();
        JsonObject gone = new JsonObject();
        gone.addProperty("type", "BasicBulletType");
        ammo.add("alloy", gone);
        JsonObject kept = new JsonObject();
        kept.addProperty("type", "BasicBulletType");
        ammo.add("other", kept);
        block.fields.put("ammoTypes", ammo);

        JsonObject consumes = new JsonObject();
        JsonArray items = new JsonArray();
        items.add(new JsonPrimitive("alloy/5"));
        items.add(new JsonPrimitive("other/2"));
        consumes.add("items", items);
        consumes.addProperty("power", 2);
        block.fields.put("consumes", consumes);

        JsonArray researchRequirements = new JsonArray();
        researchRequirements.add(new JsonPrimitive("alloy/10"));
        researchRequirements.add(new JsonPrimitive("other/5"));
        JsonObject research = new JsonObject();
        research.addProperty("parent", "alloy");
        research.add("requirements", researchRequirements);
        JsonArray objectives = new JsonArray();
        JsonObject removing = new JsonObject();
        removing.addProperty("type", "Research");
        removing.addProperty("content", "alloy");
        objectives.add(removing);
        JsonObject keeping = new JsonObject();
        keeping.addProperty("type", "Research");
        keeping.addProperty("content", "other");
        objectives.add(keeping);
        research.add("objectives", objectives);
        research.addProperty("requiresUnlock", true);
        block.fields.put("research", research);
        project.meta.contentOrder.add("alloy");
        project.meta.contentOrder.add("other");

        // The bare name is vanilla-first and stays vanilla; only the qualified spelling belongs
        // to the mod's own status record.
        bulletBare.fields.put("status", new JsonPrimitive("wet"));
        bullet.fields.put("status", new JsonPrimitive("test-mod-wet"));

        ReferenceRewrite.remove(project, registry, List.of(alloy, wet));

        JsonArray rewritten = block.fields.get("requirements").getAsJsonArray();
        assertEquals(1, rewritten.size(), "only the unrelated stack stays");
        assertEquals("other", rewritten.get(0).getAsJsonObject().get("item").getAsString());
        JsonObject rewrittenAmmo = block.fields.get("ammoTypes").getAsJsonObject();
        assertEquals(1, rewrittenAmmo.size());
        assertTrue(rewrittenAmmo.has("other"));
        assertFalse(rewrittenAmmo.has("alloy"), "the deleted item's ammo entry is gone");
        JsonObject rewrittenConsumes = block.fields.get("consumes").getAsJsonObject();
        assertEquals(1, rewrittenConsumes.getAsJsonArray("items").size());
        assertEquals("other/2", rewrittenConsumes.getAsJsonArray("items").get(0).getAsString());
        assertEquals(2, rewrittenConsumes.get("power").getAsInt(), "unrelated keys survive");

        JsonObject rewrittenResearch = block.fields.get("research").getAsJsonObject();
        assertFalse(rewrittenResearch.has("parent"), "the parent reference is gone");
        assertEquals(1, rewrittenResearch.getAsJsonArray("objectives").size());
        assertEquals("other", rewrittenResearch.getAsJsonArray("objectives").get(0)
            .getAsJsonObject().get("content").getAsString());
        assertTrue(rewrittenResearch.get("requiresUnlock").getAsBoolean());
        assertEquals(1, rewrittenResearch.getAsJsonArray("requirements").size());
        assertEquals("other/5", rewrittenResearch.getAsJsonArray("requirements")
            .get(0).getAsString(), "research requirements are cleaned too");
        assertEquals(List.of("other"), project.meta.contentOrder);
        assertFalse(bullet.fields.containsKey("status"),
            "a single-valued reference field disappears with its target");
        assertEquals("wet", bulletBare.fields.get("status").getAsString(),
            "the bare vanilla-first spelling survives deleting the mod's same-named status");
    }

    @Test
    void anEmptiedResearchObjectIsRemovedEntirely() {
        ModProject project = project();
        ContentRecord alloy = record(ContentType.item, "alloy");
        ContentRecord block = record(ContentType.block, "kiln");
        project.contents.addAll(List.of(alloy, block));

        JsonObject research = new JsonObject();
        research.addProperty("parent", "alloy");
        block.fields.put("research", research);

        ReferenceRewrite.remove(project, registry, alloy);
        assertFalse(block.fields.containsKey("research"),
            "an empty research object would be a dangling tree node");
    }

    @Test
    void batchRemoveHandlesOneHostReferencingBothTargets() {
        ModProject project = project();
        ContentRecord first = record(ContentType.item, "first");
        ContentRecord second = record(ContentType.item, "second");
        ContentRecord block = record(ContentType.block, "kiln");
        project.contents.addAll(List.of(first, second, block));
        JsonArray requirements = new JsonArray();
        requirements.add(new JsonPrimitive("first/1"));
        requirements.add(new JsonPrimitive("second/2"));
        block.fields.put("requirements", requirements);

        ReferenceRewrite.Result result = ReferenceRewrite.remove(project, registry,
            List.of(first, second));
        assertEquals(1, result.changes().size(),
            "the host is reported once, not once per target");
        assertFalse(block.fields.containsKey("requirements"));
    }

    @Test
    void researchPlanetAndObjectiveSlotsFollowOnlyTheirContentType() {
        ModProject project = project();
        ContentRecord planet = record(ContentType.planet, "tellus");
        ContentRecord sector = record(ContentType.sector, "sector-a");
        ContentRecord block = record(ContentType.block, "kiln");
        project.contents.addAll(List.of(planet, sector, block));

        JsonObject objectivePlanet = new JsonObject();
        objectivePlanet.addProperty("type", "OnPlanet");
        objectivePlanet.addProperty("planet", "tellus");
        JsonObject objectiveSector = new JsonObject();
        objectiveSector.addProperty("type", "SectorComplete");
        objectiveSector.addProperty("preset", "sector-a");
        JsonArray objectives = new JsonArray();
        objectives.add(objectivePlanet);
        objectives.add(objectiveSector);
        JsonObject research = new JsonObject();
        research.addProperty("planet", "tellus");
        research.add("objectives", objectives);
        block.fields.put("research", research);

        ReferenceRewrite.rename(project, registry, planet, "tellus", "terra");
        JsonObject afterPlanet = block.fields.get("research").getAsJsonObject();
        assertEquals("terra", afterPlanet.get("planet").getAsString());
        assertEquals("terra", afterPlanet.getAsJsonArray("objectives").get(0)
            .getAsJsonObject().get("planet").getAsString());
        assertEquals("sector-a", afterPlanet.getAsJsonArray("objectives").get(1)
            .getAsJsonObject().get("preset").getAsString(),
            "a planet rename must not touch a sector preset slot");

        ReferenceRewrite.rename(project, registry, sector, "sector-a", "sector-b");
        assertEquals("sector-b", block.fields.get("research").getAsJsonObject()
            .getAsJsonArray("objectives").get(1).getAsJsonObject().get("preset").getAsString());
    }

    @Test
    void rawFieldsKeepTheirContentTypeWhenNamesCollide() {
        ModProject project = project();
        ContentRecord wetStatus = record(ContentType.status, "wet");
        ContentRecord wetItem = record(ContentType.item, "wet");
        ContentRecord block = record(ContentType.block, "kiln");
        JsonObject consumes = new JsonObject();
        JsonArray items = new JsonArray();
        items.add(new JsonPrimitive("wet/5"));
        consumes.add("items", items);
        block.fields.put("consumes", consumes);
        project.contents.addAll(List.of(wetStatus, wetItem, block));

        ReferenceRewrite.rename(project, registry, wetStatus, "wet", "soggy");
        assertEquals("wet/5", block.fields.get("consumes").getAsJsonObject()
            .getAsJsonArray("items").get(0).getAsString(),
            "renaming a status must not touch an item slot of the same name");

        ReferenceRewrite.remove(project, registry, wetStatus);
        assertEquals("wet/5", block.fields.get("consumes").getAsJsonObject()
            .getAsJsonArray("items").get(0).getAsString(),
            "deleting a status must not delete an item stack of the same name");
    }

    @Test
    void rawUnitFactoryRequirementsAreRewrittenToo() {
        ModProject project = project();
        ContentRecord alloy = record(ContentType.item, "alloy");
        ContentRecord ground = record(ContentType.block, "ground");
        ContentRecord factory = record(ContentType.unit, "factory-unit");
        project.contents.addAll(List.of(alloy, ground, factory));
        JsonObject requirements = new JsonObject();
        requirements.addProperty("block", "test-mod-ground");
        JsonArray stacks = new JsonArray();
        stacks.add(new JsonPrimitive("alloy/10"));
        stacks.add(new JsonPrimitive("test-mod-alloy/5"));
        requirements.add("requirements", stacks);
        factory.fields.put("requirements", requirements);

        ReferenceRewrite.rename(project, registry, alloy, "alloy", "steel");
        ReferenceRewrite.rename(project, registry, ground, "ground", "pad");

        JsonObject rewritten = factory.fields.get("requirements").getAsJsonObject();
        assertEquals("test-mod-pad", rewritten.get("block").getAsString());
        assertEquals("steel/10", rewritten.getAsJsonArray("requirements").get(0).getAsString());
        assertEquals("test-mod-steel/5",
            rewritten.getAsJsonArray("requirements").get(1).getAsString());
    }

    @Test
    void deletingAPatchKeepsReferencesToTheVanillaContentItOverrode() {
        ModProject project = project();
        ContentRecord patch = record(ContentType.item, "copper");
        patch.patch = true;
        ContentRecord host = record(ContentType.block, "kiln");
        JsonArray requirements = new JsonArray();
        requirements.add(new JsonPrimitive("copper/5"));
        host.fields.put("requirements", requirements);
        project.contents.addAll(List.of(patch, host));

        ReferenceRewrite.Result result = ReferenceRewrite.remove(project, registry, patch);
        assertFalse(result.changed(), "deleting a patch leaves vanilla references alone");
        assertEquals("copper/5", host.fields.get("requirements").getAsJsonArray()
            .get(0).getAsString());
    }

    @Test
    void patchRecordsAreNeverRenamed() {
        ModProject project = project();
        ContentRecord patch = record(ContentType.block, "router");
        patch.patch = true;
        project.contents.add(patch);

        ReferenceRewrite.Result result = ReferenceRewrite.rename(project, registry, patch,
            "router", "super-router");
        assertFalse(result.changed());
        assertEquals("router", patch.name);
    }

    // --- factory plans and reconstructor upgrades (previously un-remapped raw fields) -----------

    @Test
    void renamingAUnitRewritesFactoryPlans() {
        ModProject project = project();
        ContentRecord alloy = record(ContentType.item, "alloy");
        ContentRecord dagger = record(ContentType.unit, "dagger");
        ContentRecord factory = record(ContentType.block, "ground-factory");
        project.contents.addAll(List.of(alloy, dagger, factory));

        JsonObject plan = new JsonObject();
        plan.addProperty("unit", "dagger");
        JsonArray costs = new JsonArray();
        costs.add(new JsonPrimitive("alloy/10"));
        plan.add("requirements", costs);
        plan.addProperty("time", 480);
        JsonArray plans = new JsonArray();
        plans.add(plan);
        factory.fields.put("plans", plans);

        ReferenceRewrite.rename(project, registry, dagger, "dagger", "spear");
        ReferenceRewrite.rename(project, registry, alloy, "alloy", "steel");

        var rewritten = factory.fields.get("plans").getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("spear", rewritten.get("unit").getAsString(),
            "the plan's unit reference follows the rename");
        assertEquals("steel/10", rewritten.getAsJsonArray("requirements").get(0).getAsString(),
            "so do the plan's item costs");
    }

    @Test
    void deletingAUnitDropsFactoryPlansAndUpgradePairs() {
        ModProject project = project();
        ContentRecord dagger = record(ContentType.unit, "dagger");
        ContentRecord halo = record(ContentType.unit, "halo");
        ContentRecord factory = record(ContentType.block, "ground-factory");
        ContentRecord reconstructor = record(ContentType.block, "reconstructor");
        project.contents.addAll(List.of(dagger, halo, factory, reconstructor));

        JsonArray plans = new JsonArray();
        JsonObject plan = new JsonObject();
        plan.addProperty("unit", "dagger");
        plans.add(plan);
        JsonObject keptPlan = new JsonObject();
        keptPlan.addProperty("unit", "halo");
        plans.add(keptPlan);
        factory.fields.put("plans", plans);

        JsonArray upgrades = new JsonArray();
        JsonArray pair = new JsonArray();
        pair.add(new JsonPrimitive("dagger"));
        pair.add(new JsonPrimitive("halo"));
        upgrades.add(pair);
        JsonArray foreign = new JsonArray();
        foreign.add(new JsonPrimitive("not-a-unit/1"));
        upgrades.add(foreign);
        reconstructor.fields.put("upgrades", upgrades);

        ReferenceRewrite.remove(project, registry, dagger);

        var plansLeft = factory.fields.get("plans").getAsJsonArray();
        assertEquals(1, plansLeft.size(), "the deleted unit's plan is dropped");
        assertEquals("halo", plansLeft.get(0).getAsJsonObject().get("unit").getAsString());

        var upgradesLeft = reconstructor.fields.get("upgrades").getAsJsonArray();
        assertEquals(1, upgradesLeft.size(), "the pair naming the deleted unit is dropped");
        assertEquals("not-a-unit/1", upgradesLeft.get(0).getAsJsonArray().get(0).getAsString(),
            "a pair that names no known unit is left untouched");
    }
}

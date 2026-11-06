package dev.modmaker.core.validate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.AssetRef;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The gameplay-level pitfall checks (tutorial distillation): warnings the game tolerates but the
 * player suffers - bullet reach, drill tiers, environment sprite paths, crafter buffers, sound
 * files, planet generators/meshes and sector bounds - plus the two loader rules that make the game
 * refuse a file outright (a block above Vars.maxBlockSize and a team entry that names no vanilla
 * team) and the UTF-8 BOM parsing trap.
 */
class ValidatorPitfallsTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    private static ModProject project() {
        ModProject project = new ModProject();
        project.meta.name = "My Mod";
        return project;
    }

    private static ContentRecord record(ModProject project, ContentType type, String name,
        String className) {
        ContentRecord record = ContentRecord.create("id-" + name, type, name);
        if (className != null) {
            record.setClassName(className);
        }
        project.contents.add(record);
        return record;
    }

    private static List<String> messages(ModProject project, String contains) {
        return new Validator(registry).validate(project).stream()
            .map(Issue::message)
            .filter(message -> message.contains(contains))
            .toList();
    }

    private static List<Issue> errors(ModProject project) {
        return new Validator(registry).validate(project).stream()
            .filter(Issue::isError)
            .toList();
    }

    @Test
    void bomInVerbatimFilesIsAnError() {
        ModProject project = project();
        ContentRecord withBom = record(project, ContentType.item, "bombed", null);
        withBom.raw = "\uFEFF{\"type\": \"Item\"}";
        withBom.dirty = false;
        ContentRecord edited = record(project, ContentType.item, "edited", null);
        edited.raw = "\uFEFF{\"type\": \"Item\"}";
        edited.dirty = true;
        project.metaText = "\uFEFF{name: \"My Mod\"}";

        assertEquals(2, messages(project, "UTF-8 BOM").size(),
            "meta + the untouched content file, but not the edited one");
    }

    @Test
    void bulletShortOfTurretRangeIsFlagged() {
        ModProject project = project();
        ContentRecord turret = record(project, ContentType.block, "gun", "ItemTurret");
        turret.fields.put("range", new JsonPrimitive(240));
        ContentRecord shortBullet = ContentRecord.create("b1", ContentType.bullet, "short");
        shortBullet.inlineOwnerId = turret.id;
        shortBullet.fields.put("speed", new JsonPrimitive(3));
        shortBullet.fields.put("lifetime", new JsonPrimitive(50));
        project.contents.add(shortBullet);

        assertEquals(1, messages(project, "short of the turret's range 240").size(),
            "speed x lifetime = 150 cannot cover range 240");

        ContentRecord fitting = ContentRecord.create("b2", ContentType.bullet, "fitting");
        fitting.inlineOwnerId = turret.id;
        fitting.fields.put("speed", new JsonPrimitive(4));
        fitting.fields.put("lifetime", new JsonPrimitive(60));
        project.contents.add(fitting);
        assertEquals(1, messages(project, "short of the turret's range").size(),
            "the covering bullet adds no warning");
    }

    @Test
    void bulletFarPastTurretRangeIsFlagged() {
        ModProject project = project();
        ContentRecord turret = record(project, ContentType.block, "gun", "ItemTurret");
        turret.fields.put("range", new JsonPrimitive(80));
        ContentRecord bullet = ContentRecord.create("b1", ContentType.bullet, "wild");
        bullet.inlineOwnerId = turret.id;
        bullet.fields.put("speed", new JsonPrimitive(10));
        bullet.fields.put("lifetime", new JsonPrimitive(100));
        project.contents.add(bullet);

        assertEquals(1, messages(project, "over three times the turret's range").size());
    }

    @Test
    void drillTierMustCoverItemHardness() {
        ModProject project = project();
        ContentRecord ore = record(project, ContentType.item, "tough-ore", null);
        ore.fields.put("hardness", new JsonPrimitive(3));
        ContentRecord rare = record(project, ContentType.item, "impossible-ore", null);
        rare.fields.put("hardness", new JsonPrimitive(9));
        ContentRecord drill = record(project, ContentType.block, "slow-drill", "Drill");
        drill.fields.put("tier", new JsonPrimitive(2));

        assertEquals(1, messages(project, "cannot mine item 'tough-ore' (hardness 3)").size());
        assertEquals(1, messages(project, "cannot mine item 'impossible-ore' (hardness 9)").size());
        assertEquals(1, messages(project, "nothing in the game can mine it").size(),
            "hardness 9 tops every drill, vanilla included");

        drill.fields.put("tier", new JsonPrimitive(3));
        assertEquals(0, messages(project, "cannot mine item 'tough-ore'").size(),
            "tier 3 covers hardness 3");
    }

    @Test
    void drillWithoutTierMinesNothing() {
        ModProject project = project();
        record(project, ContentType.block, "tierless", "Drill");
        assertEquals(1, messages(project, "declares no tier").size());
    }

    @Test
    void environmentSpritesMustLiveInBlocksEnvironment() {
        ModProject project = project();
        record(project, ContentType.block, "frozen-floor", "Floor");
        // A project that ships sprites at all: with none, neither sprite check says anything,
        // because the mod simply is not using sprites yet.
        project.assets.add(new AssetRef("sprites/blocks/kiln.png", 1, "sprite"));

        assertEquals(1, messages(project, "sprites/blocks/environment/frozen-floor.png").size());

        project.assets.add(new AssetRef("sprites/blocks/environment/frozen-floor.png", 1, "sprite"));
        assertEquals(0, messages(project, "pink-black").size());

        // The same sprite outside the environment folder renders, but the map editor's terrain
        // palette would not list it (Mods.java files sprites under a page by their path).
        ModProject misplaced = project();
        record(misplaced, ContentType.block, "frozen-floor", "Floor");
        misplaced.assets.add(new AssetRef("sprites/blocks/frozen-floor.png", 1, "sprite"));
        assertEquals(0, messages(misplaced, "pink-black").size());
        assertEquals(1, messages(misplaced, "map editor's environment palette").size());
    }

    @Test
    void crafterOutputMistakesAreFlagged() {
        ModProject project = project();
        ContentRecord crafter = record(project, ContentType.block, "kiln", "GenericCrafter");
        crafter.fields.put("outputItem", new JsonPrimitive("silicon/1"));
        crafter.fields.put("outputItems", stacks("silicon/1"));
        crafter.fields.put("outputLiquid", new JsonPrimitive("slag/0.2"));

        assertEquals(1, messages(project, "silently ignores outputItem").size());
        assertEquals(1, messages(project, "ignoreLiquidFullness").size());

        crafter.fields.put("ignoreLiquidFullness", new JsonPrimitive(true));
        assertEquals(0, messages(project, "ignoreLiquidFullness").size());

        // The array spelling has to warn too: init copies the singular value into outputLiquids and
        // the stall decision reads only that array (GenericCrafter.java:125-127, 214-229).
        ModProject arrayed = project();
        ContentRecord mixer = record(arrayed, ContentType.block, "mixer", "GenericCrafter");
        mixer.fields.put("outputLiquids", stacks("water/0.1"));

        assertEquals(1, messages(arrayed, "ignoreLiquidFullness").size());

        mixer.fields.put("ignoreLiquidFullness", new JsonPrimitive(true));
        assertEquals(0, messages(arrayed, "ignoreLiquidFullness").size());
    }

    @Test
    void inputBatchLargerThanTheBufferStallsTheCrafter() {
        ModProject project = project();
        ContentRecord crafter = record(project, ContentType.block, "press", "GenericCrafter");
        JsonObject consumes = new JsonObject();
        JsonArray items = new JsonArray();
        items.add(new JsonPrimitive("copper/50"));
        items.add(new JsonPrimitive("lead/2"));
        consumes.add("items", items);
        crafter.fields.put("consumes", consumes);

        assertEquals(1, messages(project, "consumes a batch of 50 items but itemCapacity is 10").size());

        crafter.fields.put("itemCapacity", new JsonPrimitive(60));
        assertEquals(0, messages(project, "itemCapacity").size());
    }

    @Test
    void soundAssetsMustBePlayableFormatsAndReferencesMustDropTheExtension() {
        ModProject project = project();
        ContentRecord unit = record(project, ContentType.unit, "walker", "UnitType");
        JsonObject weapon = new JsonObject();
        weapon.addProperty("name", "gun");
        weapon.addProperty("shootSound", "shoot.ogg");
        JsonArray weapons = new JsonArray();
        weapons.add(weapon);
        unit.fields.put("weapons", weapons);
        project.assets.add(new AssetRef("sounds/shoot.mp3", 1, "sound"));

        assertEquals(0, messages(project, "only accepts").size(),
            "the game's loader takes mp3 as well as ogg (DataAssetType.sound)");

        project.assets.add(new AssetRef("sounds/hit.wav", 1, "sound"));
        assertEquals(1, messages(project, "only accepts").size());
        assertEquals(1, messages(project, "sound references omit the extension").size(),
            "the nested weapon's shootSound is checked too");
    }

    @Test
    void jsonPlanetsAreLimitedToVanillaGeneratorsAndJsonMeshes() {
        ModProject project = project();
        ContentRecord planet = record(project, ContentType.planet, "frost", "Planet");
        planet.fields.put("generator", new JsonPrimitive("MyGenerator"));
        JsonObject mesh = new JsonObject();
        mesh.addProperty("type", "HexMesh");
        planet.fields.put("mesh", mesh);

        // The game's PlanetGenerator parser ignores the JSON value completely and always builds an
        // AsteroidGenerator (ContentParser.java:270-276), so a name it does not ship is advice, not a
        // refusal - the planet still loads.
        assertEquals(1, messages(project, "always builds an AsteroidGenerator").size());
        assertEquals(1, messages(project, "cannot be built from JSON").size(),
            "HexMesh exists but the JSON parser throws on it");
        assertTrue(errors(project).isEmpty(), "an ignored generator is not something the game refuses");

        // AsteroidGenerator is the one value that describes what actually happens, so it is silent.
        planet.fields.put("generator", new JsonPrimitive("AsteroidGenerator"));
        mesh.addProperty("type", "NoiseMesh");
        assertEquals(0, messages(project, "always builds an AsteroidGenerator").size());
        assertEquals(0, messages(project, "cannot be built from JSON").size());

        // parseMesh capitalises the first letter, so a mod's "noiseMesh" is the NoiseMesh type.
        mesh.addProperty("type", "noiseMesh");
        assertEquals(0, messages(project, "not a mesh class the game ships").size());
        assertEquals(0, messages(project, "cannot be built from JSON").size());

        // "noisemesh" stays unknown: the game only raises the first letter.
        mesh.addProperty("type", "noisemesh");
        assertEquals(1, messages(project, "not a mesh class the game ships").size());
    }

    @Test
    void scriptModsGetTheSamePlanetWarningsAsJsonMods() {
        ModProject project = project();
        project.assets.add(new AssetRef("scripts/planets.js", 1, "script"));
        ContentRecord planet = record(project, ContentType.planet, "frost", "Planet");
        planet.fields.put("generator", new JsonPrimitive("FrostGenerator"));
        JsonObject mesh = new JsonObject();
        mesh.addProperty("type", "FrostMesh");
        planet.fields.put("mesh", mesh);

        assertEquals(1, messages(project, "always builds an AsteroidGenerator").size(),
            "a script cannot replace the game's PlanetGenerator parser either, so the name is ignored");
        assertEquals(1, messages(project, "if the mod's scripts define it").size(),
            "a mesh class the game does not know is a warning for a script mod, not a hard error");
        assertTrue(errors(project).isEmpty(), "neither case makes the game refuse the file");
    }

    @Test
    void sectorPresetsMustLandInsideThePlanetGrid() {
        ModProject project = project();
        ContentRecord planet = record(project, ContentType.planet, "frost", "Planet");
        planet.fields.put("sectorSize", new JsonPrimitive(3));
        ContentRecord outside = record(project, ContentType.sector, "far-away", "SectorPreset");
        outside.fields.put("planet", new JsonPrimitive("my-mod-frost"));
        outside.fields.put("sector", new JsonPrimitive(500));
        // The last addressable sector is the one degenerate slot the game appends on top of the
        // grid's 272 tiles (Planet.java:227-233 then ContentParser.java:856-857).
        ContentRecord lastLegal = record(project, ContentType.sector, "last-legal", "SectorPreset");
        lastLegal.fields.put("planet", new JsonPrimitive("my-mod-frost"));
        lastLegal.fields.put("sector", new JsonPrimitive(272));
        ContentRecord inside = record(project, ContentType.sector, "landing", "SectorPreset");
        inside.fields.put("planet", new JsonPrimitive("my-mod-frost"));
        inside.fields.put("sector", new JsonPrimitive(5));

        assertEquals(1, messages(project, "only has 273 sectors").size(),
            "sectorSize 3 gives 10 x 27 + 2 grid tiles plus the appended degenerate sector");
        assertEquals(0, messages(project, "targets sector 272,").size(),
            "the appended sector is addressable, so the tile count alone would be off by one");
        assertEquals(0, messages(project, "targets sector 5,").size(),
            "the landing preset is inside the grid (and 'sector 5,' does not match 'sector 500')");
    }

    @Test
    void presetOnAPlanetWithoutSectorsCannotResolve() {
        ModProject project = project();
        record(project, ContentType.planet, "bare", "Planet");
        ContentRecord preset = record(project, ContentType.sector, "landing", "SectorPreset");
        preset.fields.put("planet", new JsonPrimitive("my-mod-bare"));
        preset.fields.put("sector", new JsonPrimitive(0));

        assertEquals(1, messages(project, "creates no sectors").size());
    }

    @Test
    void bareReferenceToOwnModContentIsCorrectNotAProblem() {
        // For a concrete Content field the game tries the current mod's prefix first and the bare name
        // second (ContentParser.java:507-516), so the bare name - the idiomatic form mods ship - must
        // not be reported. Only the vanilla-first lookups can silently pick the vanilla entry;
        // ValidatorPrecisionTest locks both sides of that.
        ModProject project = project();
        ContentRecord item = record(project, ContentType.item, "steel-ingot", null);
        item.fields.put("hardness", new JsonPrimitive(2));
        ContentRecord crafter = record(project, ContentType.block, "kiln", "GenericCrafter");
        crafter.fields.put("requirements", stacks("steel-ingot/10"));

        List<String> referenceIssues = new Validator(registry).validate(project).stream()
            .map(Issue::message)
            .filter(message -> message.contains("references"))
            .toList();
        assertTrue(referenceIssues.isEmpty(), referenceIssues.toString());
    }

    @Test
    void blocksAboveTheGameMaximumAreAnError() {
        ModProject project = project();
        ContentRecord huge = record(project, ContentType.block, "leviathan", "GenericCrafter");
        huge.fields.put("size", new JsonPrimitive(17));

        List<Issue> found = new Validator(registry).validate(project);
        assertTrue(found.stream().anyMatch(issue -> issue.isError()
            && issue.message().contains("above the game's maximum of 16")), found.toString());

        // The cap is inclusive: the parser only throws above Vars.maxBlockSize
        // (ContentParser.java:604-605).
        huge.fields.put("size", new JsonPrimitive(16));
        assertTrue(errors(project).isEmpty(), "size 16 is the largest legal block");

        // Block.size is an int (Block.java:218), so the value is truncated before the comparison and
        // 16.9 is a size-16 block, not a refusal.
        huge.fields.put("size", new JsonPrimitive(16.9));
        assertTrue(errors(project).isEmpty(), "the game's int field truncates the value");
    }

    @Test
    void teamEntriesMustNameAVanillaTeam() {
        ModProject project = project();
        record(project, ContentType.team, "no-team-key", "TeamEntry");
        ContentRecord unknownName = record(project, ContentType.team, "made-up", "TeamEntry");
        unknownName.fields.put("team", new JsonPrimitive("neoplastic"));
        ContentRecord unknownId = record(project, ContentType.team, "way-out", "TeamEntry");
        unknownId.fields.put("team", new JsonPrimitive(256));
        ContentRecord badShape = record(project, ContentType.team, "bad-shape", "TeamEntry");
        badShape.fields.put("team", new JsonObject());

        assertEquals(1, messages(project, "has no \"team\" key").size(),
            "the parser throws \"Team field missing.\" (ContentParser.java:866-870)");
        assertEquals(1, messages(project, "not a team the game can resolve by name").size());
        assertEquals(1, messages(project, "only knows ids 0 to 255").size());
        assertEquals(1, messages(project, "neither a team name nor a number").size());
        assertEquals(4, errors(project).size(), errors(project).toString());

        assertTrue(registry.isVanillaName(ContentType.team, "neoplastic"),
            "the registry knows the team, but the string form does not resolve: only the six ids below"
                + " 6 enter Team.baseTeams (Team.java:30, 67)");

        // Both accepted spellings: a base team name and any id Team.all holds (Team.java:28).
        ModProject good = project();
        ContentRecord named = record(good, ContentType.team, "team-copy", "TeamEntry");
        named.fields.put("team", new JsonPrimitive("malis"));
        ContentRecord numbered = record(good, ContentType.team, "team-six", "TeamEntry");
        numbered.fields.put("team", new JsonPrimitive(6));
        assertTrue(errors(good).isEmpty(), errors(good).toString());
    }

    @Test
    void meshAndCloudMeshMustBeAnObjectOrAnArray() {
        ModProject project = project();
        ContentRecord planet = record(project, ContentType.planet, "frost", "Planet");
        planet.fields.put("mesh", new JsonPrimitive("NoiseMesh"));

        assertTrue(errors(project).stream()
                .anyMatch(issue -> issue.message().contains("Meshes must be objects")),
            "a string mesh makes the planet parser throw (ContentParser.java:813-815)");

        // The bare array is the MultiMesh shorthand and must stay legal, as does the object form.
        JsonArray layers = new JsonArray();
        JsonObject layer = new JsonObject();
        layer.addProperty("type", "NoiseMesh");
        layers.add(layer);
        planet.fields.put("mesh", layers);
        assertTrue(errors(project).isEmpty(), "an array mesh parses as a MultiMesh");

        JsonObject object = new JsonObject();
        object.addProperty("type", "NoiseMesh");
        planet.fields.put("mesh", object);
        planet.fields.put("cloudMesh", new JsonPrimitive(3));
        assertTrue(errors(project).stream()
                .anyMatch(issue -> issue.message().contains("cloudMesh")),
            "cloudMesh is checked the same way (ContentParser.java:828-830)");
    }

    @Test
    void environmentSpriteFolderUsesTheGamesSubstringTest() {
        // Mods.java:445-452 files a sprite under the environment page with
        // contains("sprites/blocks/environment") - no trailing slash - or the sprites-override form.
        ModProject project = project();
        record(project, ContentType.block, "frozen-floor", "Floor");
        project.assets.add(new AssetRef("sprites/blocks/environmental/frozen-floor.png", 1, "sprite"));
        assertEquals(0, messages(project, "map editor's environment palette").size(),
            "'environmental' still matches the game's substring test");

        ModProject overridden = project();
        record(overridden, ContentType.block, "frozen-floor", "Floor");
        // sprites-override sprites are never mod-prefixed (Mods.packSprites), so the file has to
        // carry the prefixed region name the record expects.
        overridden.assets.add(
            new AssetRef("sprites-override/blocks/environment/my-mod-frozen-floor.png", 1, "sprite"));
        assertEquals(0, messages(overridden, "map editor's environment palette").size(),
            "the sprites-override tree is packed and paged the same way (Mods.java:168-174)");

        ModProject elsewhere = project();
        record(elsewhere, ContentType.block, "frozen-floor", "Floor");
        elsewhere.assets.add(new AssetRef("sprites/blocks/frozen-floor.png", 1, "sprite"));
        assertEquals(1, messages(elsewhere, "map editor's environment palette").size(),
            "a sprite outside the environment folder is still reported");
    }

    private static JsonArray stacks(String... stacks) {
        JsonArray array = new JsonArray();
        for (String stack : stacks) {
            array.add(new JsonPrimitive(stack));
        }
        return array;
    }
}

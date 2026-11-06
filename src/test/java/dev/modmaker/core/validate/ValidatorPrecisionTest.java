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
 * Precision of the validator on the three rules where the game's own conventions are easy to
 * misread - each of these used to fire hundreds of times on a healthy released mod, which is how a
 * warning list stops being read at all:
 *
 * <ul>
 *   <li>a content reference written as a bare name is correct for the mod's own content (the game
 *       prefixes it while resolving a concrete {@code Content} field); only the few fields the game
 *       looks up vanilla-first can silently bind to the vanilla entry;</li>
 *   <li>sprites are named after the file, and the game ships variants ({@code basalt1.png}) and
 *       parts ({@code duct-bottom-0.png}) instead of a bare sprite;</li>
 *   <li>a declared class is capitalised before the game looks it up ({@code beamDrill} = BeamDrill).</li>
 * </ul>
 *
 * <p>Every case also has a counter-example that must still be reported, so a fixed rule cannot be
 * fixed by simply disabling it.
 */
class ValidatorPrecisionTest {

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

    private static ContentRecord record(ModProject project, ContentType type, String name) {
        return record(project, type, name, null);
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

    private static JsonArray stacks(String... stacks) {
        JsonArray array = new JsonArray();
        for (String stack : stacks) {
            array.add(new JsonPrimitive(stack));
        }
        return array;
    }

    private static List<String> messages(ModProject project, String contains) {
        return new Validator(registry).validate(project).stream()
            .map(Issue::message)
            .filter(message -> message.contains(contains))
            .toList();
    }

    private static List<Issue> issues(ModProject project) {
        return new Validator(registry).validate(project);
    }

    // --- duplicate names -------------------------------------------------------------------------

    @Test
    void duplicateNamesInvolvingAPatchStayTolerated() {
        // The game only throws when a *new* content object registers twice; patches reuse the
        // located content (ContentParser.java:583-588), so two files patching the same vanilla
        // block are legal and used to be reported as a hard error.
        ModProject project = project();
        ContentRecord first = record(project, ContentType.block, "duo");
        first.patch = true;
        ContentRecord second = record(project, ContentType.block, "duo");
        second.patch = true;

        assertTrue(issues(project).stream().noneMatch(Issue::isError),
            "two patches of the same vanilla block must not be an error");
        assertEquals(1, messages(project, "the game applies both patches in sequence").size(),
            "a warning keeps the author aware of the doubled file");
    }

    @Test
    void twoNewRecordsWithTheSameNameAreStillAnError() {
        ModProject project = project();
        record(project, ContentType.block, "duo");
        record(project, ContentType.block, "duo");
        assertEquals(1, messages(project, "the game throws on duplicate content names").size());
    }

    @Test
    void namesDifferingOnlyInCaseAreAnError() {
        // Distinct content in the game's case-sensitive maps, but Windows/macOS cannot store both
        // as files: the project folder itself would lose one record on the next save.
        ModProject project = project();
        record(project, ContentType.block, "duo");
        record(project, ContentType.block, "Duo");
        assertTrue(issues(project).stream().anyMatch(issue -> issue.isError()
                && issue.message().contains("differ only in case")),
            "the collision must be an error, not a warning");
    }

    @Test
    void anUnresolvedConcreteReferenceIsAnErrorButResearchStaysAWarning() {
        // Any concrete Content field throws "No X found with name ..." (ContentParser.java:505-514)
        // and fails the whole file; only "research" is lenient in game (ContentParser.java:1401).
        ModProject project = project();
        ContentRecord sector = record(project, ContentType.sector, "sector-1");
        sector.fields.put("planet", new JsonPrimitive("no-such-planet"));
        assertTrue(issues(project).stream().anyMatch(issue -> issue.isError()
                && issue.message().contains("neither content of this mod")),
            "an unresolved planet reference fails the file in game");

        ModProject lenient = project();
        ContentRecord kiln = record(lenient, ContentType.block, "kiln");
        kiln.fields.put("research", new JsonPrimitive("no-such-block"));
        assertTrue(issues(lenient).stream().filter(issue ->
                issue.message().contains("neither content of this mod")).noneMatch(Issue::isError),
            "research only logs in game, so it stays a warning");
    }

    @Test
    void aLowercaseUnitTemplateIsCanonicalisedLikeTheGameDoes() {
        // The game capitalises the first letter before its class lookup (ContentParser.java:1438);
        // validateClass already did this for "type", the template path must not lag behind.
        ModProject project = project();
        ContentRecord unit = record(project, ContentType.unit, "walker");
        unit.fields.put("template", new JsonPrimitive("tankUnitType"));
        assertEquals(0, messages(project, "is not a class the game ships").size());

        unit.fields.put("template", new JsonPrimitive("tankUnitsTypo"));
        assertEquals(1, messages(project, "is not a class the game ships").size());
    }

    @Test
    void aUnitTypeObjectOrArrayIsReportedInsteadOfSilentlyAccepted() {
        // The game throws "Types must be strings" (ContentParser.java:627-628); className() only
        // reads primitives, so an object/array type used to slip through without any issue.
        ModProject project = project();
        ContentRecord unit = record(project, ContentType.unit, "walker");
        unit.fields.put("type", new JsonObject());
        assertTrue(issues(project).stream().anyMatch(issue -> issue.isError()
            && issue.message().contains("Types must be strings")));
    }

    // --- references ------------------------------------------------------------------------------

    @Test
    void bareNamesPointAtTheModsOwnContentWithoutAnyWarning() {
        ModProject project = project();
        record(project, ContentType.item, "cobalt");
        record(project, ContentType.block, "core-block");
        ContentRecord kiln = record(project, ContentType.block, "kiln");
        kiln.fields.put("research", new JsonPrimitive("core-block"));
        kiln.fields.put("requirements", stacks("cobalt/10", "copper/5"));

        assertEquals(0, messages(project, "binds to the vanilla entry").size());
        assertEquals(0, messages(project, "neither content of this mod").size(),
            "copper is vanilla content, cobalt is the mod's");
    }

    @Test
    void aBareNameVanillaAlsoUsesStillFindsTheModsOwnEntryInAConcreteContentField() {
        // For a field whose type is a concrete Content subclass the game tries <mod>-<name> before the
        // bare name (ContentParser.java:507-516), so "copper/5" really does reach the mod's own item
        // named copper; there is nothing to warn about. The prefixed spelling keeps working too.
        ModProject project = project();
        record(project, ContentType.item, "copper");
        ContentRecord kiln = record(project, ContentType.block, "kiln");
        kiln.fields.put("requirements", stacks("copper/5"));

        assertEquals(0, messages(project, "binds to the vanilla entry").size());
        assertEquals(0, messages(project, "neither content of this mod").size());

        kiln.fields.put("requirements", stacks("my-mod-copper/5"));
        assertEquals(0, messages(project, "binds to the vanilla entry").size());
        assertEquals(0, messages(project, "neither content of this mod").size());
    }

    @Test
    void aBareNameVanillaAlsoUsesBindsToVanillaInTheVanillaFirstFields() {
        // The research parent is not a field of the class: readFields lifts it out of the JSON
        // (ContentParser.java:1270) and matches it with a find over TechTree.all
        // (ContentParser.java:1401), which is in content-creation order (TechTree.java:138) with the
        // vanilla trees built first (ContentLoader.java:72-79). Writing <mod>-<name> is what reaches
        // the mod's own node.
        ModProject project = project();
        record(project, ContentType.block, "router");
        ContentRecord wall = record(project, ContentType.block, "wall");
        wall.fields.put("research", new JsonPrimitive("router"));

        assertEquals(1, messages(project, "binds to the vanilla entry").size());
        assertEquals(1, messages(project, "write 'my-mod-router'").size());

        wall.fields.put("research", new JsonPrimitive("my-mod-router"));
        assertEquals(0, messages(project, "binds to the vanilla entry").size());

        // A status-typed field takes the StatusEffect parser, which is a locate() call: vanilla
        // first, mod-prefixed second (ContentParser.java:144-148).
        ModProject statused = project();
        record(statused, ContentType.status, "burning");
        ContentRecord liquid = record(statused, ContentType.liquid, "coolant");
        liquid.fields.put("effect", new JsonPrimitive("burning"));
        assertEquals(1, messages(statused, "binds to the vanilla entry").size());

        // SectorPreset.planet is removed from the JSON and located the same way
        // (ContentParser.java:765, 784).
        ModProject located = project();
        ContentRecord planet = record(located, ContentType.planet, "serpulo");
        planet.fields.put("sectorSize", new JsonPrimitive(1));
        ContentRecord preset = record(located, ContentType.sector, "landing");
        preset.fields.put("planet", new JsonPrimitive("serpulo"));
        assertEquals(1, messages(located, "binds to the vanilla entry").size());

        preset.fields.put("planet", new JsonPrimitive("my-mod-serpulo"));
        assertEquals(0, messages(located, "binds to the vanilla entry").size());
    }

    @Test
    void aPatchKeepsTheVanillaNameSoReferencingItIsFine() {
        ModProject project = project();
        ContentRecord patch = record(project, ContentType.block, "router");
        patch.patch = true;
        ContentRecord wall = record(project, ContentType.block, "wall");
        wall.fields.put("research", new JsonPrimitive("router"));

        assertEquals(0, messages(project, "binds to the vanilla entry").size());
    }

    @Test
    void aReferenceToNothingStillWarns() {
        ModProject project = project();
        ContentRecord wall = record(project, ContentType.block, "wall");
        wall.fields.put("research", new JsonPrimitive("no-such-block-anywhere"));

        assertEquals(1, messages(project, "neither content of this mod").size());
    }

    // --- class names -----------------------------------------------------------------------------

    @Test
    void aLowerCaseFirstLetterInTheClassIsCanonicalisedLikeTheGameDoes() {
        ModProject project = project();
        record(project, ContentType.block, "ore-extractor", "beamDrill");

        assertEquals(0, messages(project, "does not know").size());
        assertEquals("BeamDrill", registry.resolveClass(ContentType.block, "beamDrill"));
        assertEquals("BeamDrill", SchemaRegistry.canonicalClassName(
            "mindustry.world.blocks.production.BeamDrill"), "a package prefix is dropped");
        assertEquals("GenericCrafter", SchemaRegistry.canonicalClassName("GenericCrafter"),
            "an already canonical name is left alone");
    }

    @Test
    void aClassTheGameDoesNotHaveIsStillReported() {
        ModProject project = project();
        record(project, ContentType.block, "mystery", "totallyMadeUpThing");

        assertEquals(1, messages(project, "does not know").size());
    }

    // --- unit movement kinds ---------------------------------------------------------------------

    @Test
    void aUnitsTypeIsAMovementKindNotAClass() {
        ModProject project = project();
        record(project, ContentType.unit, "hover-drone", "hover");
        record(project, ContentType.unit, "naval-drone", "naval");

        assertEquals(0, messages(project, "does not know").size());
        assertEquals(0, messages(project, "which the game rejects").size());
        assertEquals("UnitType", registry.resolveClass(ContentType.unit, "hover"),
            "fields still come from UnitType, so the inspector keeps structured editing");
    }

    @Test
    void aMovementKindTheGameRejectsIsAHardError() {
        ModProject project = project();
        record(project, ContentType.unit, "wheeled-drone", "wheels");

        assertEquals(1, messages(project, "which the game rejects").size());
        assertEquals(0, messages(project, "does not know").size());
    }

    @Test
    void aUnitWithoutATypeIsFine() {
        ModProject project = project();
        record(project, ContentType.unit, "plain-drone");

        assertTrue(new Validator(registry).validate(project).stream().noneMatch(Issue::isError),
            "the game defaults to the plain flying entity");
    }

    @Test
    void anUnknownUnitTemplateIsWarnedAboutBecauseTheGameFallsBackSilently() {
        // ContentParser.java:622 resolves "template"; an unknown name only logs
        // "No type ... found, defaulting to UnitType" (ContentParser.java:1454), so the template's
        // constructor defaults are lost without any failure.
        ModProject project = project();
        ContentRecord unit = record(project, ContentType.unit, "tank", "tank");
        unit.fields.put("template", new JsonPrimitive("TankUnitType2"));

        assertEquals(1, messages(project, "template 'TankUnitType2'").size());
        assertTrue(new Validator(registry).validate(project).stream().noneMatch(Issue::isError),
            "the game tolerates it, so this is a warning, not an error");
    }

    @Test
    void theTemplatesTheGameShipsAreNotWarnedAbout() {
        ModProject project = project();
        for (String template : List.of("UnitType", "ErekirUnitType", "TankUnitType", "MissileUnitType",
            "NeoplasmUnitType")) {
            ContentRecord unit = record(project, ContentType.unit, "u-" + template, "tank");
            unit.fields.put("template", new JsonPrimitive(template));
        }

        assertEquals(0, messages(project, "is not a class the game ships").size(),
            "every template the class map registers must stay silent");
    }

    // --- sprites ---------------------------------------------------------------------------------

    @Test
    void numberedVariantsCountAsASprite() {
        ModProject project = project();
        record(project, ContentType.block, "basalt");
        project.assets.add(new AssetRef("sprites/blocks/environment/basalt1.png", 1, "sprite"));
        project.assets.add(new AssetRef("sprites/blocks/environment/basalt2.png", 1, "sprite"));

        assertEquals(0, messages(project, "has no sprite").size());
    }

    @Test
    void partSpritesCountAsASprite() {
        ModProject project = project();
        record(project, ContentType.block, "duct", "Duct");
        project.assets.add(new AssetRef("sprites/blocks/duct-top-0.png", 1, "sprite"));
        project.assets.add(new AssetRef("sprites/blocks/duct-bottom-0.png", 1, "sprite"));

        assertEquals(0, messages(project, "has no sprite").size());
    }

    @Test
    void aBlockWithNoResemblingSpriteIsStillReported() {
        ModProject project = project();
        record(project, ContentType.block, "kiln");
        project.assets.add(new AssetRef("sprites/blocks/furnace.png", 1, "sprite"));

        assertEquals(1, messages(project, "has no sprite").size());
    }

    @Test
    void environmentFloorsGetTheirVariantsFromNestedFolders() {
        ModProject project = project();
        record(project, ContentType.block, "frost", "Floor");
        project.assets.add(
            new AssetRef("sprites/blocks/environment/petal biome/floor/frost1.png", 1, "sprite"));

        assertEquals(0, messages(project, "pink-black").size());
    }

    @Test
    void environmentBlockWithoutAnySpriteIsStillReported() {
        ModProject project = project();
        record(project, ContentType.block, "frost", "Floor");
        // The project ships sprites, just not one for this block.
        project.assets.add(new AssetRef("sprites/blocks/kiln.png", 1, "sprite"));

        assertEquals(1, messages(project, "pink-black").size());
        assertEquals(1, messages(project, "sprites/blocks/environment/frost.png").size(),
            "the advice points at the folder the map editor reads");
    }
}

package dev.modmaker.core.validate;

import com.google.gson.JsonPrimitive;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The minGameVersion gate (MinGameVersionCheck): failing it drops the whole mod silently
 * (Mods.java:1314-1331), so the five rules here are exactly the difference between "loads" and
 * "nothing happens" - empty, unparseable, newer than 160, class mod below 154, below 136 - plus
 * the silent and boundary cases that keep the rule from firing on healthy mods.
 */
class ValidatorMinGameVersionTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        assumeTrue(Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.load(Path.of("schemas"));
    }

    private static ModProject project(String minGameVersion) {
        ModProject project = new ModProject();
        project.meta.name = "mgv-test";
        project.meta.minGameVersion = minGameVersion;
        return project;
    }

    /** The full pass, filtered to this rule family, so registration is covered by every case. */
    private static List<Issue> findings(ModProject project) {
        return new Validator(registry).validate(project).stream()
            .filter(issue -> issue.key().startsWith("validate.meta.minGameVersion."))
            .toList();
    }

    private static Issue only(ModProject project) {
        List<Issue> found = findings(project);
        assertEquals(1, found.size(), found.toString());
        return found.get(0);
    }

    // --- rule 1: empty / blank -> error -------------------------------------------------------------

    @Test
    void anEmptyMinGameVersionIsAnError() {
        // The codec omits a blank value (ModMetaCodec.java:66-68) and the game defaults the missing
        // key to "0" (Mods.java:1417), which is below every floor it enforces.
        assertEquals("validate.meta.minGameVersion.empty", only(project("")).key());
        assertEquals(Issue.Level.error, only(project("")).level());
        assertEquals("validate.meta.minGameVersion.empty", only(project("   ")).key());
        assertEquals("validate.meta.minGameVersion.empty", only(project(null)).key());
    }

    // --- rule 2: not a build number -> error ------------------------------------------------------

    @Test
    void aNonNumericMinGameVersionIsAnError() {
        Issue issue = only(project("v146"));
        assertEquals("validate.meta.minGameVersion.badFormat", issue.key());
        assertEquals(Issue.Level.error, issue.level());
        assertEquals(List.of("v146"), issue.args());

        assertEquals("validate.meta.minGameVersion.badFormat", only(project("146-beta")).key());
        // Four segments is outside the shape the game can read at all.
        assertEquals("validate.meta.minGameVersion.badFormat", only(project("1.4.6.7")).key());
    }

    @Test
    void aDottedVersionTheGameMisreadsStillFailsAsTooOld() {
        // "1.4.6" fits the build-number shape, but getMinMajor reads only the leading "1"
        // (Mods.java:1457-1461), so the verdict comes from the age floor, not the format rule.
        Issue issue = only(project("1.4.6"));
        assertEquals("validate.meta.minGameVersion.tooOld", issue.key());
        assertEquals(Issue.Level.error, issue.level());
    }

    // --- rule 3: major > 160 -> warning, error for class mods -------------------------------------

    @Test
    void aNewerThanCurrentBuildIsAWarnedRefusal() {
        Issue pure = only(project("161"));
        assertEquals("validate.meta.minGameVersion.tooHigh", pure.key());
        assertEquals(Issue.Level.warning, pure.level());
        assertEquals(List.of("161", 161), pure.args());

        ModProject classMod = project("161");
        classMod.meta.java = true;
        assertEquals(Issue.Level.error, only(classMod).level());
    }

    // --- rule 4: class mod below 154 -> error unless legacyCompatible -------------------------------

    @Test
    void aClassModBelow154IsAnError() {
        // G1-c: the mod enters the list but its code never loads (Vars.java:55, Mods.java:1180).
        ModProject javaMod = project("146");
        javaMod.meta.java = true;
        Issue issue = only(javaMod);
        assertEquals("validate.meta.minGameVersion.classModTooOld", issue.key());
        assertEquals(Issue.Level.error, issue.level());

        ModProject mainMod = project("153");
        mainMod.meta.mainClass = "my.mod.MyMod";
        assertEquals("validate.meta.minGameVersion.classModTooOld", only(mainMod).key());
    }

    @Test
    void legacyCompatibleIsTheOnlyEscapeFromTheClassModFloor() {
        // legacyCompatible is unmodelled and survives in extra (ModMetaCodec.java:36-40);
        // the game honours it at Mods.java:1329-1331.
        ModProject escaped = project("146");
        escaped.meta.java = true;
        escaped.meta.extra.put("legacyCompatible", new JsonPrimitive(true));
        assertEquals(List.of(), findings(escaped));
    }

    // --- rule 5: major < 136 -> error unless legacyCompatible ---------------------------------------

    @Test
    void below136IsAnError() {
        Issue issue = only(project("126"));
        assertEquals("validate.meta.minGameVersion.tooOld", issue.key());
        assertEquals(Issue.Level.error, issue.level());
        assertEquals(List.of("126"), issue.args());

        assertEquals("validate.meta.minGameVersion.tooOld", only(project("135")).key());

        ModProject escaped = project("126");
        escaped.meta.extra.put("legacyCompatible", new JsonPrimitive(true));
        assertEquals(List.of(), findings(escaped));
    }

    // --- silent and boundary cases ------------------------------------------------------------------

    @Test
    void supportedValuesAreSilent() {
        // The floors are inclusive and the current major is 160, not above it.
        assertEquals(List.of(), findings(project("136")));
        assertEquals(List.of(), findings(project("146")),
            "an old pure-content mod still loads on 160 and must stay untouched");
        assertEquals(List.of(), findings(project("160")));
        assertEquals(List.of(), findings(project("160.7")));
        assertEquals(List.of(), findings(project("160.7.1")));

        ModProject javaMod = project("154");
        javaMod.meta.java = true;
        assertEquals(List.of(), findings(javaMod));
    }

    @Test
    void theCheckIsRegisteredAndSelectableById() {
        assertTrue(Validator.defaultChecks().stream()
            .anyMatch(check -> check.id().equals("minGameVersion")));
        List<Issue> selective = new Validator(registry)
            .validate(project(""), Set.of("minGameVersion"));
        assertEquals(List.of("validate.meta.minGameVersion.empty"),
            selective.stream().map(Issue::key).toList());
    }
}

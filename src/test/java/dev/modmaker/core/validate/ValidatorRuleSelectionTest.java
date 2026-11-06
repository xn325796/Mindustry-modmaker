package dev.modmaker.core.validate;

import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule registry: every check has a stable unique id, the full pass runs all of them, and a caller
 * can ask for one family of findings (the reference sweep after a rename, for instance).
 */
class ValidatorRuleSelectionTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.loadDefault();
    }

    /** A project that trips both the record check and the reference check. */
    private static ModProject project() {
        ModProject project = new ModProject();
        project.meta.name = "rule-selection";
        project.metaFile = "mod.json";

        ContentRecord first = ContentRecord.create("c1", ContentType.item, "duplicate");
        ContentRecord second = ContentRecord.create("c2", ContentType.item, "duplicate");
        ContentRecord block = ContentRecord.create("c3", ContentType.block, "consumer");
        block.setClassName("GenericCrafter");
        JsonObject requirement = new JsonObject();
        requirement.addProperty("item", "no-such-item");
        requirement.addProperty("amount", 1);
        com.google.gson.JsonArray requirements = new com.google.gson.JsonArray();
        requirements.add(requirement);
        block.fields.put("requirements", requirements);

        project.contents.add(first);
        project.contents.add(second);
        project.contents.add(block);
        return project;
    }

    @Test
    void checkIdsAreUniqueAndCoverBothFamilies() {
        List<ProjectCheck> checks = Validator.defaultChecks();
        Set<String> ids = checks.stream().map(ProjectCheck::id).collect(Collectors.toSet());
        assertEquals(checks.size(), ids.size(), "two checks share an id: " + ids);
        assertTrue(ids.contains("records"), ids.toString());
        assertTrue(ids.contains("references"), ids.toString());
        assertTrue(ids.size() >= 12, "the rule set is expected to be split, saw: " + ids);
        // Every check must be safe to run on its own; the ids are the public API for selective runs.
        assertTrue(new HashSet<>(ids).containsAll(
            checks.stream().map(ProjectCheck::id).toList()));
    }

    @Test
    void aSelectiveRunOnlyReportsThatFamily() {
        ModProject project = project();
        Validator validator = new Validator(registry);
        List<Issue> all = validator.validate(project);
        assertTrue(all.stream().anyMatch(issue -> issue.key().equals("validate.name.duplicate")),
            "precondition: the duplicate is reported by the full pass");
        assertTrue(all.stream().anyMatch(issue -> issue.key().equals("validate.reference.unresolved")),
            "precondition: the dangling reference is reported by the full pass");

        List<Issue> referencesOnly = validator.validate(project, Set.of("references"));
        assertFalse(referencesOnly.isEmpty());
        assertTrue(referencesOnly.stream()
                .allMatch(issue -> issue.key().equals("validate.reference.unresolved")),
            "a reference-only run must not report unrelated rules: " + referencesOnly);
        assertTrue(referencesOnly.size() < all.size());
    }

    @Test
    void anUnknownCheckIdIsRejected() {
        Validator validator = new Validator(registry);
        assertThrows(IllegalArgumentException.class,
            () -> validator.validate(project(), Set.of("no-such-check")));
    }
}

package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.core.validate.Issue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cache and freshness contract of the validation session, without the FX toolkit: {@link
 * ValidationSession#current()} still serves findings synchronously (the exit guard and the CLI-style
 * callers rely on that), while {@code invalidate}/{@code forceSync} decide when they are recomputed.
 */
class ValidationSessionTest {

    private static SchemaRegistry registry;

    @BeforeAll
    static void loadRegistry() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.loadDefault();
    }

    /** A project whose one duplicate name makes the validator produce a stable finding. */
    private static ModProject projectWithDuplicateName() {
        ModProject project = new ModProject();
        project.meta.name = "session-test";
        project.metaFile = "mod.json";
        ContentRecord first = ContentRecord.create("c1", ContentType.item, "same-name");
        ContentRecord second = ContentRecord.create("c2", ContentType.item, "same-name");
        project.contents.add(first);
        project.contents.add(second);
        return project;
    }

    @Test
    void currentComputesOnceAndServesTheSameList() {
        ModProject project = projectWithDuplicateName();
        ValidationSession session = new ValidationSession(registry, () -> project);

        List<Issue> first = session.current();
        assertFalse(first.isEmpty(), "the duplicate name must be reported");
        assertSame(first, session.current(), "one pass serves every pane");
        assertSame(first, session.latest(), "the published list is the cached one");
    }

    @Test
    void invalidateAndForceSyncProduceFreshResults() {
        ModProject project = projectWithDuplicateName();
        ValidationSession session = new ValidationSession(registry, () -> project);
        List<Issue> first = session.current();

        List<Issue> forced = session.forceSync();
        assertNotSame(first, forced, "an explicit pass bypasses the cache");
        assertSame(forced, session.current(), "the forced pass repopulates the cache");

        // Fix the duplicate and invalidate: a stale cache would keep reporting it.
        project.contents.remove(1);
        session.invalidate();
        assertTrue(session.current().stream().noneMatch(issue -> issue.key().contains("duplicate")),
            "the invalidated pass must see the fixed model");
    }

    @Test
    void resetForgetsThePublishedFindings() {
        ModProject project = projectWithDuplicateName();
        ValidationSession session = new ValidationSession(registry, () -> project);
        assertFalse(session.current().isEmpty());

        session.reset();
        assertEquals(List.of(), session.latest(), "a replaced project shows no stale findings");
    }

    @Test
    void validatingASnapshotMatchesValidatingTheLiveProject() {
        ModProject project = projectWithDuplicateName();
        ValidationSession session = new ValidationSession(registry, () -> project);
        List<Issue> live = session.current();
        List<Issue> fromSnapshot = session.validate(project.snapshot());
        assertEquals(live.size(), fromSnapshot.size(),
            "a snapshot must carry everything the validator reads");
        assertNotSame(live, fromSnapshot);
    }
}

package dev.modmaker.core.io;

import java.nio.file.Path;

/**
 * Locates the real-mod fixture the golden tests and the live-edit probe run against. The probing
 * rules live in {@link FixtureProject} (main sources), because the app's startup auto-open must
 * agree with the tests on what "the fixture" means: one implementation, two callers.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** The folder holding the mod's meta file, or null when the fixture is absent. */
    public static Path realModRoot() {
        return FixtureProject.probe();
    }
}

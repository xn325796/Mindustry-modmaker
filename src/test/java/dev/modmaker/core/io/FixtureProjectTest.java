package dev.modmaker.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The startup probe: a plain app launch must find the workspace's own mod so the tech tree and the
 * other views show real content instead of an empty shell.
 */
class FixtureProjectTest {

    @Test
    void rejectsAMetaFileWithoutAName(@TempDir Path temp) throws Exception {
        // Mindustry's own tools/extra-schemas/mod.json is a schema fragment, not a mod: it parses,
        // but it has no `name`. A real probe must not mistake it for the fixture mod.
        Files.writeString(temp.resolve("mod.json"), "{doc: extra schemas, amount: 3}\n");

        assertFalse(FixtureProject.hasMetaFile(temp),
            "a mod.json that declares no name is not a mod");
    }

    @Test
    void acceptsAMetaFileWithAName(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("mod.json"), "{name: probe-test}\n");

        assertTrue(FixtureProject.hasMetaFile(temp),
            "a mod.json that declares a name is a mod");
    }

    @Test
    void probeFindsTheWorkspaceFixtureMod() {
        Path hinted = SearchPaths.workingDirectory().resolve("../mod").normalize();
        assumeTrue(Files.isDirectory(hinted), "documented workspace layout not present");

        Path found = FixtureProject.probe();
        assertEquals(hinted, found, "the workspace hint beats the shallow scan");
        assertTrue(Files.isRegularFile(found.resolve("mod.hjson"))
                || Files.isRegularFile(found.resolve("mod.json")),
            "the probed folder is one the game itself would accept");
    }
}

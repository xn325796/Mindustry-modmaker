package dev.modmaker.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game loop plumbing: finding a JVM, finding the game jar and recognising content errors in the
 * game's own log. Nothing here launches anything, so it is safe to run anywhere.
 */
class GameLauncherTest {

    @Test
    void findsAJavaRuntimeOnThisMachine() {
        Path java = GameLauncher.detectJava();
        assertTrue(java == null || Files.exists(java), "detected java must exist when not null: " + java);
        if (java != null) {
            assertTrue(java.getFileName().toString().startsWith("java"), java.toString());
        }
    }

    @Test
    void findsTheGameJarNextToTheWorkspace() {
        Path jar = GameLauncher.detectGameJar();
        if (jar != null) {
            assertTrue(Files.exists(jar));
            assertTrue(jar.getFileName().toString().equals("Mindustry.jar"), jar.toString());
        }
    }

    @Test
    void recognisesContentErrorsInAGameLog() throws IOException {
        Path log = Files.createTempFile("modmaker-log", ".txt");
        Files.writeString(log, """
            10:00:00 [INFO] Loading mods...
            10:00:01 [ERR] Error loading content: content/items/broken.json
            10:00:01 [WARN] Sprite not found: 'my-mod-missing'
            10:00:02 [INFO] Mod loaded: my-mod
            """);
        List<String> problems = GameLauncher.readProblems(log);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("[game]"));
        Files.deleteIfExists(log);
    }

    @Test
    void reportsACleanLogAsClean() throws IOException {
        Path log = Files.createTempFile("modmaker-clean", ".txt");
        Files.writeString(log, "10:00:00 [INFO] Mod loaded: my-mod\n");
        List<String> problems = GameLauncher.readProblems(log);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("no content errors"));
        assertFalse(problems.get(0).contains("Sprite"));
        Files.deleteIfExists(log);
    }

    @Test
    void catchesCrashLinesWhileTheGameIsStillRunning() {
        assertTrue(GameLauncher.isProblemLine("java.lang.RuntimeException: bad block"),
            "stack traces must surface live, not only after the game exits");
        assertTrue(GameLauncher.isProblemLine("An error has occured in thread main"),
            "the game's crash banner must surface");
        assertFalse(GameLauncher.isProblemLine("10:00:00 [INFO] Mod loaded: my-mod"),
            "ordinary lines stay out of the way");
    }

    /**
     * The throwaway per-run data directory must not be left behind, and must never be pulled out
     * from under a game that is still running.
     */
    @Test
    void aFinishedSessionDeletesItsScratchDirectory() throws IOException {
        Path dataDir = Files.createTempDirectory("modmaker-session-");
        Files.createDirectories(dataDir.resolve("mods/my-mod"));
        Files.writeString(dataDir.resolve("mods/my-mod/mod.json"), "{}");
        Path outside = Files.createTempFile("modmaker-outside-", ".txt");

        new GameLauncher.Session(new FakeProcess(true), dataDir, dataDir.resolve("last_log.txt"),
            dataDir.resolve("mods/my-mod")).close();
        assertTrue(Files.isDirectory(dataDir), "a live game keeps its data directory");

        new GameLauncher.Session(new FakeProcess(false), dataDir, dataDir.resolve("last_log.txt"),
            dataDir.resolve("mods/my-mod")).close();
        assertFalse(Files.exists(dataDir), "the scratch directory is gone once the game stopped");
        assertTrue(Files.exists(outside), "nothing outside the scratch directory is touched");
        Files.deleteIfExists(outside);
    }

    /** A process whose liveness the test decides; it never runs anything. */
    private static final class FakeProcess extends Process {
        private final boolean alive;

        FakeProcess(boolean alive) {
            this.alive = alive;
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public java.io.InputStream getInputStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}

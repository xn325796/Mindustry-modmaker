package dev.modmaker.ui;

import dev.modmaker.core.io.SearchPaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The verification loop: build the mod into a throwaway data directory and start Mindustry against
 * it.
 *
 * <p>ClientLauncher.java:42 honours MINDUSTRY_DATA_DIR (or -Dmindustry.data.dir), so the game reads
 * its mods, saves and logs from a scratch folder. That means a test run never touches the player's
 * real profile, and the game's own last_log.txt can be read back to report content errors.
 */
public final class GameLauncher {

    /** Where a JDK keeps its launcher, per platform. */
    private static final String JAVA_EXECUTABLE =
        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
    private static final String GAME_JAR = "Mindustry.jar";
    /** Levels below an install root searched for bin/java. */
    private static final int JAVA_SCAN_DEPTH = 3;

    /**
     * Output watchers, one per test-play session. A managed daemon pool rather than a bare
     * {@code new Thread} per launch: the threads are named consistently, cannot keep the JVM alive,
     * and {@link #shutdownWatchers()} can stop them when the window closes.
     */
    private static final java.util.concurrent.ExecutorService WATCHERS =
        java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "modmaker-game-watcher");
            thread.setDaemon(true);
            return thread;
        });

    /** Stops the watcher pool; called from the application's shutdown hook. */
    public static void shutdownWatchers() {
        WATCHERS.shutdownNow();
    }

    /** A running (or finished) test-play session, and the scratch data directory it owns. */
    public record Session(Process process, Path dataDir, Path logFile, Path modFolder) {

        /** True while the launched game is still running. */
        public boolean running() {
            return process != null && process.isAlive();
        }

        /**
         * Deletes the throwaway data directory (mods, saves, logs). Refuses while the game is still
         * running - pulling its data directory out from under it corrupts the run.
         */
        public void close() {
            if (running()) {
                return;
            }
            deleteTree(dataDir);
        }
    }

    private GameLauncher() {
    }

    /**
     * Deletes a scratch tree without ever following a link out of it: {@link Files#walk} does not
     * follow symbolic links unless asked to, and deleting a link removes the link itself, so a
     * symlink planted inside the game's data directory cannot reach the user's real files.
     */
    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a file the game is still holding open stays behind.
                }
            });
        } catch (IOException ignored) {
            // The directory is gone already or unreadable: nothing left to clean up.
        }
    }

    /**
     * Builds the saved plan into scratch/mods/&lt;name&gt;/ and launches the game on it. The plan was
     * produced on the model's thread; this method only touches the filesystem and the process.
     */
    public static Session buildAndLaunch(ProjectController controller,
        dev.modmaker.core.pipeline.BuildPipeline.Plan plan,
        Path gameJar, Path javaExe, Consumer<String> log) throws IOException {
        Path dataDir = Files.createTempDirectory("modmaker-game-");
        try {
            Path modsDir = dataDir.resolve("mods");
            Files.createDirectories(modsDir);

            var report = controller.writeBuildFolder(plan, modsDir);
            log.accept("installed " + report.files().size() + " files into " + report.target());
            log.accept("game data directory: " + dataDir);

            List<String> command = new ArrayList<>();
            command.add(javaExe.toString());
            command.add("-Xms512m");
            command.add("-Xmx2G");
            command.add("-jar");
            command.add(gameJar.toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(gameJar.toAbsolutePath().getParent().toFile());
            builder.environment().put("MINDUSTRY_DATA_DIR", dataDir.toAbsolutePath().toString());
            Path consoleLog = dataDir.resolve("console.log");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            log.accept("launched: " + String.join(" ", command));

            // Stream the game's output as it happens: a crash is visible in the log tab without
            // closing the game, and the full output still lands in console.log for later reading.
            WATCHERS.execute(() -> {
                try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
                    var writer = openConsoleLog(consoleLog, log)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (writer != null) {
                            writer.println(line);
                            writer.flush();
                        }
                        if (isProblemLine(line)) {
                            log.accept("[game] " + line.strip());
                        }
                    }
                } catch (IOException failure) {
                    log.accept("could not read the game output: " + failure.getMessage());
                }
                int exit = process.isAlive() ? -1 : process.exitValue();
                try {
                    exit = process.waitFor();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                log.accept("game exited with code " + exit);
                Path gameLog = dataDir.resolve("last_log.txt");
                if (Files.exists(gameLog)) {
                    try {
                        readProblems(gameLog).forEach(log);
                    } catch (IOException failure) {
                        log.accept("could not read the game log: " + failure.getMessage());
                    }
                }
            });

            return new Session(process, dataDir, dataDir.resolve("last_log.txt"),
                Path.of(report.target()));
        } catch (IOException | RuntimeException failure) {
            deleteTree(dataDir);
            throw failure;
        }
    }

    /**
     * The console-log writer, or null when it cannot be opened. Returning null (instead of failing
     * the watcher) keeps stdout draining: an undrained pipe would freeze the game once its output
     * buffer fills.
     */
    private static java.io.PrintWriter openConsoleLog(Path consoleLog,
        java.util.function.Consumer<String> log) {
        try {
            return new java.io.PrintWriter(Files.newBufferedWriter(consoleLog));
        } catch (IOException failure) {
            log.accept("could not write the console log, output is not saved: "
                + failure.getMessage());
            return null;
        }
    }

    /** Lines from the game output that indicate content or loading problems. */
    static boolean isProblemLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("error loading content") || lower.contains("sprite not found")
            || lower.contains("failed to load") || lower.contains("no parsers for content type")
            || lower.contains("has errored") || lower.contains("exception")
            // the game's own crash banner (yes, "occured" is the game's spelling)
            || lower.contains("an error has occured");
    }

    /** Problem lines from a finished game log, for the summary after the game exits. Bounded so a
     * gigabyte error log (a mod spamming warnings) cannot exhaust the heap. */
    public static List<String> readProblems(Path logFile) throws IOException {
        List<String> problems = new ArrayList<>();
        // The game's log is usually UTF-8, but locale-dependent output on a non-UTF-8 Windows
        // console can inject other bytes; a strict reader would throw and lose the whole summary,
        // so undecodable bytes become the replacement character instead.
        var decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
            Files.newInputStream(logFile), decoder))) {
            String line;
            int scanned = 0;
            while ((line = reader.readLine()) != null && scanned++ < 200_000) {
                if (isProblemLine(line)) {
                    problems.add("[game] " + line.strip());
                }
            }
        }
        if (problems.isEmpty()) {
            problems.add("[game] no content errors reported in " + logFile.getFileName());
        }
        return problems;
    }

    /**
     * Finds a java executable: JAVA_HOME first, then PATH, then the usual install roots for this
     * OS. Nothing here is tied to one machine's layout, so the launcher works from any checkout.
     */
    public static Path detectJava() {
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) {
            Path candidate = javaIn(Path.of(home));
            if (candidate != null) {
                return candidate;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    Path candidate = javaIn(Path.of(entry.strip()));
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        for (Path root : javaSearchRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            Path found = SearchPaths.find(root, JAVA_SCAN_DEPTH, GameLauncher::isJavaExecutable);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** A java executable directly inside {@code home}/bin, or null. */
    private static Path javaIn(Path home) {
        Path executable = home.resolve("bin").resolve(JAVA_EXECUTABLE);
        return Files.isRegularFile(executable) ? executable.toAbsolutePath() : null;
    }

    private static boolean isJavaExecutable(Path path) {
        return path.getFileName().toString().equals(JAVA_EXECUTABLE)
            && path.getParent() != null
            && path.getParent().getFileName().toString().equals("bin")
            && Files.isRegularFile(path);
    }

    private static List<Path> javaSearchRoots() {
        String home = System.getProperty("user.home", ".");
        if (isWindows()) {
            return List.of(Path.of("C:/Program Files/Java"),
                Path.of("C:/Program Files/Eclipse Adoptium"),
                Path.of("C:/Program Files/Microsoft"),
                Path.of("C:/Program Files/Zulu"),
                Path.of(home, ".jdks"));
        }
        return List.of(Path.of("/usr/lib/jvm"),
            Path.of("/Library/Java/JavaVirtualMachines"),
            Path.of(home, ".sdkman/candidates/java"),
            Path.of(home, ".jdks"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Finds the game jar: an explicit setting first, then the usual spots around the project. */
    public static Path detectGameJar() {
        Path explicit = SearchPaths.override("modmaker.gameJar", "MINDUSTRY_JAR");
        if (explicit != null && Files.isRegularFile(explicit)) {
            return explicit;
        }
        return SearchPaths.firstFile(List.of(
            Path.of("..", "Mindustry", GAME_JAR),
            Path.of("Mindustry", GAME_JAR),
            Path.of("..", "..", "Mindustry", GAME_JAR)));
    }
}

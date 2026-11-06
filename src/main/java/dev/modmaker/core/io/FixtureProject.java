package dev.modmaker.core.io;

import dev.modmaker.core.json.HjsonReader;
import dev.modmaker.core.model.ModMeta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Locates the real-mod project the app opens at startup when the command line names none, and the
 * golden tests run against, wherever it happens to sit on the machine. No specific mod is named
 * here: an explicit setting wins, then the relative spot the usual workspace layout provides
 * ({@code ../mod}), then a shallow scan of the folders beside the working directory for any folder
 * the game itself would accept as a mod - recognised by the meta file names it probes for, so
 * mod.hjson is as valid as mod.json. When nothing matches, the caller skips cleanly instead of
 * failing: the app just starts empty, the tests skip.
 */
public final class FixtureProject {

    /** Point the probe at a mod with -Dmodmaker.fixture=&lt;dir&gt; or MODMAKER_FIXTURE. */
    public static final String PROPERTY = "modmaker.fixture";
    public static final String ENV = "MODMAKER_FIXTURE";

    /** Where the fixture usually sits when the workspace follows the documented layout. */
    private static final String WORKSPACE_HINT = "../mod";

    /** Levels below the working directory's parent searched while looking for a mod. */
    private static final int SCAN_DEPTH = 3;

    private FixtureProject() {
    }

    /** The folder to open at startup, or null when nothing on this machine looks like a mod. */
    public static Path probe() {
        Path explicit = SearchPaths.override(PROPERTY, ENV);
        if (explicit != null) {
            Path mod = asModRoot(explicit);
            if (mod != null) {
                return mod;
            }
        }
        Path working = SearchPaths.workingDirectory();
        Path hinted = asModRoot(working.resolve(WORKSPACE_HINT));
        if (hinted != null) {
            return hinted;
        }
        return SearchPaths.find(working.getParent(), SCAN_DEPTH, FixtureProject::hasMetaFile);
    }

    /**
     * True when {@code folder} holds a meta file the game would accept as a mod: the file must
     * exist, parse, and declare a non-blank {@code name}. A mere file named {@code mod.json} is
     * not enough - Mindustry ships schema fragments of the same name (for example
     * {@code tools/extra-schemas/mod.json}) that have no name and must not be opened as a mod.
     */
    static boolean hasMetaFile(Path folder) {
        for (String metaFile : ModMeta.META_FILES) {
            Path metaPath = folder.resolve(metaFile);
            if (!Files.isRegularFile(metaPath)) {
                continue;
            }
            try {
                String text = Files.readString(metaPath, StandardCharsets.UTF_8);
                ModMeta meta = ModMetaCodec.fromJson(HjsonReader.readObject(text));
                if (meta.name != null && !meta.name.isBlank()) {
                    return true;
                }
            } catch (IOException | RuntimeException failure) {
                // Unreadable or unparseable meta: the game would not load it either.
            }
        }
        return false;
    }

    /** The folder itself when it is a mod, else a single wrapping folder that is. */
    private static Path asModRoot(Path folder) {
        if (!Files.isDirectory(folder)) {
            return null;
        }
        if (hasMetaFile(folder)) {
            return folder.toAbsolutePath().normalize();
        }
        // Tolerate one wrapping folder, like the game's resolveRoot does.
        try (Stream<Path> entries = Files.list(folder)) {
            for (Path child : entries.filter(Files::isDirectory).toList()) {
                if (hasMetaFile(child)) {
                    return child.toAbsolutePath().normalize();
                }
            }
        } catch (IOException ignored) {
            // Fall through: the caller tries the next place.
        }
        return null;
    }
}

package dev.modmaker.core.io;

import dev.modmaker.core.model.ModMeta;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Crash recovery: a periodic snapshot of the editable text state (meta, content, bundles, boards)
 * written under {@code modmaker/autosave/}. That folder lives inside the authoring directory the
 * exporter and the mod reader already ignore, so an autosave never ships in a build and never gets
 * adopted as content.
 *
 * <p>An autosave is a full project folder on its own: {@link #write} plans a detached snapshot with
 * {@link ProjectIo#plan} rooted at the autosave directory, so the live model's dirty flags are never
 * touched. {@link #restore} opens that folder as a project and saves it back over the real root -
 * reusing the same prune logic as a normal save, so content and bundle files deleted since the last
 * autosave are removed rather than left behind.
 */
public final class Autosave {

    /** The directory under {@code modmaker/} that holds the latest autosave. */
    public static final String AUTOSAVE_DIR = "autosave";
    /** A marker file whose mtime is the autosave timestamp; the rest of the folder is project data. */
    private static final String MARKER = ".autosave";

    private Autosave() {
    }

    /** The autosave folder for a project, regardless of whether it exists. */
    public static Path root(Path projectRoot) {
        return projectRoot.resolve(ProjectIo.AUTHORING_DIR).resolve(AUTOSAVE_DIR);
    }

    /**
     * Writes the current editable state as a self-contained snapshot. The argument must be a
     * detached copy ({@link ModProject#snapshot()}) so {@link ProjectIo#plan} - which updates the
     * model's root and dirty flags - never reaches the live model while the user edits it.
     */
    public static void write(ModProject snapshot) throws IOException {
        Path target = root(snapshot.root);
        ProjectIo.write(ProjectIo.plan(snapshot, target));
        Files.writeString(target.resolve(MARKER),
            Long.toString(System.currentTimeMillis()) + System.lineSeparator());
    }

    /** True when a snapshot exists at all. */
    public static boolean exists(Path projectRoot) {
        return Files.isDirectory(root(projectRoot))
            && Files.exists(root(projectRoot).resolve(ProjectIo.AUTHORING_DIR)
                .resolve(ProjectIo.PROJECT_FILE));
    }

    /**
     * The mtime of the last autosave, or 0 when there is none. The marker is written last, so its
     * timestamp is the moment the snapshot finished.
     */
    public static long lastModified(Path projectRoot) {
        Path marker = root(projectRoot).resolve(MARKER);
        try {
            return Files.exists(marker) ? Files.getLastModifiedTime(marker).toMillis() : 0;
        } catch (IOException failure) {
            return 0;
        }
    }

    /** The time the project itself was last saved: the authoring layer, else the meta file. */
    public static long projectSaveTime(Path projectRoot) {
        Path projectFile = projectRoot.resolve(ProjectIo.AUTHORING_DIR)
            .resolve(ProjectIo.PROJECT_FILE);
        try {
            if (Files.exists(projectFile)) {
                return Files.getLastModifiedTime(projectFile).toMillis();
            }
            for (String name : ModMeta.META_FILES) {
                Path meta = projectRoot.resolve(name);
                if (Files.exists(meta)) {
                    return Files.getLastModifiedTime(meta).toMillis();
                }
            }
        } catch (IOException ignored) {
            // fall through to "no project on disk yet"
        }
        return 0;
    }

    /** True when the autosave holds edits newer than the project's last save. */
    public static boolean isNewerThan(Path projectRoot) {
        return lastModified(projectRoot) > projectSaveTime(projectRoot);
    }

    /**
     * Restores the autosave over the project folder and returns the recovered model, ready to be
     * published. Assets (sprites, sounds, maps) are never written by a save, so the autosave does not
     * carry them; they are re-read from the real folder, which the restore leaves untouched.
     */
    public static ModProject restore(Path projectRoot, SchemaRegistry registry) throws IOException {
        ModProject recovered = ProjectIo.open(root(projectRoot), registry);
        ModProject base = ProjectIo.open(projectRoot, registry);
        recovered.assets = base.assets;
        ProjectIo.save(recovered, projectRoot);
        discard(projectRoot);
        return recovered;
    }

    /** Deletes the autosave folder, if any. Used after a manual save or an explicit discard. */
    public static void discard(Path projectRoot) throws IOException {
        Path autosave = root(projectRoot);
        if (!Files.exists(autosave)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(autosave)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A delete of a locked file is best-effort: the next save overwrites it.
                }
            });
        }
    }
}

package dev.modmaker.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a file through a sibling temp file and swaps it into place, so a reader - or a crash - never
 * sees half of it.
 *
 * <p>Both writers of this tool need that guarantee for the same reason: a save must not leave a
 * truncated mod file behind (the game would load it and the author would lose the previous version),
 * and a build must not destroy the last good archive when it fails halfway. The contract used to be
 * spelled out twice with slightly different flags - the save's rename did not even ask for
 * {@code ATOMIC_MOVE} - so it lives here once, next to the {@link ProjectIo#TEMP_SUFFIX} both sides
 * already agree on.
 */
final class AtomicFiles {

    private AtomicFiles() {
    }

    /** Writes {@code text} as UTF-8 to {@code target}, creating parent folders as needed. */
    static void writeText(Path target, String text) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = tempFor(absolute);
        try {
            Files.writeString(temp, text == null ? "" : text, StandardCharsets.UTF_8);
            moveIntoPlace(temp, absolute);
        } catch (IOException failure) {
            // A leftover temp file is harmless (readers treat it as noise), but leaving it behind
            // after a failed save would hide the real failure in a directory listing.
            deleteQuietly(temp);
            throw failure;
        }
    }

    /**
     * Renames a finished temp file onto its target. The temp file is a sibling, so this is a rename
     * on every real filesystem; {@code ATOMIC_MOVE} is requested and the plain replace is the
     * fallback for providers that cannot promise it.
     */
    static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Best-effort cleanup; the caller's own failure is the one worth reporting. */
    static void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // A leftover temp file is harmless: the next write overwrites it.
        }
    }

    /** The sibling a write goes through first, e.g. {@code mod.json.mm-tmp}. */
    static Path tempFor(Path target) {
        return target.resolveSibling(target.getFileName() + ProjectIo.TEMP_SUFFIX);
    }
}

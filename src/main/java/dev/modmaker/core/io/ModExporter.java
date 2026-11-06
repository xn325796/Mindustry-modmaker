package dev.modmaker.core.io;

import dev.modmaker.core.model.ModMeta;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.validate.Issue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the distributable mod from a saved project folder.
 *
 * <p>Because a project folder already is a mod folder, building is a copy: everything except the
 * authoring directory goes into &lt;destination&gt;/&lt;internalName&gt;/, or into a zip whose single
 * wrapping folder the game unwraps on load (Mods.resolveRoot, Mods.java:1080) and that a user can
 * unpack straight into mods/.
 */
public final class ModExporter {

    /** Fixed timestamp so repeated builds of the same project produce identical archives. */
    private static final long FIXED_TIME =
        LocalDateTime.of(2020, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli();

    private ModExporter() {
    }

    /** Copies the mod into destination/&lt;internalName&gt;/ and returns what was written. */
    public static ExportReport buildFolder(ModProject project, Path destination, List<Issue> issues)
        throws IOException {
        return buildFolder(requireRoot(project), requireInternalName(project), destination, issues);
    }

    /**
     * Builds from values already read off the model. Taking them as arguments lets the caller do the
     * model reading on the thread that owns it and the packing on a worker thread.
     */
    public static ExportReport buildFolder(Path root, String internal, Path destination,
        List<Issue> issues) throws IOException {
        Path modDir = destination.resolve(internal);
        requireOutside(root, modDir);
        Files.createDirectories(modDir);

        // Directories first, so empty ones (an empty sprites-override/, say) survive too.
        for (Path dir : modDirectories(root)) {
            Files.createDirectories(modDir.resolve(relative(root, dir)));
        }

        List<String> written = new ArrayList<>();
        long bytes = 0;
        for (Path file : modFiles(root)) {
            String relative = relative(root, file);
            Path target = modDir.resolve(relative);
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            written.add(relative);
            bytes += Files.size(file);
        }
        // Without this, content deleted from the project would keep re-appearing in the built mod
        // and the game would go on loading it - "the delete did nothing" is brutal to debug.
        List<String> pruned = pruneStale(root, modDir, written);
        return new ExportReport(modDir.toAbsolutePath().toString(), false, written, bytes,
            issues == null ? List.of() : issues, pruned);
    }

    /**
     * Deletes regular files left in the target from earlier builds that this build did not write.
     * Never runs when the target is (or contains) the project folder itself - pruning there would
     * eat the project's own authoring files.
     */
    private static List<String> pruneStale(Path projectRoot, Path modDir, List<String> written)
        throws IOException {
        String rootPath = projectRoot.toAbsolutePath().normalize().toString();
        String targetPath = modDir.toAbsolutePath().normalize().toString();
        // The ancestor check must be case-insensitive: on Windows/macOS a differently-cased target
        // still names the project folder, and pruning there would eat the project's own files.
        if (targetPath.equalsIgnoreCase(rootPath)
            || startsWithIgnoreCase(rootPath, targetPath + java.io.File.separatorChar)) {
            return List.of();
        }
        Set<String> keep = new java.util.HashSet<>(written);
        List<String> pruned = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(modDir)) {
            List<Path> candidates = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : candidates) {
                String relative = relative(modDir, file);
                if (keep.contains(relative)) {
                    continue;
                }
                // Never delete the project's own authoring layer, should it somehow end up here.
                if (relative.equals(ProjectIo.AUTHORING_DIR)
                    || relative.startsWith(ProjectIo.AUTHORING_DIR + "/")) {
                    continue;
                }
                Files.deleteIfExists(file);
                pruned.add(relative);
            }
        }
        return pruned;
    }

    /** Packs the mod into a zip with one wrapping folder, so unpacking gives mods/&lt;name&gt;/. */
    public static ExportReport buildZip(ModProject project, Path zipFile, List<Issue> issues)
        throws IOException {
        return buildZip(requireRoot(project), requireInternalName(project), zipFile, issues);
    }

    /** Packs from values already read off the model; see {@link #buildFolder(Path, String, Path,
     * List)}. */
    public static ExportReport buildZip(Path root, String internal, Path zipFile, List<Issue> issues)
        throws IOException {
        requireOutside(root, zipFile);
        Files.createDirectories(zipFile.toAbsolutePath().getParent());

        List<Path> files = modFiles(root);
        Set<Path> dirsWithFiles = directoriesWithFiles(root, files);

        List<String> written = new ArrayList<>();
        long bytes = 0;
        // Pack into a sibling temp file and swap it in only once the archive is complete. Writing
        // straight into zipFile truncated the previous build, so a failure halfway (an unreadable
        // file, a full disk) destroyed the last good archive and left a corrupt one in its place.
        Path temp = AtomicFiles.tempFor(zipFile.toAbsolutePath());
        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp),
                StandardCharsets.UTF_8)) {
                out.setLevel(Deflater.BEST_COMPRESSION);
                for (Path file : files) {
                    String relative = relative(root, file);
                    ZipEntry entry = new ZipEntry(internal + "/" + relative);
                    entry.setTime(FIXED_TIME);
                    out.putNextEntry(entry);
                    // Streamed straight into the archive instead of readAllBytes: the zip is
                    // written sequentially anyway, so materialising the file first bought nothing
                    // and made the largest asset (a music track, say) cost its full size again in
                    // heap on top of the copy the importer already holds.
                    long size;
                    try (InputStream in = Files.newInputStream(file)) {
                        size = in.transferTo(out);
                    }
                    out.closeEntry();
                    written.add(relative);
                    bytes += size;
                }
                // Empty directories still need an entry, otherwise unpacking loses them.
                for (Path dir : modDirectories(root)) {
                    if (dirsWithFiles.contains(dir)) {
                        continue;
                    }
                    ZipEntry entry = new ZipEntry(internal + "/" + relative(root, dir) + "/");
                    entry.setTime(FIXED_TIME);
                    out.putNextEntry(entry);
                    out.closeEntry();
                }
            }
            AtomicFiles.moveIntoPlace(temp, zipFile);
        } catch (IOException failure) {
            AtomicFiles.deleteQuietly(temp);
            throw failure;
        }
        return new ExportReport(zipFile.toAbsolutePath().toString(), true, written, bytes,
            issues == null ? List.of() : issues, List.of());
    }

    /**
     * Directories at the project root that belong in the built mod: the two structural directories
     * plus every asset directory the importer classifies as such (ModImporter.assetKind). Anything
     * else at the root - build output, an IDE project, the non-dot files of a git working tree - is
     * development noise and must not ship.
     */
    private static final Set<String> MOD_DIRECTORIES = Set.of(
        "content", "bundles", "sprites", "sprites-override", "sounds",
        "music", "scripts", "maps", "schematics");

    /** Root-level files the game itself reads besides the meta file; see ModImporter.assetKind. */
    private static final Set<String> MOD_ROOT_FILES = Set.of("icon.png", "preview.png");

    /** Every file of the mod as it sits in the project folder, dropping authoring and dev noise. */
    public static List<Path> modFiles(Path projectRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk.filter(Files::isRegularFile)
                .filter(file -> isModPath(projectRoot, file))
                // A crash during a save can leave "<target>.mm-tmp" behind; it is not part of the
                // mod and must never be shipped.
                .filter(file -> !file.getFileName().toString().endsWith(ProjectIo.TEMP_SUFFIX))
                .sorted()
                .toList();
        }
    }

    /** Every directory of the mod, dropping the authoring directory tree and dev noise. */
    public static List<Path> modDirectories(Path projectRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk.filter(Files::isDirectory)
                .filter(dir -> !projectRoot.equals(dir))
                .filter(dir -> isModPath(projectRoot, dir))
                .sorted()
                .toList();
        }
    }

    private static Set<Path> directoriesWithFiles(Path root, List<Path> files) {
        Set<Path> dirs = new java.util.HashSet<>();
        for (Path file : files) {
            Path parent = file.getParent();
            while (parent != null && parent.startsWith(root)) {
                dirs.add(parent);
                parent = parent.getParent();
            }
        }
        return dirs;
    }

    /**
     * True for a file or directory that is mod payload. Three exclusions, in order: a dot-segment
     * anywhere in the path (.git/, .gradle/, .gitignore, .DS_Store, editor backups), the authoring
     * layer, and anything outside the known mod directories / root files. A project folder often is
     * a git working tree or sits inside a larger checkout, so walking everything and shipping it
     * used to put .git/objects into the built zip and register each blob as a game asset.
     */
    private static boolean isModPath(Path projectRoot, Path path) {
        Path relative = projectRoot.relativize(path);
        int segments = relative.getNameCount();
        if (segments == 0) {
            return false;
        }
        for (int i = 0; i < segments; i++) {
            String segment = relative.getName(i).toString();
            if (segment.isEmpty() || segment.charAt(0) == '.') {
                return false;
            }
        }
        String first = relative.getName(0).toString();
        if (first.equalsIgnoreCase(ProjectIo.AUTHORING_DIR)) {
            return false;
        }
        if (segments == 1) {
            String name = relative.toString();
            for (String meta : ModMeta.META_FILES) {
                if (name.equals(meta)) {
                    return true;
                }
            }
            return MOD_ROOT_FILES.contains(name);
        }
        return MOD_DIRECTORIES.contains(first);
    }

    public static Path requireRoot(ModProject project) throws IOException {
        if (project.root == null || !Files.isDirectory(project.root)) {
            throw new IOException("project has no folder on disk; save it before building");
        }
        return project.root;
    }

    public static String requireInternalName(ModProject project) throws IOException {
        String internal = project.internalName();
        if (internal.isEmpty()) {
            throw new IOException("mod name is empty; the built folder needs a name");
        }
        if (!dev.modmaker.core.fmt.NameRules.isFolderSafe(internal)) {
            throw new IOException("mod name is not a safe folder name: " + internal);
        }
        if (internal.contains(" ") || !internal.equals(internal.toLowerCase(Locale.ROOT))) {
            throw new IOException("mod name does not clean up to a folder-safe name: " + internal);
        }
        return internal;
    }

    /**
     * A build target strictly inside the project folder would nest a copy of the mod into itself
     * and grow on every build. Building onto the project folder itself, or above it, is tolerated:
     * pruneStale refuses to delete anything there.
     */
    private static void requireOutside(Path projectRoot, Path target) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path resolved = target.toAbsolutePath().normalize();
        // Case-insensitive on purpose: a differently-cased target still names a path inside the
        // project on Windows/macOS, and building there would nest a copy on every build.
        boolean same = resolved.toString().equalsIgnoreCase(root.toString());
        boolean inside = startsWithIgnoreCase(resolved.toString(), root + java.io.File.separator);
        if (!same && inside) {
            throw new IOException("the build target is inside the project folder: " + resolved
                + " - the mod would nest a copy of itself; pick a folder outside");
        }
    }

    private static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}

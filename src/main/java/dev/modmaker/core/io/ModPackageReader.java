package dev.modmaker.core.io;

import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.BundleCodec;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.json.HjsonReader;
import dev.modmaker.core.json.Json;
import dev.modmaker.core.model.ModMeta;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a mod the way the game does: a folder or a zip/jar, unwrapping a single top-level folder
 * (Mods.resolveRoot, Mods.java:1080), recognising the four meta file names in order (Mods.java:34),
 * scanning content/&lt;folder&gt; recursively with the historical plural aliases (Mods.java:877-885)
 * and picking up bundles/bundle*.properties.
 *
 * <p>Files that are not content or bundles are kept as assets so an untouched mod can be rebuilt
 * exactly. OS and VCS junk is dropped instead.
 */
public final class ModPackageReader {

    private static final List<String> NOISE_FILES =
        List.of(".DS_Store", "desktop.ini", "Thumbs.db", ".git", ".gitignore", ".gitattributes", ".gitmodules");
    private static final List<String> NOISE_DIRS =
        List.of("__MACOSX/", ".git/", ".idea/", ".vscode/", ".svn/", "modmaker/");
    private static final String CONTENT = "content/";
    private static final String BUNDLES = "bundles/";

    /** Import budget: a crafted zip cannot exhaust memory or hang the import (zip-bomb guard). */
    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    /** How many refused entry names are remembered; a hostile archive has millions of them. */
    private static final int MAX_SKIPPED = 200;

    private ModPackageReader() {
    }

    public static ModPackage read(Path source) throws IOException {
        return read(source, ImportProgress.NONE);
    }

    public static ModPackage read(Path source, ImportProgress progress) throws IOException {
        if (Files.isDirectory(source)) {
            return readFolder(source, progress);
        }
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip") || name.endsWith(".jar")) {
            return readZip(source, progress);
        }
        throw new IOException("Not a mod folder or zip: " + source);
    }

    private static ModPackage readFolder(Path root, ImportProgress progress) throws IOException {
        ModPackage mod = new ModPackage();
        mod.sourceLabel = root.toAbsolutePath().toString();
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> dirs = new ArrayList<>();
        // Two passes: enumerate first, then read bytes. A single walk that read every file as it
        // went could not know the file count up front, so the UI could only show an indeterminate
        // bar; the enumeration pass is cheap (one stat per entry) and makes READING measurable.
        List<String> fileNames = new ArrayList<>();
        List<Path> filePaths = new ArrayList<>();
        // The same budget the zip path enforces: a folder import (and every project open) reads
        // whole files into memory, so an over-large tree must be refused here too instead of
        // exhausting the heap while the identical payload as a zip would be rejected.
        long totalBytes = 0;
        int entries = 0;
        try (var walk = Files.walk(root)) {
            var iterator = walk.iterator();
            while (true) {
                Path path;
                try {
                    if (!iterator.hasNext()) {
                        break;
                    }
                    path = iterator.next();
                } catch (java.io.UncheckedIOException failure) {
                    throw failure.getCause();
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.isEmpty()) {
                    continue;
                }
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("the folder holds more than " + MAX_ENTRIES
                        + " entries and is refused as a possible zip bomb");
                }
                if (Files.isDirectory(path)) {
                    dirs.add(relative);
                } else if (Files.isRegularFile(path)) {
                    long size = Files.size(path);
                    if (size > MAX_ENTRY_BYTES) {
                        throw new IOException("'" + relative + "' exceeds "
                            + MAX_ENTRY_BYTES / (1024 * 1024) + " MiB and is refused");
                    }
                    totalBytes += size;
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw new IOException("the folder unpacks to more than "
                            + MAX_TOTAL_BYTES / (1024 * 1024 * 1024) + " GiB and is refused as a"
                            + " possible zip bomb");
                    }
                    fileNames.add(relative);
                    filePaths.add(path);
                }
            }
        }
        int total = fileNames.size();
        progress.stageStarted(ImportProgress.Stage.READING, total);
        for (int i = 0; i < total; i++) {
            progress.checkCancelled();
            files.put(fileNames.get(i), Files.readAllBytes(filePaths.get(i)));
            int done = i + 1;
            if (ImportProgress.worthReporting(done, total)) {
                progress.progressed(ImportProgress.Stage.READING, done, total);
            }
        }
        mod.emptyDirs.addAll(emptyDirs(dirs, files.keySet()));
        classify(mod, files);
        return mod;
    }

    private static ModPackage readZip(Path zip, ImportProgress progress) throws IOException {
        ModPackage mod = new ModPackage();
        mod.sourceLabel = zip.toAbsolutePath().toString();
        mod.fromZip = true;

        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, ZipEntry> entriesByName = new LinkedHashMap<>();
        List<String> dirs = new ArrayList<>();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = archive.entries();
            int seen = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                // Every entry counts toward the budget, whatever it turns into: directory entries
                // and refused traversal names used to skip the check entirely, so a flood of
                // empty directories was accepted and held in memory.
                if (++seen > MAX_ENTRIES) {
                    throw new IOException("the archive holds more than " + MAX_ENTRIES
                        + " entries and is refused as a possible zip bomb");
                }
                // Zip-slip at the source: an entry whose path escapes the package root (../, an
                // absolute or rooted path, a drive letter) is skipped before it can be used.
                if (isUnsafeEntryName(name)) {
                    if (mod.skipped.size() < MAX_SKIPPED) {
                        mod.skipped.add(name + " (path escapes the package)");
                    }
                    continue;
                }
                if (entry.isDirectory()) {
                    dirs.add(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
                    continue;
                }
                names.add(name);
                // Keep the enumerated entry: archives written by Windows tools store names with
                // backslashes, and archive.getEntry(normalized) would then miss and silently drop
                // the file.
                entriesByName.put(name, entry);
            }

            String prefix = singleRootPrefix(names);
            mod.rootPrefix = prefix;

            long totalBytes = 0;
            int total = names.size();
            progress.stageStarted(ImportProgress.Stage.READING, total);
            for (int i = 0; i < total; i++) {
                progress.checkCancelled();
                if (ImportProgress.worthReporting(i + 1, total)) {
                    progress.progressed(ImportProgress.Stage.READING, i + 1, total);
                }
                String name = names.get(i);
                if (!prefix.isEmpty() && !name.startsWith(prefix)) {
                    continue;
                }
                String relative = prefix.isEmpty() ? name : name.substring(prefix.length());
                if (relative.isEmpty() || relative.endsWith("/")) {
                    continue;
                }
                ZipEntry entry = entriesByName.get(name);
                if (entry == null) {
                    continue;
                }
                // Bounded read so a lying or huge entry cannot exhaust the heap. One byte past the
                // limit is read on purpose: readNBytes(n) can never return more than n, so the old
                // `data.length > MAX_ENTRY_BYTES` check was dead code and an over-sized entry was
                // silently truncated to 256 MiB instead of being refused.
                try (InputStream stream = archive.getInputStream(entry)) {
                    byte[] data = stream.readNBytes((int) MAX_ENTRY_BYTES + 1);
                    if (data.length > MAX_ENTRY_BYTES) {
                        throw new IOException("zip entry '" + name + "' exceeds "
                            + MAX_ENTRY_BYTES / (1024 * 1024) + " MiB and is refused");
                    }
                    totalBytes += data.length;
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw new IOException("the archive unpacks to more than "
                            + MAX_TOTAL_BYTES / (1024 * 1024 * 1024) + " GiB and is refused"
                            + " as a possible zip bomb");
                    }
                    files.put(relative, data);
                }
            }
            // A zip built elsewhere may only carry directory entries for empty folders.
            dirs.removeIf(dir -> !prefix.isEmpty() && !dir.startsWith(prefix));
            List<String> stripped = new ArrayList<>();
            for (String dir : dirs) {
                String relative = prefix.isEmpty() ? dir : dir.substring(prefix.length());
                if (!relative.isEmpty()) {
                    stripped.add(relative);
                }
            }
            mod.emptyDirs.addAll(emptyDirs(stripped, files.keySet()));
        }
        classify(mod, files);
        return mod;
    }

    /** Directories that contain no file anywhere beneath them. */
    private static List<String> emptyDirs(List<String> directories, Set<String> filePaths) {
        Set<String> withFiles = new LinkedHashSet<>();
        for (String path : filePaths) {
            int slash = path.lastIndexOf('/');
            while (slash > 0) {
                withFiles.add(path.substring(0, slash));
                slash = path.lastIndexOf('/', slash - 1);
            }
        }
        List<String> empty = new ArrayList<>();
        for (String dir : new LinkedHashSet<>(directories)) {
            if (!withFiles.contains(dir) && !isNoise(dir + "/")) {
                empty.add(dir);
            }
        }
        return empty;
    }

    /**
     * Mods.resolveRoot descends into the only top-level folder when there is exactly one entry.
     * Returns the prefix to strip, e.g. "MyMod/", or an empty string.
     */
    static String singleRootPrefix(List<String> entryNames) {
        Set<String> tops = new LinkedHashSet<>();
        for (String name : entryNames) {
            int slash = name.indexOf('/');
            if (slash < 0) {
                return "";
            }
            tops.add(name.substring(0, slash));
            if (tops.size() > 1) {
                return "";
            }
        }
        if (tops.size() != 1) {
            return "";
        }
        String only = tops.iterator().next();
        return only + "/";
    }

    private static void classify(ModPackage mod, Map<String, byte[]> files) {
        // The meta file the game would pick: first existing name in its probe order.
        for (String candidate : ModMeta.META_FILES) {
            byte[] bytes = files.get(candidate);
            if (bytes != null) {
                mod.metaFile = candidate;
                // Kept verbatim, BOM included, so an untouched meta is written back byte for byte.
                // Every parser below strips the BOM itself (HjsonReader.read).
                mod.metaText = new String(bytes, StandardCharsets.UTF_8);
                try {
                    mod.meta = ModMetaCodec.fromJson(HjsonReader.readObject(mod.metaText));
                } catch (RuntimeException failure) {
                    mod.metaError = failure.getMessage();
                    mod.skipped.add(candidate + " (unreadable meta: " + failure.getMessage() + ")");
                }
                break;
            }
        }

        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = entry.getKey();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            if (isNoise(path)) {
                mod.skipped.add(path);
                continue;
            }
            if (path.equals(mod.metaFile)) {
                continue;
            }

            if (path.startsWith(CONTENT)) {
                ModPackage.SourceContent content = readContent(path, entry.getValue());
                if (content != null) {
                    mod.contents.add(content);
                    continue;
                }
            }
            if (path.startsWith(BUNDLES) && BundleCodec.isBundleFile(fileName)) {
                mod.bundleFiles.put(path, new String(entry.getValue(), StandardCharsets.UTF_8));
                continue;
            }
            mod.assetFiles.put(path, entry.getValue());
        }
    }

    /** Returns null when the file is not a content json, so it falls through to assets. */
    private static ModPackage.SourceContent readContent(String path, byte[] bytes) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ext.equals("json") && !ext.equals("hjson")) {
            return null;
        }
        String rest = path.substring(CONTENT.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String folder = rest.substring(0, slash);
        ContentType type = ContentType.fromFolderLoose(folder);
        if (type == null) {
            return null;
        }

        ModPackage.SourceContent content = new ModPackage.SourceContent();
        content.path = path;
        content.name = dot < 0 ? fileName : fileName.substring(0, dot);
        content.ext = ext;
        content.type = type;
        // Verbatim text (BOM included) so untouched files round-trip byte for byte; parsing strips.
        content.text = new String(bytes, StandardCharsets.UTF_8);
        try {
            // The game escapes every '#' in .json files (ContentParser.parse, v160.5) so a hash is
            // a literal there; in .hjson files it starts a comment anywhere.
            JsonObject object = ext.equals("json")
                ? HjsonReader.readJsonObject(content.text)
                : HjsonReader.readObject(content.text);
            for (Map.Entry<String, com.google.gson.JsonElement> field : object.entrySet()) {
                content.fields.put(field.getKey(), field.getValue());
            }
        } catch (RuntimeException failure) {
            content.parseError = failure.getMessage();
        }
        return content;
    }

    /**
     * True for entry names that could escape the package root when written to disk: parent
     * segments, absolute/rooted paths or Windows drive letters. No legitimate mod file needs any
     * of these; skipping them closes zip-slip at the source.
     */
    private static boolean isUnsafeEntryName(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.matches("(?i)^[a-z]:.*")) {
            return true;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.equals("..")) {
                return true;
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c < 0x20 || "<>:\"|?*".indexOf(c) >= 0) {
                    return true;
                }
            }
            char last = segment.charAt(segment.length() - 1);
            if (last == '.' || last == ' ') {
                return true;
            }
        }
        return false;
    }

    /**
     * True for OS/VCS junk and crash leftovers. Package-private so the golden tests apply exactly
     * the same filter to the fixture they walk on disk.
     */
    static boolean isNoise(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (NOISE_FILES.contains(fileName) || fileName.endsWith(ProjectIo.TEMP_SUFFIX)) {
            // A save writes through "<target>.mm-tmp" and moves it into place; a crash between the
            // two leaves it behind, and it must not be adopted as a mod asset (it would be pruned
            // never and shipped in builds).
            return true;
        }
        for (String dir : NOISE_DIRS) {
            if (path.startsWith(dir) || path.contains("/" + dir)) {
                return true;
            }
        }
        return false;
    }
}

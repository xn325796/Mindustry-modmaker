package dev.modmaker.core.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.BundleCodec;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.fmt.PropertiesFile;
import dev.modmaker.core.json.Json;
import dev.modmaker.core.json.JsonPath;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModMeta;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The project folder is a real mod folder plus an authoring directory:
 *
 * <pre>
 * &lt;project&gt;/mod.json          real meta file (verbatim until it is edited)
 * &lt;project&gt;/content/**         real content files (verbatim until they are edited)
 * &lt;project&gt;/bundles/**         real bundle files
 * &lt;project&gt;/sprites|scripts|... real assets
 * &lt;project&gt;/modmaker/project.json   board list, record ids, provenance
 * &lt;project&gt;/modmaker/boards/*.canvas JSON Canvas boards
 * </pre>
 *
 * <p>Because the mod files themselves carry the state, a project folder can be dropped straight into
 * the game's mods directory to test it, saving only rewrites what actually changed, and building is a
 * plain copy. Records that were never touched keep their exact original bytes.
 */
public final class ProjectIo {

    public static final String AUTHORING_DIR = "modmaker";
    public static final String PROJECT_FILE = "project.json";
    public static final String BOARDS_DIR = "boards";
    /**
     * Suffix of the temporary file a save writes before moving it into place. Anything carrying it
     * is a crash leftover: the reader treats it as noise and the exporter never ships it.
     */
    public static final String TEMP_SUFFIX = ".mm-tmp";

    private ProjectIo() {
    }

    // --- write ----------------------------------------------------------------------------------

    /** Materialises an imported package as a project folder, keeping every file as it was shipped. */
    public static void unpack(ModProject project, ModPackage pkg, Path root) throws IOException {
        unpack(project, pkg, root, ImportProgress.NONE);
    }

    public static void unpack(ModProject project, ModPackage pkg, Path root, ImportProgress progress)
        throws IOException {
        Files.createDirectories(root);

        String metaName = pkg.metaFile.isEmpty() ? "mod.hjson" : pkg.metaFile;
        project.metaFile = metaName;
        // Verbatim, BOM included; parsers strip the BOM themselves when they read it back.
        String metaText = pkg.metaText;
        if (metaText.isEmpty()) {
            project.metaText = Json.write(ModMetaCodec.toJson(project.meta)) + "\n";
        } else {
            project.metaText = metaText;
        }
        project.metaDirty = false;

        // Every branch below writes to a path derived from the untrusted package, so all of them
        // go through safeResolve - not just assets. (Zip-slip: content/../../evil.hjson.)
        int total = pkg.contents.size() + pkg.bundleFiles.size() + pkg.assetFiles.size()
            + pkg.emptyDirs.size();
        progress.stageStarted(ImportProgress.Stage.WRITING, total);
        int done = 0;
        for (ModPackage.SourceContent content : pkg.contents) {
            writingTick(progress, ++done, total);
            Path target = safeResolve(root, content.path);
            if (target == null) {
                pkg.skipped.add(content.path + " (path escapes the project folder)");
                continue;
            }
            AtomicFiles.writeText(target, content.text);
        }
        for (Map.Entry<String, String> bundle : pkg.bundleFiles.entrySet()) {
            writingTick(progress, ++done, total);
            Path target = safeResolve(root, bundle.getKey());
            if (target == null) {
                pkg.skipped.add(bundle.getKey() + " (path escapes the project folder)");
                continue;
            }
            AtomicFiles.writeText(target, bundle.getValue());
            String locale = bundleLocale(bundle.getKey());
            project.strings.bundleRaw.put(locale, bundle.getValue());
        }
        for (Map.Entry<String, byte[]> asset : pkg.assetFiles.entrySet()) {
            writingTick(progress, ++done, total);
            Path target = safeResolve(root, asset.getKey());
            if (target == null) {
                pkg.skipped.add(asset.getKey() + " (path escapes the project folder)");
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, asset.getValue());
        }
        // Empty directories are part of the package too (an empty sprites-override/, for instance).
        for (String dir : pkg.emptyDirs) {
            writingTick(progress, ++done, total);
            Path target = safeResolve(root, dir);
            if (target == null) {
                pkg.skipped.add(dir + " (path escapes the project folder)");
                continue;
            }
            Files.createDirectories(target);
        }
        project.root = root;
        save(project, root);
    }

    /** Cancel-check plus one throttled WRITING tick per package file. */
    private static void writingTick(ImportProgress progress, int done, int total) {
        progress.checkCancelled();
        if (ImportProgress.worthReporting(done, total)) {
            progress.progressed(ImportProgress.Stage.WRITING, done, total);
        }
    }

    /** Resolves a path from an untrusted package, or null when it would escape the project root. */
    public static Path safeResolve(Path root, String relative) {
        if (relative == null || relative.isEmpty()) {
            return null;
        }
        try {
            Path target = root.resolve(relative).normalize();
            Path normalizedRoot = root.normalize();
            return target.startsWith(normalizedRoot) ? target : null;
        } catch (java.nio.file.InvalidPathException invalid) {
            return null;
        }
    }

    /**
     * Syncs changed state to disk in one go. Kept for callers that already run on the thread owning
     * the model (the exit flow, project creation); background saves use {@link #plan} and
     * {@link #write} instead, so the model is never read from a worker thread while the user edits.
     */
    public static synchronized void save(ModProject project, Path root) throws IOException {
        write(plan(project, root));
    }

    /**
     * Decides everything a save will do to disk: file contents, deletions and prunes. <b>Must run on
     * the thread that owns the model</b> - it reads the field trees and updates {@code raw}/{@code
     * dirty}/{@code rawPath}, so nothing else may be editing them concurrently. The returned plan is
     * the only input {@link #write} needs.
     */
    public static SavePlan plan(ModProject project, Path root) throws IOException {
        String previousMetaFileBefore = project.previousMetaFile;
        boolean metaDirtyBefore = project.metaDirty;
        try {
            SavePlan plan = new SavePlan(root, project);
            project.root = root;

            writeMeta(project, root, plan);
            Set<Path> expectedContent = writeContent(project, root, plan);
            plan.prune(root.resolve("content"), expectedContent, project.assetPaths(),
                Set.of(".json", ".hjson"));
            writeBundles(project, root, plan);
            writeAuthoringLayer(project, root, plan);
            return plan;
        } catch (IOException | RuntimeException failure) {
            // Planning already flipped model flags; put the meta commit state back so a later save
            // still deletes the stale previous meta file.
            project.previousMetaFile = previousMetaFileBefore;
            project.metaDirty = metaDirtyBefore;
            throw failure;
        }
    }

    /**
     * Carries out a plan. This half only touches the filesystem, so it may run on the worker thread
     * while the user keeps editing. Synchronized: the exit flow writes on the FX thread while the io
     * executor may be writing concurrently, and two writers must not race on the same .mm-temp files.
     */
    public static synchronized void write(SavePlan plan) throws IOException {
        Files.createDirectories(plan.root());
        for (WriteOp op : plan.writes()) {
            AtomicFiles.writeText(op.target(), op.text());
        }
        for (Path target : plan.deletes()) {
            Files.deleteIfExists(target);
        }
        for (PruneOp op : plan.prunes()) {
            pruneManaged(plan.root(), op.folder(), op.expected(), op.protectedPaths(),
                op.extensions());
        }
    }

    /** One file a plan will write. */
    private record WriteOp(Path target, String text) {
    }

    /** One managed folder a plan will prune, with the absolute paths that must survive it. */
    private record PruneOp(Path folder, Set<Path> expected, Set<String> protectedPaths,
        Set<String> extensions) {
    }

    /**
     * What a save will do, decided while the model could still be read. Splitting the two phases is
     * what removes the race: the io thread used to serialise {@code record.fields} while the FX
     * thread was editing them inside the same map.
     */
    public static final class SavePlan {
        private final Path root;
        private final ModProject project;
        private final List<WriteOp> writes = new ArrayList<>();
        private final List<Path> deletes = new ArrayList<>();
        private final List<PruneOp> prunes = new ArrayList<>();
        /**
         * The "unsaved" flags as they were before the plan ran, keyed by record so a record added or
         * removed while the write is in flight cannot make the rollback land on the wrong one.
         */
        private final Map<ContentRecord, Boolean> recordDirtyBefore = new LinkedHashMap<>();
        private final boolean metaDirtyBefore;
        private final Map<String, Boolean> bundleDirtyBefore;
        private final String previousMetaFileBefore;

        SavePlan(Path root, ModProject project) {
            this.root = root;
            this.project = project;
            for (ContentRecord record : project.contents) {
                recordDirtyBefore.put(record, record.dirty);
            }
            this.metaDirtyBefore = project.metaDirty;
            this.bundleDirtyBefore = new LinkedHashMap<>(project.strings.bundleDirty);
            this.previousMetaFileBefore = project.previousMetaFile;
        }

        public Path root() {
            return root;
        }

        void write(Path target, String text) {
            writes.add(new WriteOp(target, text));
        }

        void delete(Path target) {
            deletes.add(target);
        }

        void prune(Path folder, Set<Path> expected, Set<String> protectedPaths,
            Set<String> extensions) {
            prunes.add(new PruneOp(folder, expected, protectedPaths, extensions));
        }

        List<WriteOp> writes() {
            return writes;
        }

        List<Path> deletes() {
            return deletes;
        }

        List<PruneOp> prunes() {
            return prunes;
        }

        /**
         * Puts the "unsaved" flags back after a write that did not finish. Without this a failed save
         * would leave the model looking persisted: the next save would skip those files and the exit
         * guard would not warn, so the edits would silently never reach the disk. This touches model
         * state, so it must run on the thread that owns the model.
         */
        public void rollback() {
            for (Map.Entry<ContentRecord, Boolean> entry : recordDirtyBefore.entrySet()) {
                entry.getKey().dirty = entry.getValue();
            }
            project.metaDirty = metaDirtyBefore;
            project.previousMetaFile = previousMetaFileBefore;
            project.strings.bundleDirty.clear();
            project.strings.bundleDirty.putAll(bundleDirtyBefore);
        }
    }

    private static void writeMeta(ModProject project, Path root, SavePlan plan) throws IOException {
        String metaName = project.metaFile == null || project.metaFile.isEmpty()
            ? "mod.hjson"
            : project.metaFile;
        // Defense in depth: the meta name only ever comes from the fixed probe list or the
        // authoring layer (validated on open); anything else would be a corruption marker.
        if (!Arrays.asList(ModMeta.META_FILES).contains(metaName)) {
            metaName = "mod.hjson";
            project.metaDirty = true;
        }
        Path target = root.resolve(metaName);
        boolean hjson = metaName.toLowerCase(Locale.ROOT).endsWith(".hjson");
        String text = !project.metaDirty && project.metaText != null && !project.metaText.isEmpty()
            ? project.metaText
            : (hjson
                ? dev.modmaker.core.json.HjsonWriter.write(ModMetaCodec.toJson(project.meta))
                : Json.write(ModMetaCodec.toJson(project.meta))) + "\n";
        plan.write(target, text);
        project.metaText = text;
        project.metaDirty = false;
        // A format conversion renames the meta file; the old one must not linger at the root,
        // where the game would still probe it and a build would still copy it.
        if (project.previousMetaFile != null && !project.previousMetaFile.equals(metaName)) {
            plan.delete(root.resolve(project.previousMetaFile));
        }
        project.previousMetaFile = null;
    }

    private static Set<Path> writeContent(ModProject project, Path root, SavePlan plan)
        throws IOException {
        Set<Path> expected = new LinkedHashSet<>();
        for (ContentRecord record : project.contents) {
            if (record.isInline()) {
                continue;
            }
            boolean inlineChanged = project.contents.stream()
                .anyMatch(inline -> record.id.equals(inline.inlineOwnerId) && inline.dirty);
            String previousPath = record.rawPath;

            Path target = targetPath(root, record);
            // Defense in depth: two records resolving to one file would have the second overwrite
            // the first, losing its content and (on reopen) the record itself. Refuse loudly instead
            // of dropping data silently.
            if (!expected.add(target.toAbsolutePath().normalize())) {
                throw new IOException("two content records resolve to the same file ("
                    + root.relativize(target).toString().replace('\\', '/')
                    + "); rename one of them before saving");
            }
            String text;
            if (!record.dirty && record.raw != null) {
                // A nested record changed while the host itself did not: splice just that value into
                // the original bytes so the file's comments, blank lines and hand formatting survive.
                text = inlineChanged ? patchInlines(project, record) : record.raw;
                if (text == null) {
                    text = serialize(project, record);
                }
            } else {
                text = serialize(project, record);
            }
            plan.write(target, text);

            record.rawPath = root.relativize(target).toString().replace('\\', '/');
            record.raw = text;
            record.dirty = false;

            if (previousPath != null && !previousPath.equals(record.rawPath)) {
                // Case-insensitive filesystems: when only the case of the name changed, the previous
                // path resolves to the very file just written and must not be deleted.
                Path previous = root.resolve(previousPath);
                if (!sameFileOnDisk(previous, target)) {
                    plan.delete(previous);
                }
            }
        }
        project.contents.stream().filter(ContentRecord::isInline).forEach(record -> record.dirty = false);
        return expected;
    }

    /**
     * Where a record's file belongs. Content that came from a package keeps the folder it was
     * imported from (mods nest content in their own subfolders, and an edit must not move it);
     * renaming only changes the file name inside that folder.
     */
    private static Path targetPath(Path root, ContentRecord record) {
        if (record.rawPath == null) {
            return root.resolve(canonicalContentPath(record));
        }
        Path existing = root.resolve(record.rawPath);
        Path parent = existing.getParent();
        if (parent == null) {
            return root.resolve(canonicalContentPath(record));
        }
        Path target = parent.resolve(record.name + "." + record.ext);
        if (!target.normalize().startsWith(root.normalize())) {
            return root.resolve(canonicalContentPath(record));
        }
        return target;
    }

    /**
     * Writes only the locales that actually have a bundle on disk, plus any the editor touched.
     * A locale is never invented: a mod that ships only bundle_zh_CN.properties keeps exactly that
     * one file.
     */
    private static void writeBundles(ModProject project, Path root, SavePlan plan) throws IOException {
        Set<Path> expected = new LinkedHashSet<>();
        Set<String> locales = new LinkedHashSet<>(project.strings.bundleFiles.keySet());
        for (String locale : project.locales) {
            if (project.strings.isBundleDirty(locale)) {
                locales.add(locale);
            }
        }
        locales.addAll(project.strings.extra.keySet());

        for (String locale : locales) {
            String fileName = project.strings.bundleFiles.getOrDefault(locale, BundleCodec.fileNameOf(locale));
            Path target = root.resolve("bundles").resolve(fileName);
            String text;
            if (!project.strings.isBundleDirty(locale) && project.strings.bundleRaw.containsKey(locale)) {
                text = project.strings.bundleRaw.get(locale);
            } else {
                text = renderBundle(project, locale);
            }
            plan.write(target, text);
            project.strings.bundleRaw.put(locale, text);
            project.strings.bundleFiles.put(locale, fileName);
            project.strings.bundleDirty.put(locale, false);
            expected.add(target.toAbsolutePath().normalize());
        }
        plan.prune(root.resolve("bundles"), expected, project.assetPaths(), Set.of(".properties"));
    }

    /**
     * The bundle text for one locale: the file the mod shipped with only the edited lines rewritten,
     * so comments, blank lines, key order and formatting survive an edit (a hand-edited bundle is a
     * document, not a table). Locales with no shipped text are rendered from scratch as before.
     *
     * <p>A key loses its line only when the project stops defining it - deleted content, or a string
     * the author cleared - which is what rebuilding the bundle used to do to every key. A key the
     * shipped file itself defines with an empty value is left exactly as it is: the user did not
     * touch it, so the tool must not either.
     */
    private static String renderBundle(ModProject project, String locale) {
        String shipped = project.strings.bundleRaw.get(locale);
        PropertiesFile file = shipped == null ? new PropertiesFile() : PropertiesFile.parse(shipped);

        Map<String, String> wanted = new LinkedHashMap<>();
        project.strings.values.entrySet().stream()
            .filter(entry -> entry.getValue().containsKey(locale)
                && entry.getValue().get(locale) != null)
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> wanted.put(entry.getKey(), entry.getValue().get(locale)));
        Map<String, String> extras = project.strings.extra.get(locale);
        if (extras != null) {
            wanted.putAll(extras);
        }

        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            String shippedValue = file.values.get(entry.getKey());
            if (entry.getValue().isEmpty() && (shippedValue == null || !shippedValue.isEmpty())) {
                file.remove(entry.getKey());
                continue;
            }
            file.put(entry.getKey(), entry.getValue());
        }
        for (String key : new LinkedHashSet<>(file.values.keySet())) {
            if (!wanted.containsKey(key)) {
                file.remove(key);
            }
        }
        return file.write();
    }

    /**
     * Removes files this tool manages that no longer correspond to anything in the project.
     * Files that are assets of the project (unknown content folders, non-standard names) are never
     * touched, so nothing a mod ships can be lost by saving.
     */
    private static void pruneManaged(Path root, Path folder, Set<Path> expected,
        Set<String> protectedPaths, Set<String> extensions) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        Set<Path> expectedReal = realPaths(expected);
        try (Stream<Path> walk = Files.walk(folder)) {
            List<Path> candidates = walk.filter(Files::isRegularFile).toList();
            for (Path file : candidates) {
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
                if (!extensions.contains(extension)) {
                    continue;
                }
                if (folder.getFileName().toString().equals("bundles") && !BundleCodec.isBundleFile(name)) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (protectedPaths.contains(relative)) {
                    continue;
                }
                // Compare real paths so a file that only differs in case from the expected one
                // (case-insensitive filesystems) is recognised as the same file and kept.
                if (!expectedReal.contains(realPath(file))) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** True when two paths already point at one file (case-insensitive / symlink-safe check). */
    private static boolean sameFileOnDisk(Path a, Path b) {
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException failure) {
            return false;
        }
    }

    private static Set<Path> realPaths(Set<Path> paths) {
        Set<Path> real = new LinkedHashSet<>();
        for (Path path : paths) {
            Path resolved = realPath(path);
            if (resolved != null) {
                real.add(resolved);
            }
        }
        return real;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException failure) {
            return null;
        }
    }

    private static String serialize(ModProject project, ContentRecord record) {
        JsonObject object = mergedTree(project, record);
        for (ContentRecord inline : project.contents) {
            if (record.id.equals(inline.inlineOwnerId)) {
                inline.dirty = false;
            }
        }
        // The extension is a format decision: .hjson records are written in the hand-edited
        // dialect, everything else in canonical JSON.
        return writeTree(object, record) + "\n";
    }

    /**
     * The field tree this record's file should contain: its own fields with every nested record
     * merged back in at its path. Both a full re-serialisation and the text patch below are checked
     * against this, so the two can only ever agree on what the file means.
     */
    private static JsonObject mergedTree(ModProject project, ContentRecord record) {
        JsonObject object = new JsonObject();
        record.fields.forEach(object::add);
        for (ContentRecord inline : project.contents) {
            if (!record.id.equals(inline.inlineOwnerId)) {
                continue;
            }
            JsonObject inlineObject = new JsonObject();
            inline.fields.forEach(inlineObject::add);
            JsonPath.set(object, inline.inlinePath, inlineObject);
        }
        return object;
    }

    /**
     * Applies a change confined to nested records as a text edit on the host's original bytes, so a
     * bullet edit keeps every comment elsewhere in the turret file.
     *
     * <p>Returns null - the caller then re-serialises the whole record, which is lossy but always
     * correct - when a value cannot be located, or when the spliced text does not parse back to
     * {@link #mergedTree}: that is what makes a nested record added or removed fall back instead of
     * silently leaving the old value in place.
     */
    private static String patchInlines(ModProject project, ContentRecord record) {
        String text = record.raw;
        for (ContentRecord inline : project.contents) {
            if (!record.id.equals(inline.inlineOwnerId) || !inline.dirty) {
                continue;
            }
            JsonObject inlineObject = new JsonObject();
            inline.fields.forEach(inlineObject::add);
            text = spliceInline(text, inline, inlineObject, record);
            if (text == null) {
                return null;
            }
        }
        JsonElement reparsed = dev.modmaker.core.json.JsonTextPatch.parseOrNull(text);
        return mergedTree(project, record).equals(reparsed) ? text : null;
    }

    /**
     * Splices one nested record back into its host's original text.
     *
     * <p>A change confined to leaf values is applied field by field, so the bullet's own comments
     * survive as well as the host's. A change of shape - a member added or removed - cannot be
     * expressed as a value replacement, so it falls back to replacing the whole nested object, which
     * still keeps every comment outside it. Null means even that could not be located.
     */
    private static String spliceInline(String text, ContentRecord inline, JsonObject updated,
        ContentRecord record) {
        dev.modmaker.core.json.JsonTextPatch.Span span =
            dev.modmaker.core.json.JsonTextPatch.valueSpan(text, inline.inlinePath);
        if (span == null) {
            return null;
        }
        JsonElement previous =
            dev.modmaker.core.json.JsonTextPatch.parseOrNull(text.substring(span.start(), span.end()));
        List<String> paths = new ArrayList<>();
        List<JsonElement> values = new ArrayList<>();
        if (previous != null && leafChanges(previous, updated, "", paths, values)) {
            String patched = text;
            for (int i = 0; i < paths.size(); i++) {
                String path = paths.get(i).isEmpty()
                    ? inline.inlinePath
                    : inline.inlinePath + "." + paths.get(i);
                patched = dev.modmaker.core.json.JsonTextPatch.replace(patched, path,
                    writeNested(values.get(i), record));
                if (patched == null) {
                    return null;
                }
            }
            return patched;
        }
        return dev.modmaker.core.json.JsonTextPatch.replace(text, inline.inlinePath,
            writeNested(updated, record));
    }

    /**
     * Collects the value replacements that turn {@code before} into {@code after}. Returns false -
     * meaning a plain value splice cannot express the change - when a member or element was added or
     * removed at any level.
     */
    private static boolean leafChanges(JsonElement before, JsonElement after, String path,
        List<String> paths, List<JsonElement> values) {
        if (before != null && before.equals(after)) {
            return true;
        }
        if (before != null && after != null && before.isJsonObject() && after.isJsonObject()) {
            JsonObject oldObject = before.getAsJsonObject();
            JsonObject newObject = after.getAsJsonObject();
            if (!oldObject.keySet().equals(newObject.keySet())) {
                return false;
            }
            for (String key : newObject.keySet()) {
                if (!leafChanges(oldObject.get(key), newObject.get(key), JsonPath.child(path, key),
                    paths, values)) {
                    return false;
                }
            }
            return true;
        }
        if (before != null && after != null && before.isJsonArray() && after.isJsonArray()
            && before.getAsJsonArray().size() == after.getAsJsonArray().size()) {
            for (int i = 0; i < after.getAsJsonArray().size(); i++) {
                if (!leafChanges(before.getAsJsonArray().get(i), after.getAsJsonArray().get(i),
                    JsonPath.childIndex(path, i), paths, values)) {
                    return false;
                }
            }
            return true;
        }
        if (path.isEmpty()) {
            return false;
        }
        paths.add(path);
        values.add(after);
        return true;
    }

    /** Writes a tree in the dialect of the file it belongs to. */
    private static String writeTree(JsonObject object, ContentRecord record) {
        boolean hjson = record.ext != null && record.ext.equalsIgnoreCase("hjson");
        return hjson
            ? dev.modmaker.core.json.HjsonWriter.write(object)
            : Json.write(object);
    }

    /** Writes a value for splicing inside a file of that dialect: objects keep their braces. */
    private static String writeNested(JsonElement value, ContentRecord record) {
        boolean hjson = record.ext != null && record.ext.equalsIgnoreCase("hjson");
        return hjson
            ? dev.modmaker.core.json.HjsonWriter.writeNested(value)
            : Json.write(value);
    }

    /**
     * A board canvas file name is untrusted input from the authoring layer, so it must be a bare
     * file name: no separator, no drive letter, no parent segment, and a .canvas extension.
     * Without this, {@code "file": "../../mod.hjson"} in modmaker/project.json made the next save
     * overwrite the mod's own meta file, and an absolute path overwrote anything on disk.
     */
    static boolean isSafeBoardFile(String file) {
        if (file == null || file.isEmpty() || file.length() > 120) {
            return false;
        }
        if (file.contains("/") || file.contains("\\") || file.indexOf(':') >= 0
            || file.contains("..") || file.startsWith(".")) {
            return false;
        }
        if (NameRules.isWindowsReservedName(file)) {
            return false;
        }
        return file.toLowerCase(Locale.ROOT).endsWith(".canvas");
    }

    /** A board file name no other board of this project already uses. */
    private static String uniqueBoardFile(String preferred, Set<String> used) {
        String name = isSafeBoardFile(preferred) ? preferred : "main.canvas";
        if (!used.contains(name)) {
            return name;
        }
        int stem = name.length() - ".canvas".length();
        String prefix = name.substring(0, stem);
        for (int index = 2; ; index++) {
            String candidate = prefix + "-" + index + ".canvas";
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static void writeAuthoringLayer(ModProject project, Path root, SavePlan plan)
        throws IOException {
        Path authoring = root.resolve(AUTHORING_DIR);
        Path boardsDir = authoring.resolve(BOARDS_DIR);
        Files.createDirectories(boardsDir);

        JsonObject meta = new JsonObject();
        meta.addProperty("format", ModProject.FORMAT);
        meta.addProperty("schemaProfile", project.schemaProfile);
        meta.addProperty("importedFrom", project.importedFrom);
        meta.addProperty("metaFile", project.metaFile);

        JsonObject records = new JsonObject();
        for (ContentRecord record : project.contents) {
            String key = recordKey(project, record);
            if (key != null) {
                records.addProperty(key, record.id);
            }
        }
        meta.add("records", records);

        JsonArray boards = new JsonArray();
        Set<String> usedFiles = new LinkedHashSet<>();
        for (Board board : project.boards) {
            // Defense in depth for the same guard the read path applies: a name that escaped the
            // boards directory (or that another board already claimed) must never reach writeText,
            // and normalising it here keeps the model and project.json in agreement.
            String file = uniqueBoardFile(board.file, usedFiles);
            usedFiles.add(file);
            board.file = file;
            JsonObject entry = new JsonObject();
            entry.addProperty("id", board.id);
            entry.addProperty("name", board.name);
            entry.addProperty("file", file);
            JsonObject viewport = new JsonObject();
            viewport.addProperty("x", board.viewport.x);
            viewport.addProperty("y", board.viewport.y);
            viewport.addProperty("zoom", board.viewport.zoom);
            entry.add("viewport", viewport);
            if (board.edgeStyle != null && !board.edgeStyle.isBlank()
                && !"bezier".equals(board.edgeStyle)) {
                entry.addProperty("edgeStyle", board.edgeStyle);
            }
            boards.add(entry);
            plan.write(boardsDir.resolve(file), Json.write(JsonCanvas.toCanvas(project, board)) + "\n");
        }
        meta.add("boards", boards);
        plan.write(authoring.resolve(PROJECT_FILE), Json.write(meta) + "\n");
    }

    // --- read -----------------------------------------------------------------------------------

    /** Opens a project folder: content comes from the real mod files, boards from modmaker/. */
    public static ModProject open(Path root, SchemaRegistry registry) throws IOException {
        ModPackage pkg = ModPackageReader.read(root);
        ModProject project = new ModImporter(registry).importPackage(pkg).project();
        project.root = root;

        Path projectFile = root.resolve(AUTHORING_DIR).resolve(PROJECT_FILE);
        if (!Files.exists(projectFile)) {
            return project;
        }
        try {
            readAuthoringLayer(project, root, projectFile);
        } catch (IOException | RuntimeException failure) {
            // The authoring layer is regenerable; a corrupt project.json or board must not make the
            // real mod unopenable. Keep the imported project (which has its own board) and report.
            project.authoringWarning = "authoring layer ignored: " + failure.getMessage();
        }
        return project;
    }

    /** Reads and applies the authoring layer (schema profile, meta adoption, ids, boards). */
    private static void readAuthoringLayer(ModProject project, Path root, Path projectFile)
        throws IOException {
        JsonObject meta = Json.parseObject(Json.readFile(projectFile));

        String schemaProfile = text(meta, "schemaProfile");
        if (schemaProfile != null) {
            project.schemaProfile = schemaProfile;
        }
        String importedFrom = text(meta, "importedFrom");
        if (importedFrom != null) {
            project.importedFrom = importedFrom;
        }
        String recordedMeta = text(meta, "metaFile");
        if (recordedMeta != null && !recordedMeta.isEmpty()) {
            String probed = project.metaFile;
            // Only adopt the recorded name if it still exists and is a name the game probes;
            // an externally renamed meta must not resurrect the old file on the next save.
            if (Arrays.asList(ModMeta.META_FILES).contains(recordedMeta)
                && Files.isRegularFile(root.resolve(recordedMeta))) {
                if (!recordedMeta.equals(probed)) {
                    project.previousMetaFile = probed;
                    project.metaText = Files.readString(root.resolve(recordedMeta), StandardCharsets.UTF_8);
                    project.metaDirty = false;
                    try {
                        project.meta = ModMetaCodec.fromJson(
                            dev.modmaker.core.json.HjsonReader.readObject(project.metaText));
                        project.metaParseFailed = false;
                    } catch (RuntimeException failure) {
                        // Keep the bytes; a conversion must not replace them with empty metadata.
                        project.metaParseFailed = true;
                    }
                }
                project.metaFile = recordedMeta;
            }
        }

        // Restore record ids so saved node positions and edges still line up. File-backed records
        // are restored first and their old->new mapping is applied to inline links.
        JsonObject records = object(meta, "records");
        if (records != null) {
            Map<String, String> remapped = new LinkedHashMap<>();
            for (ContentRecord record : project.contents) {
                if (record.isInline() || record.rawPath == null) {
                    continue;
                }
                String oldId = text(records, record.rawPath);
                if (oldId != null) {
                    remapped.put(record.id, oldId);
                }
            }
            for (ContentRecord record : project.contents) {
                if (record.isInline()) {
                    record.inlineOwnerId = remapped.getOrDefault(record.inlineOwnerId, record.inlineOwnerId);
                }
            }
            for (ContentRecord record : project.contents) {
                String key = recordKey(project, record);
                if (key != null) {
                    String id = text(records, key);
                    if (id != null) {
                        record.id = id;
                    }
                }
            }
        }

        JsonArray boards = array(meta, "boards");
        if (boards != null && !boards.isEmpty()) {
            List<Board> loaded = new ArrayList<>();
            Set<String> usedFiles = new LinkedHashSet<>();
            for (JsonElement element : boards) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String recorded = text(entry, "file");
                // The authoring file is user-editable text, so this name is untrusted input on the
                // same footing as a zip entry: an unvalidated "../../mod.hjson" here made the next
                // save overwrite the mod's own meta file.
                String file = isSafeBoardFile(recorded)
                    ? recorded
                    : uniqueBoardFile("main.canvas", usedFiles);
                file = uniqueBoardFile(file, usedFiles);
                usedFiles.add(file);
                Path canvasFile = root.resolve(AUTHORING_DIR).resolve(BOARDS_DIR).resolve(file);
                String id = text(entry, "id");
                if (id == null) {
                    id = dev.modmaker.core.fmt.Ids.uid("b");
                }
                Board board = Files.exists(canvasFile)
                    ? JsonCanvas.fromCanvas(Json.readFile(canvasFile), file, id)
                    : new Board();
                board.file = file;
                String name = text(entry, "name");
                if (name != null && !name.isBlank()) {
                    board.name = name;
                }
                JsonObject viewport = object(entry, "viewport");
                if (viewport != null) {
                    board.viewport.x = number(viewport, "x", board.viewport.x);
                    board.viewport.y = number(viewport, "y", board.viewport.y);
                    double zoom = number(viewport, "zoom", board.viewport.zoom);
                    board.viewport.zoom = zoom > 0 ? zoom : 1;
                }
                String edgeStyle = text(entry, "edgeStyle");
                if (edgeStyle != null && !edgeStyle.isBlank()) {
                    board.edgeStyle = edgeStyle;
                }
                loaded.add(board);
            }
            project.boards = loaded;
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static double number(JsonObject object, String key, double fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            double parsed = value.getAsDouble();
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    private static String canonicalContentPath(ContentRecord record) {
        return "content/" + record.type.folderName() + "/" + record.name + "." + record.ext;
    }

    private static String bundleLocale(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String locale = BundleCodec.localeOf(fileName);
        return locale.isEmpty() ? "en" : locale;
    }

    /**
     * Identity of a record in the authoring layer: its file path, or for nested content the host's
     * path plus the position inside it.
     */
    private static String recordKey(ModProject project, ContentRecord record) {
        if (record.isInline()) {
            ContentRecord owner = project.contentById(record.inlineOwnerId);
            return owner == null || owner.rawPath == null
                ? null
                : owner.rawPath + "#" + record.inlinePath;
        }
        return record.rawPath;
    }
}

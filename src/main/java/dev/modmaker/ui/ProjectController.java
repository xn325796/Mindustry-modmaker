package dev.modmaker.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.Ids;
import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.fmt.PlanetTemplates;
import dev.modmaker.core.fmt.ReferenceOps;
import dev.modmaker.core.fmt.ReferenceRewrite;
import dev.modmaker.core.io.AutoLayout;
import dev.modmaker.core.io.ExportReport;
import dev.modmaker.core.io.ImportProgress;
import dev.modmaker.core.io.ImportReport;
import dev.modmaker.core.io.LinkGraph;
import dev.modmaker.core.io.ProjectFactory;
import dev.modmaker.core.io.ProjectIo;
import dev.modmaker.core.json.JsonPath;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.CanvasNode;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.pipeline.BuildPipeline;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;
import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.core.validate.Issue;

import javafx.application.Platform;
import javafx.geometry.Point2D;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Everything the UI does to a project in one place: open, import, edit, save, validate, build.
 *
 * <p>Views subscribe to change notifications instead of talking to the core directly, so the order of
 * a save, a validation run and a repaint stays consistent.
 *
 * <p>It is a facade, not the whole implementation: the undo history lives in {@link UndoManager},
 * the selected record in {@link SelectionModel}, cached/background findings in
 * {@link ValidationSession}, and the shared load/validate/save/export flow in
 * {@link dev.modmaker.core.pipeline.BuildPipeline}. What stays here is the project lifecycle, the
 * edit commands and the notification fan-out.
 */
public final class ProjectController {

    private final SchemaRegistry registry;
    /** Stateless read/import/build orchestration, split out of this class. */
    private final ProjectActions actions;

    /**
     * The shared validation state: one cached pass serves every pane that needs findings, and a
     * background pass (when the shell installs a worker) keeps typing from running the whole rule set
     * on the FX thread. See {@link ValidationSession}.
     */
    private final ValidationSession validation;

    private ModProject project;
    private Board board;

    /** Which record the panes show; selection is its own tiny state machine. */
    private final SelectionModel selection = new SelectionModel();

    private final List<Runnable> projectListeners = new ArrayList<>();
    private Consumer<String> logSink = message -> {
    };

    /** The undo/redo history, separated from the edit commands that fill it. */
    private final UndoManager history = new UndoManager();

    /**
     * Asked before an import unpacks over a non-empty project folder; returning false cancels.
     * Defaults to approving so command-line runs stay non-interactive.
     */
    private Predicate<Path> overwriteApprover = path -> true;

    /**
     * Experimental: when on, the inspector's class dropdown only offers classes in the same branch
     * of the hierarchy as the current one, so a battery cannot be turned into an item bridge.
     */
    private boolean adaptiveClasses = true;

    /**
     * Drift between the board and the records as found when the project was loaded - cards whose
     * content is gone, references with no link, links that no longer match a field. Kept so a stale
     * authoring layer is reported instead of being accepted silently; a rebuild refreshes it.
     */
    private LinkGraph.Diff canvasDrift = new LinkGraph.Diff(0, 0, 0);

    /** One reversible user action. redo() re-applies what the user did. */
    public interface Command extends UndoManager.Command {
    }

    /**
     * The "this has to be written on the next save" flags one command flips: the dirty flag of the
     * records it touches, every locale's bundle flag - and its raw text - plus the board and meta
     * flags. Captured before the command runs and again right after it, so undo can put the project
     * back exactly as clean as it was (an undone edit that leaves the project dirty makes the next
     * save re-serialise an untouched file, silently dropping its comments) and redo can re-arm the
     * same flags.
     *
     * <p>A flag is never cleared behind the bytes it guards: when a save ran in between, the file on
     * disk holds the command's own result, so the record/bundle still has to be regenerated.
     */
    private final class DirtyFlags {
        private final List<ContentRecord> records = new ArrayList<>();
        private final List<String> rawSeen = new ArrayList<>();
        private final Map<String, String> bundleRawSeen = new LinkedHashMap<>();
        private boolean[] recordDirtyBefore;
        private boolean[] recordDirtyAfter;
        private Map<String, Boolean> bundlesBefore;
        private Map<String, Boolean> bundlesAfter;
        private boolean boardsDirtyBefore;
        private boolean boardsDirtyAfter;
        private boolean metaDirtyBefore;
        private boolean metaDirtyAfter;

        DirtyFlags(ContentRecord... watched) {
            for (ContentRecord record : watched) {
                if (record != null && !records.contains(record)) {
                    records.add(record);
                    rawSeen.add(record.raw);
                }
            }
            bundleRawSeen.putAll(project.strings.bundleRaw);
            recordDirtyBefore = recordFlags();
            bundlesBefore = new LinkedHashMap<>(project.strings.bundleDirty);
            boardsDirtyBefore = project.boardsDirty;
            metaDirtyBefore = project.metaDirty;
        }

        /** Records what the command left behind; call once, right after it first ran. */
        void captureAfter() {
            recordDirtyAfter = recordFlags();
            bundlesAfter = new LinkedHashMap<>(project.strings.bundleDirty);
            boardsDirtyAfter = project.boardsDirty;
            metaDirtyAfter = project.metaDirty;
        }

        /** Puts every watched flag back the way it was before the command ran. */
        void restoreBefore() {
            restore(recordDirtyBefore, bundlesBefore, boardsDirtyBefore, metaDirtyBefore);
        }

        /** Re-arms the flags the command set, so a redo is written on the next save. */
        void restoreAfter() {
            restore(recordDirtyAfter, bundlesAfter, boardsDirtyAfter, metaDirtyAfter);
        }

        private boolean[] recordFlags() {
            boolean[] flags = new boolean[records.size()];
            for (int i = 0; i < flags.length; i++) {
                flags[i] = records.get(i).dirty;
            }
            return flags;
        }

        private void restore(boolean[] recordFlags, Map<String, Boolean> bundleFlags,
            boolean boardsDirty, boolean metaDirty) {
            for (int i = 0; i < records.size(); i++) {
                ContentRecord record = records.get(i);
                // A save since the command refreshed the file: its bytes are the command's result
                // now, so the record must be regenerated even when it was clean before.
                record.dirty = recordFlags[i] || !Objects.equals(record.raw, rawSeen.get(i));
            }
            for (String locale : new ArrayList<>(project.strings.bundleDirty.keySet())) {
                boolean dirty = bundleFlags.getOrDefault(locale, false)
                    || !Objects.equals(project.strings.bundleRaw.get(locale), bundleRawSeen.get(locale));
                project.strings.bundleDirty.put(locale, dirty);
            }
            project.boardsDirty = boardsDirty;
            project.metaDirty = metaDirty;
        }
    }

    /** Applies one field value; the shared implementation of undo and redo. */
    private static final class FieldCommand implements Command {
        private final ContentRecord record;
        private final String field;
        private final JsonElement oldValue;
        private JsonElement newValue;
        private final DirtyFlags flags;

        FieldCommand(ContentRecord record, String field, JsonElement oldValue, JsonElement newValue,
            DirtyFlags flags) {
            this.record = record;
            this.field = field;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.flags = flags;
        }

        boolean sameTarget(FieldCommand other) {
            return record == other.record && field.equals(other.field);
        }

        private void apply(JsonElement value) {
            if (value == null) {
                record.fields.remove(field);
            } else {
                record.fields.put(field, value);
            }
            record.dirty = true;
        }

        @Override
        public void undo() {
            apply(oldValue);
            flags.restoreBefore();
        }

        @Override
        public void redo() {
            flags.restoreAfter();
            apply(newValue);
        }
    }

    /** Applies one bundle string; quick consecutive edits of the same cell coalesce. */
    private final class StringCommand implements Command {
        private final String key;
        private final String locale;
        private final String oldValue;
        private String newValue;
        private final DirtyFlags flags;

        StringCommand(String key, String locale, String oldValue, String newValue, DirtyFlags flags) {
            this.key = key;
            this.locale = locale;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.flags = flags;
        }

        boolean sameTarget(StringCommand other) {
            return key.equals(other.key) && locale.equals(other.locale);
        }

        private void apply(String value) {
            project.strings.put(key, locale, value);
            project.strings.markBundleDirty(locale);
        }

        @Override
        public void undo() {
            apply(oldValue);
            flags.restoreBefore();
        }

        @Override
        public void redo() {
            flags.restoreAfter();
            apply(newValue);
        }
    }

    public ProjectController(SchemaRegistry registry) {
        this.registry = registry;
        this.actions = new ProjectActions(registry);
        this.validation = new ValidationSession(registry, () -> project);
    }

    public SchemaRegistry registry() {
        return registry;
    }

    public ModProject project() {
        return project;
    }

    public Board board() {
        return board;
    }

    public ContentRecord selected() {
        return selection.selected();
    }

    public void onProjectChanged(Runnable listener) {
        projectListeners.add(listener);
    }

    public void onSelectionChanged(Consumer<ContentRecord> listener) {
        selection.onChange(listener);
    }

    /** Lets the shell validate on a worker thread; without it validation stays synchronous. */
    public void useValidationWorker(java.util.concurrent.Executor worker) {
        validation.useWorker(worker);
    }

    /** The findings computed for the current model, computing them on this thread if needed. */
    public List<Issue> validateQuietly() {
        requireProject();
        return validation.current();
    }

    /** The last findings without ever computing: what the live panes render. */
    public List<Issue> latestIssues() {
        return validation.latest();
    }

    /** Invoked (on the FX thread) after a background validation pass published new findings. */
    public void onValidationRefreshed(Runnable listener) {
        validation.onRefreshed(listener);
    }

    /** True while a background validation pass is queued or running. */
    public boolean validationPending() {
        return validation.pending();
    }

    /**
     * Starts a background validation pass. Must be called on the FX thread: the model snapshot the
     * worker reads is taken there.
     */
    public void refreshValidationAsync() {
        validation.refreshAsync();
    }

    /** A snapshot of the model for read-only work on a worker thread. */
    public ModProject snapshotProject() {
        requireProject();
        return project.snapshot();
    }

    public void setLogSink(Consumer<String> sink) {
        this.logSink = sink;
    }

    /** Sets the callback consulted before an import would overwrite a non-empty project folder. */
    public void setOverwriteApprover(Predicate<Path> approver) {
        this.overwriteApprover = approver == null ? path -> true : approver;
    }

    public void log(String message) {
        logSink.accept(message);
    }

    // --- project lifecycle ----------------------------------------------------------------------

    public ModProject createProject(String modName, Path root) throws IOException {
        project = ProjectFactory.create(modName, root);
        board = project.boards.isEmpty() ? null : project.boards.get(0);
        selection.clear();
        canvasDrift = new LinkGraph.Diff(0, 0, 0);
        clearHistory();
        validation.reset();
        log("new project: " + root);
        fireProjectChanged();
        return project;
    }

    /**
     * Reads a project folder without publishing it. This is the expensive half (package read, content
     * parsing) and it only touches objects no view can see yet, so the io thread may run it; the
     * result is handed to {@link #publishProject} on the thread that owns the model.
     */
    public ModProject readProject(Path root) throws IOException {
        return actions.readProject(root);
    }

    /** Publishes a freshly read project. Must run on the FX thread, which owns the model. */
    public void publishProject(ModProject loaded, Path root) {
        project = loaded;
        board = project.boards.isEmpty() ? null : project.boards.get(0);
        selection.clear();
        clearHistory();
        validation.reset();
        log("opened project: " + root + " (" + project.contents.size() + " records, "
            + project.assets.size() + " assets)");
        if (loaded.authoringWarning != null) {
            log("  warn: " + loaded.authoringWarning);
        }
        // The authoring layer is user-editable text and can be stale relative to the real mod files
        // (content added or deleted outside the tool, an old canvas). Check it once on load instead
        // of silently showing a canvas that no longer mirrors the records.
        canvasDrift = canvasDiff();
        if (!canvasDrift.clean()) {
            log("canvas does not match the records (" + canvasDrift.summary()
                + "); use View > Rebuild Links to resync");
        }
        fireProjectChanged();
    }

    /** Reads and publishes in one call, for callers that already own the model. */
    public ModProject openProject(Path root) throws IOException {
        ModProject loaded = readProject(root);
        publishProject(loaded, root);
        return loaded;
    }

    /** A package read off disk and unpacked, waiting to be published on the model's thread. */
    public ProjectActions.ImportResult readImport(Path source, Path destinationRoot)
        throws IOException {
        return actions.readImport(source, destinationRoot, overwriteApprover, this::log);
    }

    /**
     * Reads a mod package (zip or folder) and unpacks it into a project folder, without publishing
     * it. The project it builds is not reachable from a view yet, so the io thread may run this.
     */
    public ProjectActions.ImportResult readImport(Path source, Path destinationRoot,
        ImportProgress progress) throws IOException {
        return actions.readImport(source, destinationRoot, progress, overwriteApprover, this::log);
    }

    /** Publishes an unpacked package and reports what it held. Must run on the FX thread. */
    public void publishImport(ProjectActions.ImportResult imported, Path source) {
        project = imported.project();
        board = project.boards.isEmpty() ? null : project.boards.get(0);
        selection.clear();
        canvasDrift = new LinkGraph.Diff(0, 0, 0);
        clearHistory();
        validation.reset();
        ImportReport report = imported.report();
        log("imported " + source);
        log("  " + report.summary());
        if (!report.skipped.isEmpty()) {
            log("  skipped " + report.skipped.size() + " file(s) that are not content:");
            report.skipped.stream().limit(10).forEach(skipped -> log("    " + skipped));
            if (report.skipped.size() > 10) {
                log("    ..." + (report.skipped.size() - 10) + " more");
            }
        }
        if (!report.parseFailures.isEmpty()) {
            log("  " + report.parseFailures.size()
                + " content file(s) failed to parse and were kept as raw text:");
            report.parseFailures.keySet().stream().limit(10)
                .forEach(failure -> log("    " + failure));
        }
        report.warnings.stream().limit(20).forEach(warning -> log("  warn: " + warning));
        fireProjectChanged();
    }

    /** Reads, unpacks and publishes in one call, for callers that already own the model. */
    public ModProject importPackage(Path source, Path destinationRoot) throws IOException {
        ProjectActions.ImportResult imported = readImport(source, destinationRoot);
        publishImport(imported, source);
        return imported.project();
    }

    /**
     * Saves synchronously on the calling thread. Only for callers that already own the model (the
     * exit flow, tests); the shell's background save builds the plan on the FX thread via
     * {@link #planSave()} and writes it on its worker.
     */
    public void save() throws IOException {
        requireProject();
        ProjectIo.SavePlan plan = planSave();
        writeSavePlan(plan);
        markSaved();
        log("saved " + plan.root());
    }

    /**
     * Decides what a save will write. <b>Must run on the FX thread</b>, which owns the model: it
     * reads the field trees and updates {@code raw}/{@code dirty}, so an edit arriving mid-plan
     * either corrupts the snapshot or throws.
     */
    public ProjectIo.SavePlan planSave() throws IOException {
        requireProject();
        return ProjectIo.plan(project, project.root);
    }

    /** Puts the unsaved flags back after a write that did not finish; runs on the model's thread. */
    public void rollbackSave(ProjectIo.SavePlan plan) {
        plan.rollback();
    }

    /** The model half of a finished save: clears the flag the write phase cannot touch. */
    public void markSaved() {
        if (project != null) {
            project.boardsDirty = false;
        }
    }

    /**
     * Converts every content file and the meta file to the target format ("json" or "hjson") and
     * saves immediately, so the on-disk folder reflects the new format. Returns how many content
     * files were rewritten.
     */
    public int convertFormat(String ext) throws IOException {
        ConvertPlan plan = planConvert(ext);
        writeSavePlan(plan.save());
        afterConvert(plan);
        return plan.converted();
    }

    /**
     * The model half of a format conversion: flips the extensions and decides what to write. Must
     * run on the FX thread, like {@link #planSave()}.
     */
    public ConvertPlan planConvert(String ext) throws IOException {
        requireProject();
        int converted = dev.modmaker.core.io.FormatConverter.pendingCount(project, ext);
        dev.modmaker.core.io.FormatConverter.convert(project, ext);
        return new ConvertPlan(ProjectIo.plan(project, project.root), converted, ext);
    }

    /** The model half of a conversion whose files are already on disk: logs it, refreshes the views. */
    public void afterConvert(ConvertPlan plan) {
        log("converted content format to ." + plan.ext() + " (" + plan.converted() + " files)");
        fireProjectChanged();
    }

    /** A conversion's save plan plus the numbers its log line needs. */
    public record ConvertPlan(ProjectIo.SavePlan save, int converted, String ext) {
    }

    /** True when anything changed since the last save: records, bundles, metadata or boards. */
    public boolean isDirty() {
        if (project == null) {
            return false;
        }
        if (project.metaDirty || project.boardsDirty) {
            return true;
        }
        for (ContentRecord record : project.contents) {
            if (record.dirty) {
                return true;
            }
        }
        for (String locale : project.strings.bundleDirty.keySet()) {
            if (project.strings.isBundleDirty(locale)) {
                return true;
            }
        }
        return false;
    }

    // --- metadata -------------------------------------------------------------------------------

    /** Reads one modelled string field, for the meta editor to pre-fill. */
    public String metaString(String field) {
        requireProject();
        return switch (field) {
            case "displayName" -> project.meta.displayName;
            case "author" -> project.meta.author;
            case "description" -> project.meta.description;
            case "subtitle" -> project.meta.subtitle;
            case "version" -> project.meta.version;
            case "minGameVersion" -> project.meta.minGameVersion;
            default -> throw new IllegalArgumentException("unknown meta field: " + field);
        };
    }

    /** True for the modelled boolean meta fields ({@code hidden}). */
    public boolean metaFlag(String field) {
        requireProject();
        return switch (field) {
            case "hidden" -> project.meta.hidden;
            default -> throw new IllegalArgumentException("unknown meta flag: " + field);
        };
    }

    /** Sets a modelled string field, marking the meta dirty so it is re-serialised. Undoable. */
    public void setMetaString(String field, String value) {
        requireProject();
        String previous = metaString(field);
        String next = value == null ? "" : value;
        if (Objects.equals(previous, next)) {
            return;
        }
        DirtyFlags flags = new DirtyFlags();
        applyMetaString(field, next);
        project.metaDirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                applyMetaString(field, previous);
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                applyMetaString(field, next);
            }
        });
        log("set meta." + field);
        fireProjectChanged();
    }

    /** Sets a modelled boolean field, marking the meta dirty. Undoable. */
    public void setMetaFlag(String field, boolean value) {
        requireProject();
        boolean previous = metaFlag(field);
        if (previous == value) {
            return;
        }
        DirtyFlags flags = new DirtyFlags();
        applyMetaFlag(field, value);
        project.metaDirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                applyMetaFlag(field, previous);
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                applyMetaFlag(field, value);
            }
        });
        log("set meta." + field);
        fireProjectChanged();
    }

    /** Sets the {@code legacyCompatible} gate (kept in {@code meta.extra}). Undoable. */
    public void setLegacyCompatible(boolean value) {
        requireProject();
        boolean previous = project.meta.legacyCompatible();
        if (previous == value) {
            return;
        }
        DirtyFlags flags = new DirtyFlags();
        project.meta.setLegacyCompatible(value);
        project.metaDirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                project.meta.setLegacyCompatible(previous);
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                project.meta.setLegacyCompatible(value);
            }
        });
        log("set meta.legacyCompatible = " + value);
        fireProjectChanged();
    }

    private void applyMetaString(String field, String value) {
        switch (field) {
            case "displayName" -> project.meta.displayName = value;
            case "author" -> project.meta.author = value;
            case "description" -> project.meta.description = value;
            case "subtitle" -> project.meta.subtitle = value;
            case "version" -> project.meta.version = value;
            case "minGameVersion" -> project.meta.minGameVersion = value;
            default -> throw new IllegalArgumentException("unknown meta field: " + field);
        }
    }

    private void applyMetaFlag(String field, boolean value) {
        switch (field) {
            case "hidden" -> project.meta.hidden = value;
            default -> throw new IllegalArgumentException("unknown meta flag: " + field);
        }
    }

    // --- content --------------------------------------------------------------------------------

    /** Adds a content record of the given type and places it on the current board. */
    public ContentRecord addContent(ContentType type) {
        return addContent(type, null, null);
    }

    /** Adds content, placing its card at the given world position (or below the stack when null). */
    public ContentRecord addContent(ContentType type, Double worldX, Double worldY) {
        requireProject();
        String name = uniqueName(type);
        ContentRecord record = ContentRecord.create(Ids.uid("c"), type, name);

        String baseClass = registry.baseClassOf(type);
        if (baseClass != null && !baseClass.equals(type.defaultClass())) {
            // Only spell out "type" when it is not what the game would assume anyway.
            record.setClassName(baseClass);
        }
        // Templates prefill new records with vanilla default data (planets get serpulo's grid,
        // atmosphere and campaign flags) so they are complete instead of empty shells.
        com.google.gson.JsonObject template = PlanetTemplates.templateFor(type);
        if (template != null) {
            for (var entry : template.entrySet()) {
                record.fields.put(entry.getKey(), entry.getValue());
            }
            record.dirty = true;
        }
        // Captured before any of this reaches the project state, so undoing the "add" puts the
        // dirty flags back instead of leaving unsaved work behind.
        DirtyFlags flags = new DirtyFlags(record);
        project.contents.add(record);
        project.strings.put(stringKey(record, "name"), "en", name);
        project.strings.markBundleDirty("en");
        if (!project.locales.contains("en")) {
            project.locales.add(0, "en");
        }
        if (board != null) {
            CanvasNode node = new CanvasNode();
            node.id = Ids.uid("n");
            node.kind = CanvasNode.KIND_CONTENT;
            node.contentId = record.id;
            if (worldX != null && worldY != null) {
                node.x = worldX;
                node.y = worldY;
            } else {
                placeBelowAll(node);
            }
            board.nodes.add(node);

            ContentRecord added = record;
            CanvasNode placed = node;
            String stringsKey = stringKey(record, "name");
            // Commands must act on the board they were created on: the controller's board field
            // follows the UI, so an undo after switching boards would otherwise hit the wrong one.
            Board commandBoard = board;
            flags.captureAfter();
            pushCommand(new Command() {
                @Override
                public void undo() {
                    project.contents.remove(added);
                    project.strings.removeKey(stringsKey);
                    commandBoard.nodes.remove(placed);
                    commandBoard.edges.removeIf(edge -> placed.id.equals(edge.from) || placed.id.equals(edge.to));
                    flags.restoreBefore();
                }

                @Override
                public void redo() {
                    flags.restoreAfter();
                    project.contents.add(added);
                    project.strings.put(stringsKey, "en", added.name);
                    project.strings.markBundleDirty("en");
                    if (!commandBoard.nodes.contains(placed)) {
                        commandBoard.nodes.add(placed);
                    }
                }
            });
        }
        String templateName = PlanetTemplates.name(type);
        log("added " + type + " '" + name + "'"
            + (templateName != null ? " (from the " + templateName + " template)" : ""));
        fireProjectChanged();
        select(record);
        return record;
    }

    /** Default placement for a new card: below everything already on the board. */
    private void placeBelowAll(CanvasNode node) {
        node.x = AutoLayout.margin();
        double lowest = AutoLayout.margin();
        for (CanvasNode existing : board.nodes) {
            lowest = Math.max(lowest, existing.y + existing.h);
        }
        node.y = lowest + AutoLayout.gap();
    }

    /** Renaming changes the file the record is written to; references are by identity, not by name. */
    public void rename(ContentRecord record, String newName) {
        requireProject();
        if (record == null || newName == null || newName.equals(record.name)) {
            return;
        }
        String problem = NameRules.contentNameProblem(newName);
        if (problem != null) {
            log("cannot rename to '" + newName + "': " + problem);
            return;
        }
        if (record.patch) {
            // A patch is registered under the vanilla name it overrides; renaming it would turn a
            // patch into new content and break every bare reference the game resolves to vanilla.
            log("cannot rename '" + record.name
                + "': a patch keeps the vanilla name it overrides");
            return;
        }
        // Content names are unique per type: two records of one type resolving to one file made the
        // save write both to the same path, so the loser's content - and, on reopen, the record
        // itself - silently disappeared.
        boolean taken = project.contents.stream()
            .anyMatch(other -> other != record && !other.isInline()
                && other.type == record.type && other.name.equals(newName));
        if (taken) {
            log("cannot rename to '" + newName + "': " + record.type + " '" + newName
                + "' already exists; pick another name or rename that one first");
            return;
        }
        String previous = record.name;
        // Every record can hold a reference to the renamed one, so watch the whole project's dirty
        // flags - not just the renamed record's - or undoing would leave the rewritten hosts dirty.
        DirtyFlags flags = new DirtyFlags(project.contents.toArray(new ContentRecord[0]));
        ReferenceRewrite.Result rewrite = ReferenceRewrite.rename(project, registry, record,
            previous, newName);
        // Inline ammo bullets are stored under "ammoTypes.<item>"; remapping only the host's key
        // would make mergedTree re-inject the old key on save.
        List<InlinePathEdit> inlineEdits = remapAmmoInlinePaths(record, previous, newName, rewrite);
        record.name = newName;
        record.dirty = true;
        migrateBundleKeys(record, previous);
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                applyRewrite(rewrite, true);
                applyInlinePathEdits(inlineEdits, true);
                record.name = previous;
                record.dirty = true;
                migrateBundleKeys(record, newName);
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                record.name = newName;
                record.dirty = true;
                migrateBundleKeys(record, previous);
                applyRewrite(rewrite, false);
                applyInlinePathEdits(inlineEdits, false);
            }
        });
        fireProjectChanged();
        log("renamed '" + previous + "' to '" + newName + "'; the file moves on save");
    }

    /**
     * Moves the managed bundle keys (name/description/details/credit) from a record's old content
     * name to its current one, so localised display names survive a rename. Undo reverses it by
     * migrating back.
     */
    private void migrateBundleKeys(ContentRecord record, String oldName) {
        String internal = project.internalName();
        for (String suffix : List.of("name", "description", "details", "credit")) {
            String oldKey = NameRules.bundleKey(record.type,
                NameRules.fullName(internal, oldName, record.patch), suffix);
            var byLocale = project.strings.values.get(oldKey);
            if (byLocale == null) {
                continue;
            }
            String newKey = NameRules.bundleKey(record.type, record.fullName(internal), suffix);
            project.strings.values.remove(oldKey);
            project.strings.values.put(newKey, new LinkedHashMap<>(byLocale));
            byLocale.keySet().forEach(project.strings::markBundleDirty);
        }
    }

    public void deleteContent(ContentRecord record) {
        deleteContents(record == null ? List.of() : List.of(record));
    }

    /**
     * Deletes one or more records as a single undoable step (rubber-band multi-delete). Everything
     * needed to restore them exactly is captured before any mutation, so batches containing inline
     * records of the same host still roll back byte for byte.
     */
    public void deleteContents(List<ContentRecord> records) {
        requireProject();
        if (records == null || records.isEmpty()) {
            return;
        }
        List<ContentRecord> targets = records.stream().filter(Objects::nonNull).distinct().toList();
        if (targets.isEmpty()) {
            return;
        }
        List<String> orderBefore = List.copyOf(project.meta.contentOrder);
        // Capture everything needed to restore each record exactly: the card, its edges, and for
        // nested records the host's field tree before any removal.
        List<CanvasNode> nodes = new ArrayList<>();
        List<List<CanvasEdge>> touching = new ArrayList<>();
        List<com.google.gson.JsonObject> ownerTrees = new ArrayList<>();
        List<ContentRecord> owners = new ArrayList<>();
        List<Map<String, Map<String, String>>> keyLocales = new ArrayList<>();
        for (ContentRecord record : targets) {
            CanvasNode node = board == null ? null : board.nodes.stream()
                .filter(candidate -> record.id.equals(candidate.contentId))
                .findFirst().orElse(null);
            nodes.add(node);
            touching.add(board == null || node == null
                ? List.of()
                : board.edges.stream()
                    .filter(edge -> node.id.equals(edge.from) || node.id.equals(edge.to))
                    .toList());
            ContentRecord owner = record.isInline() ? project.contentById(record.inlineOwnerId) : null;
            owners.add(owner);
            ownerTrees.add(owner == null ? null : owner.tree());
            keyLocales.add(bundleLocales(record));
        }

        // The deleted records, every reference host and both dirty-flag maps are captured before
        // any mutation, so undo leaves the project exactly as clean as it was.
        DirtyFlags flags = new DirtyFlags(project.contents.toArray(new ContentRecord[0]));
        // Remove every reference to the targets first: the holders are about to lose the records
        // that made those names resolvable.
        ReferenceRewrite.Result rewrite = ReferenceRewrite.remove(project, registry, targets);
        // Deleting an item that is an ammoTypes key must also remove the inline bullet record
        // stored under "ammoTypes.<item>"; otherwise mergedTree re-creates the deleted entry.
        List<RemovedInline> removedInline = removeAmmoInlineChildren(targets, rewrite);
        // A non-item host (a turret, say) owns its inline bullets by inlineOwnerId; deleting the
        // host must remove those children too, or they become orphaned records and stale cards.
        removedInline.addAll(removeOwnedInlineChildren(targets));
        removedInline.sort(java.util.Comparator.comparingInt(RemovedInline::index));
        // A patch target is skipped by ReferenceRewrite (deleting it restores vanilla content), but
        // its own contentOrder entry still names the deleted file and must go.
        for (ContentRecord target : targets) {
            project.meta.contentOrder.remove(target.name);
        }
        List<String> orderAfter = List.copyOf(project.meta.contentOrder);
        if (!orderBefore.equals(orderAfter)) {
            project.metaDirty = true;
        }

        for (int i = 0; i < targets.size(); i++) {
            ContentRecord record = targets.get(i);
            if (record.isInline() && record.inlineOwnerId != null) {
                // Nested content only exists inside its host, so removing the node removes the object.
                ContentRecord owner = owners.get(i);
                if (owner != null) {
                    com.google.gson.JsonObject tree = owner.tree();
                    if (JsonPath.remove(tree, record.inlinePath)) {
                        owner.applyTree(tree);
                        owner.dirty = true;
                    }
                }
            }
            // The bundles still hold the deleted record's four managed lines: mark every locale
            // that had a value, or the stale keys would survive on disk forever. Removing only
            // "name" left description/details/credit behind in the shipped bundle.
            Map<String, Map<String, String>> locales = keyLocales.get(i);
            locales.keySet().forEach(this::markKeyLocalesDirty);
            locales.keySet().forEach(project.strings::removeKey);
            project.contents.remove(record);
            if (board != null) {
                board.nodes.removeIf(candidate -> record.id.equals(candidate.contentId));
                if (nodes.get(i) != null) {
                    CanvasNode node = nodes.get(i);
                    board.edges.removeIf(edge -> node.id.equals(edge.from) || node.id.equals(edge.to));
                }
            }
        }
        selection.clear();
        project.boardsDirty = true;

        List<CanvasNode> capturedNodes = List.copyOf(nodes);
        List<List<CanvasEdge>> capturedTouching = List.copyOf(touching);
        List<Map<String, Map<String, String>>> capturedKeyLocales = List.copyOf(keyLocales);
        Board commandBoard = board;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                applyRewrite(rewrite, true);
                for (int i = targets.size() - 1; i >= 0; i--) {
                    ContentRecord record = targets.get(i);
                    project.contents.add(record);
                    capturedKeyLocales.get(i).forEach((key, byLocale) -> {
                        project.strings.removeKey(key);
                        byLocale.forEach((locale, value) -> {
                            project.strings.put(key, locale, value);
                            project.strings.markBundleDirty(locale);
                        });
                    });
                    CanvasNode node = capturedNodes.get(i);
                    if (node != null && commandBoard != null && !commandBoard.nodes.contains(node)) {
                        commandBoard.nodes.add(node);
                    }
                    if (commandBoard != null) {
                        // Both endpoints of an edge can be in the batch: restore each edge once.
                        for (CanvasEdge edge : capturedTouching.get(i)) {
                            if (!commandBoard.edges.contains(edge)) {
                                commandBoard.edges.add(edge);
                            }
                        }
                    }
                    ContentRecord owner = owners.get(i);
                    com.google.gson.JsonObject ownerBefore = ownerTrees.get(i);
                    if (owner != null && ownerBefore != null) {
                        owner.applyTree(ownerBefore);
                        owner.dirty = true;
                    }
                }
                applyRemovedInline(removedInline, true);
                project.meta.contentOrder.clear();
                project.meta.contentOrder.addAll(orderBefore);
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                applyRewrite(rewrite, false);
                for (int i = 0; i < targets.size(); i++) {
                    ContentRecord record = targets.get(i);
                    List<CanvasEdge> recordEdges = capturedTouching.get(i);
                    if (record.isInline()) {
                        ContentRecord owner = project.contentById(record.inlineOwnerId);
                        if (owner != null) {
                            com.google.gson.JsonObject tree = owner.tree();
                            if (JsonPath.remove(tree, record.inlinePath)) {
                                owner.applyTree(tree);
                                owner.dirty = true;
                            }
                        }
                    }
                    capturedKeyLocales.get(i).keySet().forEach(ProjectController.this::markKeyLocalesDirty);
                    capturedKeyLocales.get(i).keySet().forEach(project.strings::removeKey);
                    project.contents.remove(record);
                    if (commandBoard != null) {
                        commandBoard.nodes.removeIf(candidate -> record.id.equals(candidate.contentId));
                        commandBoard.edges.removeIf(recordEdges::contains);
                    }
                }
                applyRemovedInline(removedInline, false);
                project.meta.contentOrder.clear();
                project.meta.contentOrder.addAll(orderAfter);
                project.boardsDirty = true;
            }
        });

        if (targets.size() == 1) {
            log("deleted " + targets.get(0).type + " '" + targets.get(0).name + "'");
        } else {
            log("deleted " + targets.size() + " records");
        }
        fireProjectChanged();
    }

    /**
     * Draws a reference: writes the source content's name into the target's field and adds the edge.
     * The source name is written bare - the game resolves it against the current mod first, and
     * vanilla targets must not be prefixed. The source can be a full content card or a carrier card
     * bound to a record / a vanilla name.
     */
    public boolean connect(String fromNodeId, String toNodeId, String fieldKey) {
        requireProject();
        if (board == null) {
            return false;
        }
        CanvasNode fromNode = nodeById(fromNodeId);
        CanvasNode toNode = nodeById(toNodeId);
        if (fromNode == null || toNode == null || fromNode == toNode) {
            return false;
        }
        ContentRecord from = contentOf(fromNode);
        ContentRecord to = contentOf(toNode);
        if (to == null || (from == null && fromNode.refName == null)) {
            return false;
        }
        if (from != null && from == to) {
            return false;
        }
        boolean exists = board.edges.stream().anyMatch(edge ->
            fromNodeId.equals(edge.from) && toNodeId.equals(edge.to) && fieldKey.equals(edge.fieldKey));
        if (exists) {
            log("that reference already exists");
            return false;
        }
        FieldDef field = referenceField(to, fieldKey);
        if (field == null) {
            log(fieldKey + " is not a reference field of " + to.name);
            return false;
        }

        String sourceName = sourceNameOf(fromNode);
        // Struct shapes route by the referenced content's type: a factory block into a unit's
        // requirements creates an entry, an item fills its costs, a liquid goes into a consume
        // group's liquids slot. Null when the shape and the type cannot combine.
        String sourceTypeName = from != null ? from.type.name() : fromNode.refType;
        DirtyFlags flags = new DirtyFlags(to);
        ReferenceOps.Revert revert = ReferenceOps.apply(to, field, sourceName, sourceTypeName);
        if (revert == null) {
            log("wire a " + entryKeyOf(field) + " into " + to.name + "." + fieldKey
                + " first - there is no slot for a " + (sourceTypeName == null ? "reference"
                : sourceTypeName) + " yet");
            return false;
        }
        to.dirty = true;
        CanvasEdge edge = new CanvasEdge();
        edge.id = Ids.uid("e");
        edge.from = fromNodeId;
        edge.to = toNodeId;
        edge.fieldKey = fieldKey;
        edge.label = sourceName;
        board.edges.add(edge);

        Board commandBoard = board;
        String appliedSource = sourceTypeName;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                ReferenceOps.revert(to, field, revert);
                to.dirty = true;
                commandBoard.edges.remove(edge);
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                ReferenceOps.apply(to, field, sourceName, appliedSource);
                to.dirty = true;
                if (!commandBoard.edges.contains(edge)) {
                    commandBoard.edges.add(edge);
                }
            }
        });
        log(sourceName + " -> " + to.name + "." + fieldKey);
        fireProjectChanged();
        return true;
    }

    /**
     * Writes one reference from the block canvas's per-block link view. The same ReferenceOps
     * write with undo as {@link #connect}, without the board edge - the focused view's links are
     * a derived reading of the records, not board data. The source is a record or a direct
     * reference name (vanilla content).
     */
    public boolean connectReference(ContentRecord from, String refName, ContentRecord to,
        String fieldKey) {
        requireProject();
        if (to == null || (from == null && (refName == null || refName.isBlank()))) {
            return false;
        }
        if (from != null && from == to) {
            return false;
        }
        FieldDef field = referenceField(to, fieldKey);
        if (field == null) {
            log(fieldKey + " is not a reference field of " + to.name);
            return false;
        }
        String sourceName = from != null ? from.name : refName;
        String sourceTypeName = from != null ? from.type.name() : null;
        DirtyFlags flags = new DirtyFlags(to);
        ReferenceOps.Revert revert = ReferenceOps.apply(to, field, sourceName, sourceTypeName);
        if (revert == null) {
            log("wire a " + entryKeyOf(field) + " into " + to.name + "." + fieldKey
                + " first - there is no slot for a " + (sourceTypeName == null ? "reference"
                : sourceTypeName) + " yet");
            return false;
        }
        to.dirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                ReferenceOps.revert(to, field, revert);
                to.dirty = true;
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                ReferenceOps.apply(to, field, sourceName, sourceTypeName);
                to.dirty = true;
            }
        });
        log(sourceName + " -> " + to.name + "." + fieldKey);
        fireProjectChanged();
        return true;
    }

    /** Removes one reference written from the per-block link view. Undoable like every edit. */
    public boolean disconnectReference(ContentRecord to, String fieldKey, String sourceName) {
        requireProject();
        if (to == null) {
            return false;
        }
        FieldDef field = referenceField(to, fieldKey);
        if (field == null) {
            return false;
        }
        DirtyFlags flags = new DirtyFlags(to);
        boolean removed = ReferenceOps.remove(to, field, sourceName);
        if (!removed) {
            return false;
        }
        to.dirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                ReferenceOps.apply(to, field, sourceName, null);
                to.dirty = true;
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                ReferenceOps.remove(to, field, sourceName);
                to.dirty = true;
            }
        });
        log("removed " + sourceName + " -> " + to.name + "." + fieldKey);
        fireProjectChanged();
        return true;
    }

    /** Removes a reference edge and its field value. */
    public void disconnect(CanvasEdge edge) {
        requireProject();
        if (edge == null || !board.edges.contains(edge)) {
            return;
        }
        CanvasNode fromNode = nodeById(edge.from);
        ContentRecord to = contentOf(nodeById(edge.to));
        if (to == null) {
            // The record the edge points at is gone (stale authoring data). Dropping the edge is
            // still an edit the user can make, so it has to be undoable like every other one: the
            // bare removal here was the one delete Ctrl+Z could not bring back.
            Board commandBoard = board;
            DirtyFlags flags = new DirtyFlags();
            commandBoard.edges.remove(edge);
            if (project != null) {
                project.boardsDirty = true;
            }
            flags.captureAfter();
            pushCommand(new Command() {
                @Override
                public void undo() {
                    if (!commandBoard.edges.contains(edge)) {
                        commandBoard.edges.add(edge);
                    }
                    flags.restoreBefore();
                }

                @Override
                public void redo() {
                    flags.restoreAfter();
                    commandBoard.edges.remove(edge);
                }
            });
            fireProjectChanged();
            return;
        }
        String sourceName = fromNode == null ? edge.label : sourceNameOf(fromNode);
        ContentRecord from = contentOf(fromNode);
        String sourceTypeName = from != null ? from.type.name() : fromNode == null ? null : fromNode.refType;
        FieldDef field = referenceField(to, edge.fieldKey);
        DirtyFlags flags = new DirtyFlags(to);
        boolean removed = field != null && ReferenceOps.remove(to, field, sourceName);
        if (removed) {
            to.dirty = true;
        }
        board.edges.remove(edge);

        Board commandBoard = board;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                if (field != null) {
                    ReferenceOps.apply(to, field, sourceName, sourceTypeName);
                    to.dirty = true;
                }
                if (!commandBoard.edges.contains(edge)) {
                    commandBoard.edges.add(edge);
                }
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                if (field != null) {
                    ReferenceOps.remove(to, field, sourceName);
                    to.dirty = true;
                }
                commandBoard.edges.remove(edge);
            }
        });
        log("removed " + sourceName + " -> " + to.name + "." + edge.fieldKey);
        fireProjectChanged();
    }

    /**
     * The amount a connection carries, or -1 when the edge is not a stack reference. The canvas uses
     * this to tell whether an amount editor applies, and to pre-fill it.
     */
    public int edgeAmount(CanvasEdge edge) {
        ContentRecord to = edgeTarget(edge);
        FieldDef field = to == null ? null : referenceField(to, edge.fieldKey);
        if (field == null
            || (field.type() != FieldType.stack && field.type() != FieldType.stackArray)) {
            return -1;
        }
        return ReferenceOps.amountOf(to, field, sourceNameOf(nodeById(edge.from)));
    }

    /** Sets how many of the referenced stack item a connection carries. Undoable. */
    public void setEdgeAmount(CanvasEdge edge, int amount) {
        requireProject();
        ContentRecord to = edgeTarget(edge);
        FieldDef field = to == null ? null : referenceField(to, edge.fieldKey);
        if (field == null || amount < 1) {
            return;
        }
        String sourceName = sourceNameOf(nodeById(edge.from));
        DirtyFlags flags = new DirtyFlags(to);
        ReferenceOps.Revert revert = ReferenceOps.setAmount(to, field, sourceName, amount);
        if (revert == null) {
            return;
        }
        to.dirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                ReferenceOps.revert(to, field, revert);
                to.dirty = true;
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                ReferenceOps.setAmount(to, field, sourceName, amount);
                to.dirty = true;
            }
        });
        log("set " + sourceName + " amount in " + to.name + "." + edge.fieldKey + " to " + amount);
        fireProjectChanged();
    }

    /** The record a connection writes into, or null when the edge is stale or unbound. */
    private ContentRecord edgeTarget(CanvasEdge edge) {
        if (edge == null || board == null || project == null || !board.edges.contains(edge)) {
            return null;
        }
        return contentOf(nodeById(edge.to));
    }

    /** Records a finished card drag so it can be undone (positions are already applied). */
    public void commitMoves(Map<String, Point2D> before, Map<String, double[]> after) {
        if (before.isEmpty() || !movesAnything(before, after)) {
            // A press and release at the same spot is not an edit: no undo step, no dirty flag.
            return;
        }
        DirtyFlags flags = new DirtyFlags();
        if (project != null) {
            project.boardsDirty = true;
        }
        flags.captureAfter();
        Board commandBoard = board;
        pushCommand(new Command() {
            private void apply(Map<String, double[]> positions) {
                if (commandBoard == null) {
                    return;
                }
                for (CanvasNode node : commandBoard.nodes) {
                    double[] position = positions.get(node.id);
                    if (position != null) {
                        node.x = position[0];
                        node.y = position[1];
                    }
                }
            }

            @Override
            public void undo() {
                Map<String, double[]> previous = new java.util.LinkedHashMap<>();
                before.forEach((nodeId, point) -> previous.put(nodeId, new double[] {point.getX(), point.getY()}));
                apply(previous);
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                apply(after);
            }
        });
    }

    /** True when at least one dragged card actually ended up somewhere else. */
    private static boolean movesAnything(Map<String, Point2D> before, Map<String, double[]> after) {
        for (Map.Entry<String, double[]> entry : after.entrySet()) {
            Point2D origin = before.get(entry.getKey());
            double[] position = entry.getValue();
            if (origin != null && position != null
                && (origin.getX() != position[0] || origin.getY() != position[1])) {
                return true;
            }
        }
        return false;
    }

    // --- reroute waypoints (report P2-9) ---------------------------------------------------------

    /** Drops a reroute waypoint into a connection at a world point. Undoable. */
    public void insertReroute(CanvasEdge edge, double worldX, double worldY) {
        requireProject();
        if (edge == null || board == null || !board.edges.contains(edge)) {
            return;
        }
        int index = edge.reroutes.size();
        double[] point = new double[] {worldX, worldY};
        DirtyFlags flags = new DirtyFlags();
        edge.reroutes.add(point);
        project.boardsDirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                if (index < edge.reroutes.size()) {
                    edge.reroutes.remove(index);
                }
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                if (index <= edge.reroutes.size()) {
                    edge.reroutes.add(point);
                }
            }
        });
        fireProjectChanged();
    }

    /** Removes one reroute waypoint from a connection. Undoable. */
    public void removeReroute(CanvasEdge edge, int index) {
        requireProject();
        if (edge == null || board == null || !board.edges.contains(edge)
            || index < 0 || index >= edge.reroutes.size()) {
            return;
        }
        double[] point = edge.reroutes.get(index);
        DirtyFlags flags = new DirtyFlags();
        edge.reroutes.remove(index);
        project.boardsDirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                if (index <= edge.reroutes.size()) {
                    edge.reroutes.add(index, point);
                }
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                if (index < edge.reroutes.size()) {
                    edge.reroutes.remove(index);
                }
            }
        });
        fireProjectChanged();
    }

    /**
     * Records a finished reroute drag so it can be undone (the drag already moved the live
     * waypoint, exactly like a card drag leaves its node in place before this is called). A press
     * and release at the same spot pushes nothing and marks nothing dirty.
     */
    public void commitRerouteMove(CanvasEdge edge, int index, double[] before, double[] after) {
        requireProject();
        if (edge == null || board == null || !board.edges.contains(edge)
            || index < 0 || index >= edge.reroutes.size()) {
            return;
        }
        if (before[0] == after[0] && before[1] == after[1]) {
            return;
        }
        edge.reroutes.set(index, after);
        DirtyFlags flags = new DirtyFlags();
        project.boardsDirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                if (index < edge.reroutes.size()) {
                    edge.reroutes.set(index, before);
                }
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                if (index < edge.reroutes.size()) {
                    edge.reroutes.set(index, after);
                }
            }
        });
    }

    // --- undo / redo ----------------------------------------------------------------------------

    public boolean adaptiveClasses() {
        return adaptiveClasses;
    }

    public void setAdaptiveClasses(boolean value) {
        if (adaptiveClasses != value) {
            adaptiveClasses = value;
            log(value ? "adaptive classes on (experimental)" : "adaptive classes off");
            fireProjectChanged();
        }
    }

    public void pushCommand(Command command) {
        history.push(command);
    }

    public boolean canUndo() {
        return history.canUndo();
    }

    public boolean canRedo() {
        return history.canRedo();
    }

    /** How many undoable steps are stacked (test hook). */
    int undoStackDepth() {
        return history.depth();
    }

    public void undo() {
        requireProject();
        if (history.undo() == null) {
            return;
        }
        log("undo");
        fireProjectChanged();
    }

    public void redo() {
        requireProject();
        if (history.redo() == null) {
            return;
        }
        log("redo");
        fireProjectChanged();
    }

    private void clearHistory() {
        history.clear();
    }

    private FieldDef referenceField(ContentRecord record, String fieldKey) {
        String className = registry.resolveClass(record.type, record.className());
        if (className == null) {
            return null;
        }
        for (FieldDef def : registry.fieldsOf(className)) {
            if (def.name().equals(fieldKey)) {
                return def.isReference() ? def : null;
            }
        }
        return null;
    }

    private CanvasNode nodeById(String nodeId) {
        return board == null ? null : board.nodes.stream()
            .filter(node -> node.id.equals(nodeId))
            .findFirst().orElse(null);
    }

    /** The record a node is bound to, or null for reference-name carriers (vanilla content). */
    private ContentRecord contentOf(CanvasNode node) {
        return node == null || node.contentId == null ? null : project.contentById(node.contentId);
    }

    /** The bare content name a node stands for: its record's name, or its direct reference name. */
    public String sourceNameOf(CanvasNode node) {
        if (node == null) {
            return "";
        }
        ContentRecord record = contentOf(node);
        return record != null ? record.name : node.refName == null ? "" : node.refName;
    }

    /**
     * The entry key a struct field's first wiring must provide ("block" for a unit requirement,
     * "unit" for a factory plan) - used in the connect error message.
     */
    private static String entryKeyOf(FieldDef field) {
        String shape = field.stackKind();
        if ("unitReq".equals(shape)) {
            return "factory block";
        }
        if ("plan".equals(shape)) {
            return "unit";
        }
        return "reference";
    }

    /**
     * Rebinds a carrier card to another record, or to a direct reference name (vanilla content).
     * Undoable; edges keep pointing at the node, so they follow the new binding automatically.
     */
    public void rebindCarrier(String nodeId, String newContentId, String newRefName) {
        requireProject();
        CanvasNode node = nodeById(nodeId);
        if (node == null) {
            return;
        }
        String oldContentId = node.contentId;
        String oldRefName = node.refName;
        if (Objects.equals(oldContentId, newContentId) && Objects.equals(oldRefName, newRefName)) {
            return;
        }
        DirtyFlags flags = new DirtyFlags();
        node.contentId = newContentId;
        node.refName = newRefName;
        project.boardsDirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                node.contentId = oldContentId;
                node.refName = oldRefName;
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                node.contentId = newContentId;
                node.refName = newRefName;
            }
        });
        fireProjectChanged();
    }

    /**
     * Removes carrier nodes (and their edges) without touching the records they point at - the
     * records stay in the assets workspace. Undoable.
     */
    public void detachNodes(List<String> nodeIds) {
        requireProject();
        if (nodeIds == null || nodeIds.isEmpty() || board == null) {
            return;
        }
        List<CanvasNode> removed = new ArrayList<>();
        List<List<CanvasEdge>> touching = new ArrayList<>();
        for (String nodeId : nodeIds) {
            CanvasNode node = nodeById(nodeId);
            if (node == null) {
                continue;
            }
            removed.add(node);
            touching.add(board.edges.stream()
                .filter(edge -> node.id.equals(edge.from) || node.id.equals(edge.to))
                .toList());
        }
        if (removed.isEmpty()) {
            return;
        }
        List<CanvasNode> capturedNodes = List.copyOf(removed);
        List<List<CanvasEdge>> capturedTouching = List.copyOf(touching);
        Board commandBoard = board;
        DirtyFlags flags = new DirtyFlags();
        for (CanvasNode node : capturedNodes) {
            board.nodes.remove(node);
            board.edges.removeIf(edge ->
                node.id.equals(edge.from) || node.id.equals(edge.to));
        }
        selection.clear();
        project.boardsDirty = true;
        flags.captureAfter();

        pushCommand(new Command() {
            @Override
            public void undo() {
                for (int i = 0; i < capturedNodes.size(); i++) {
                    CanvasNode node = capturedNodes.get(i);
                    if (!commandBoard.nodes.contains(node)) {
                        commandBoard.nodes.add(node);
                    }
                    for (CanvasEdge edge : capturedTouching.get(i)) {
                        if (!commandBoard.edges.contains(edge)) {
                            commandBoard.edges.add(edge);
                        }
                    }
                }
                project.boardsDirty = true;
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                for (CanvasNode node : capturedNodes) {
                    commandBoard.nodes.remove(node);
                    commandBoard.edges.removeIf(edge ->
                        node.id.equals(edge.from) || node.id.equals(edge.to));
                }
                project.boardsDirty = true;
            }
        });
        log("detached " + capturedNodes.size() + " carrier card(s); the records stay registered");
        fireProjectChanged();
    }

    /** Renaming changes the file the record is written to; references are by identity, not by name. */

    /** Sets a field on a record, marking it dirty so it is re-serialised on save. Undoable; quick
     * consecutive edits of the same field (a colour picker nudging values) coalesce into one step. */
    public void setField(ContentRecord record, String field, JsonElement value) {
        requireProject();
        if (record == null) {
            return;
        }
        JsonElement previous = record.fields.get(field);
        // Objects and arrays are edited in place by structured editors (the mesh editor mutates
        // the record's own JsonObject), so reference equality still means "changed".
        boolean mutable = (previous != null && previous.isJsonObject() || previous != null
            && previous.isJsonArray())
            || (value != null && value.isJsonObject() || value != null && value.isJsonArray());
        if (!mutable) {
            if (value == null && previous == null) {
                return;
            }
            if (previous != null && previous.equals(value)) {
                return;
            }
        }
        DirtyFlags flags = new DirtyFlags(record);
        if (value == null) {
            record.fields.remove(field);
        } else {
            record.fields.put(field, value);
        }
        record.dirty = true;
        warnAboutReserialisation(record);
        // In-place-mutated objects need snapshots for undo to mean anything.
        JsonElement oldSnapshot = mutable && previous != null ? previous.deepCopy() : previous;
        JsonElement newSnapshot = mutable && value != null ? value.deepCopy() : value;
        FieldCommand command = new FieldCommand(record, field, oldSnapshot, newSnapshot, flags);
        flags.captureAfter();
        if (history.peekUndo() instanceof FieldCommand top && top.sameTarget(command)) {
            top.newValue = newSnapshot;
            history.clearRedo();
        } else {
            pushCommand(command);
        }
        fireProjectChanged();
    }

    /** The four managed bundle keys of a record that actually have translations, with their locales. */
    private Map<String, Map<String, String>> bundleLocales(ContentRecord record) {
        Map<String, Map<String, String>> found = new LinkedHashMap<>();
        for (String field : NameRules.BUNDLE_FIELDS) {
            String key = stringKey(record, field);
            var byLocale = project.strings.values.get(key);
            if (byLocale != null) {
                found.put(key, new LinkedHashMap<>(byLocale));
            }
        }
        return found;
    }

    /** Marks every locale whose bundle holds a value for the key, so the next save rewrites them. */
    private void markKeyLocalesDirty(String key) {
        var byLocale = project.strings.values.get(key);
        if (byLocale != null) {
            byLocale.keySet().forEach(project.strings::markBundleDirty);
        }
    }

    public void setString(String key, String locale, String value) {        requireProject();
        String previous = project.strings.get(key, locale);
        if (Objects.equals(previous, value)) {
            return;
        }
        DirtyFlags flags = new DirtyFlags();
        project.strings.put(key, locale, value);
        project.strings.markBundleDirty(locale);
        StringCommand command = new StringCommand(key, locale, previous, value, flags);
        flags.captureAfter();
        if (history.peekUndo() instanceof StringCommand top && top.sameTarget(command)) {
            top.newValue = value;
            history.clearRedo();
        } else {
            pushCommand(command);
        }
        fireProjectChanged();
    }

    /** Toggles a record's patch flag and notifies the views (canvas badge, exporters). Undoable. */
    public void setPatch(ContentRecord record, boolean patch) {
        requireProject();
        if (record == null || record.patch == patch) {
            return;
        }
        boolean previous = record.patch;
        DirtyFlags flags = new DirtyFlags(record);
        record.patch = patch;
        record.dirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                record.patch = previous;
                record.dirty = true;
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                record.patch = patch;
                record.dirty = true;
            }
        });
        fireProjectChanged();
    }

    /** Replaces a record's whole field tree from the inspector's raw JSON editor. Undoable. */
    public void applyRawTree(ContentRecord record, JsonObject tree) {
        requireProject();
        if (record == null || tree == null) {
            return;
        }
        // Snapshot both ends: the editor replaces the whole tree, and nested values are shared with
        // the structured editors, which mutate them in place.
        JsonObject before = record.tree().deepCopy();
        if (before.equals(tree)) {
            return;
        }
        DirtyFlags flags = new DirtyFlags(record);
        record.applyTree(tree.deepCopy());
        record.dirty = true;
        warnAboutReserialisation(record);
        flags.captureAfter();
        JsonObject after = record.tree().deepCopy();
        pushCommand(new Command() {
            @Override
            public void undo() {
                record.applyTree(before.deepCopy());
                record.dirty = true;
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                record.applyTree(after.deepCopy());
                record.dirty = true;
            }
        });
        fireProjectChanged();
    }

    public void select(ContentRecord record) {
        selection.select(record);
    }

    public Board addBoard(String name) {
        requireProject();
        Board created = new Board();
        created.id = Ids.uid("b");
        created.name = name;
        created.file = uniqueBoardFile(name);
        DirtyFlags flags = new DirtyFlags();
        project.boards.add(created);
        project.boardsDirty = true;
        Board previous = board;
        board = created;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                project.boards.remove(created);
                board = previous != null ? previous
                    : (project.boards.isEmpty() ? null : project.boards.get(0));
                flags.restoreBefore();
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                if (!project.boards.contains(created)) {
                    project.boards.add(created);
                }
                board = created;
            }
        });
        fireProjectChanged();
        return created;
    }

    public void setBoard(Board next) {
        board = next;
        fireProjectChanged();
    }

    // --- layout, validation, build --------------------------------------------------------------

    public void tidyLayout() {
        requireProject();
        if (board == null) {
            return;
        }
        // A full re-arrangement is far too disruptive to be irreversible: snapshot both ends.
        Map<String, double[]> before = snapshotPositions();
        AutoLayout.tidy(project, board);
        Map<String, double[]> after = snapshotPositions();
        if (!samePositions(before, after)) {
            DirtyFlags flags = new DirtyFlags();
            project.boardsDirty = true;
            flags.captureAfter();
            Board commandBoard = board;
            pushCommand(new Command() {
                @Override
                public void undo() {
                    applyPositions(commandBoard, before);
                    flags.restoreBefore();
                }

                @Override
                public void redo() {
                    flags.restoreAfter();
                    applyPositions(commandBoard, after);
                }
            });
        }
        fireProjectChanged();
    }

    /** How far the current board has drifted from the records it mirrors. */
    public LinkGraph.Diff canvasDiff() {
        if (project == null || board == null) {
            return new LinkGraph.Diff(0, 0, 0);
        }
        return LinkGraph.diff(project, board, registry);
    }

    /** The drift found when the project was loaded, as refreshed by the last rebuild. */
    public LinkGraph.Diff canvasDrift() {
        return canvasDrift;
    }

    /**
     * Recomputes the board's links from the records: a link between two content cards becomes
     * exactly what the records imply, while a link that touches a card with no record (a vanilla
     * carrier or a detached card) is kept untouched. Undoable. Returns how many links changed.
     */
    public int rebuildLinks() {
        requireProject();
        if (board == null) {
            return 0;
        }
        List<CanvasEdge> before = new ArrayList<>(board.edges);
        LinkGraph.Rebuild rebuild = LinkGraph.rebuild(project, board, registry);
        if (rebuild.added() == 0 && rebuild.removed() == 0) {
            log("links already match the records");
            return 0;
        }
        Board commandBoard = board;
        List<CanvasEdge> after = rebuild.edges();
        DirtyFlags flags = new DirtyFlags();
        board.edges.clear();
        board.edges.addAll(after);
        project.boardsDirty = true;
        flags.captureAfter();
        pushCommand(new Command() {
            @Override
            public void undo() {
                flags.restoreBefore();
                commandBoard.edges.clear();
                commandBoard.edges.addAll(before);
            }

            @Override
            public void redo() {
                flags.restoreAfter();
                commandBoard.edges.clear();
                commandBoard.edges.addAll(after);
            }
        });
        canvasDrift = canvasDiff();
        log("rebuilt links: +" + rebuild.added() + " -" + rebuild.removed()
            + " (" + board.edges.size() + " total)");
        fireProjectChanged();
        return rebuild.added() + rebuild.removed();
    }

    private Map<String, double[]> snapshotPositions() {
        Map<String, double[]> snapshot = new java.util.LinkedHashMap<>();
        if (board != null) {
            for (CanvasNode node : board.nodes) {
                snapshot.put(node.id, new double[] {node.x, node.y});
            }
        }
        return snapshot;
    }

    private void applyPositions(Board target, Map<String, double[]> positions) {
        if (target == null) {
            return;
        }
        for (CanvasNode node : target.nodes) {
            double[] position = positions.get(node.id);
            if (position != null) {
                node.x = position[0];
                node.y = position[1];
            }
        }
    }

    /** Element-wise position comparison: {@code double[]} values must be compared by content. */
    private static boolean samePositions(Map<String, double[]> before, Map<String, double[]> after) {
        if (!before.keySet().equals(after.keySet())) {
            return false;
        }
        for (String nodeId : before.keySet()) {
            if (!java.util.Arrays.equals(before.get(nodeId), after.get(nodeId))) {
                return false;
            }
        }
        return true;
    }

    /** F5/"validate": a fresh synchronous pass, bypassing any cached or in-flight result. */
    public List<Issue> validate() {
        requireProject();
        List<Issue> issues = validation.forceSync();
        long errors = issues.stream().filter(Issue::isError).count();
        log("validation: " + errors + " errors, " + (issues.size() - errors) + " warnings");
        return issues;
    }

    /**
     * The model half of a build: validates, decides the save and captures the values the packer needs.
     * Must run on the FX thread, which owns the model. The flow itself lives in {@link BuildPipeline},
     * which the CLI drives too.
     */
    public BuildPipeline.Plan planBuild() throws IOException {
        requireProject();
        return actions.planBuild(project);
    }

    /**
     * The model half of a build with findings that were already computed from a snapshot off the FX
     * thread, so the rule set does not run a second time here.
     */
    public BuildPipeline.Plan planBuild(List<Issue> issues) throws IOException {
        requireProject();
        return actions.planBuild(project, issues);
    }

    /**
     * Validates a snapshot of the project on the calling thread. Used by the shell's io worker so a
     * build's validation never runs on the FX thread; the snapshot is taken there beforehand.
     */
    public List<Issue> validateSnapshot(ModProject snapshot) {
        return validation.validate(snapshot);
    }

    /** The disk half of a folder build: pure file work, so the worker thread may run it. */
    public ExportReport writeBuildFolder(BuildPipeline.Plan plan, Path destination)
        throws IOException {
        ExportReport report = actions.writeBuildFolder(plan, destination);
        log("build: " + report.summary());
        return report;
    }

    /** The disk half of a zip build: pure file work, so the worker thread may run it. */
    public ExportReport writeBuildZip(BuildPipeline.Plan plan, Path zipFile) throws IOException {
        ExportReport report = actions.writeBuildZip(plan, zipFile);
        log("build: " + report.summary());
        return report;
    }

    /** One inline record whose path changes when an ammoTypes key is renamed. */
    private record InlinePathEdit(ContentRecord record, String oldPath, String newPath,
        String oldName, String newName) {
    }

    /** One inline record removed because its ammoTypes key was deleted. */
    private record RemovedInline(ContentRecord record, int index, String path, String name,
        CanvasNode node, List<CanvasEdge> edges) {
    }

    /**
     * Renames {@code ammoTypes.<item>} paths of inline bullets owned by a host whose ammoTypes key
     * just changed. Without this, ProjectIo.mergedTree re-inserts the old key on save.
     */
    private List<InlinePathEdit> remapAmmoInlinePaths(ContentRecord target, String oldName,
        String newName, ReferenceRewrite.Result rewrite) {
        if (target.type != ContentType.item) {
            return List.of();
        }
        String internal = project.internalName();
        String fullOld = NameRules.fullName(internal, oldName, false);
        String fullNew = NameRules.fullName(internal, newName, false);
        List<InlinePathEdit> edits = new ArrayList<>();
        for (ContentRecord inline : project.contents) {
            if (!inline.isInline() || inline.inlinePath == null) {
                continue;
            }
            ContentRecord owner = project.contentById(inline.inlineOwnerId);
            if (owner == null || !changedBy(rewrite, owner)) {
                continue;
            }
            String newPath = remapAmmoPath(inline.inlinePath, oldName, newName);
            if (newPath == null) {
                newPath = remapAmmoPath(inline.inlinePath, fullOld, fullNew);
            }
            if (newPath == null) {
                continue;
            }
            String oldLeaf = inline.name;
            String newLeaf = dev.modmaker.core.json.JsonPath.leafName(newPath);
            edits.add(new InlinePathEdit(inline, inline.inlinePath, newPath, oldLeaf, newLeaf));
            inline.inlinePath = newPath;
            inline.name = newLeaf;
        }
        return edits;
    }

    /** Restores or re-applies inline path edits as part of rename undo/redo. */
    private void applyInlinePathEdits(List<InlinePathEdit> edits, boolean before) {
        for (InlinePathEdit edit : edits) {
            edit.record().inlinePath = before ? edit.oldPath() : edit.newPath();
            edit.record().name = before ? edit.oldName() : edit.newName();
        }
    }

    /**
     * Removes inline bullets stored under an ammoTypes key being deleted, together with their
     * cards. Undo re-adds them; redo removes them again.
     */
    private List<RemovedInline> removeAmmoInlineChildren(List<ContentRecord> targets,
        ReferenceRewrite.Result rewrite) {
        String internal = project.internalName();
        List<RemovedInline> removed = new ArrayList<>();
        for (ContentRecord target : targets) {
            if (target == null || target.patch || target.type != ContentType.item) {
                continue;
            }
            String full = NameRules.fullName(internal, target.name, false);
            for (int i = project.contents.size() - 1; i >= 0; i--) {
                ContentRecord inline = project.contents.get(i);
                if (!inline.isInline() || inline.inlinePath == null || targets.contains(inline)) {
                    continue;
                }
                ContentRecord owner = project.contentById(inline.inlineOwnerId);
                if (owner == null || !changedBy(rewrite, owner)
                    || !underAmmoKey(inline.inlinePath, target.name)
                        && !underAmmoKey(inline.inlinePath, full)) {
                    continue;
                }
                CanvasNode node = board == null ? null : board.nodes.stream()
                    .filter(candidate -> inline.id.equals(candidate.contentId))
                    .findFirst().orElse(null);
                List<CanvasEdge> edges = board == null || node == null ? List.of()
                    : board.edges.stream()
                        .filter(edge -> node.id.equals(edge.from) || node.id.equals(edge.to))
                        .toList();
                removed.add(new RemovedInline(inline, i, inline.inlinePath, inline.name, node, edges));
                project.contents.remove(i);
                if (board != null && node != null) {
                    board.nodes.remove(node);
                    board.edges.removeIf(edges::contains);
                }
            }
        }
        removed.sort(java.util.Comparator.comparingInt(RemovedInline::index));
        return removed;
    }

    /**
     * Removes inline records owned by any deleted host (a turret's bullets, for instance), together
     * with their cards and edges, so they do not survive as orphans. Undo/redo go through
     * {@link #applyRemovedInline} like the ammoTypes case.
     */
    private List<RemovedInline> removeOwnedInlineChildren(List<ContentRecord> targets) {
        List<RemovedInline> removed = new ArrayList<>();
        for (ContentRecord target : targets) {
            if (target == null || target.id == null) {
                continue;
            }
            for (int i = project.contents.size() - 1; i >= 0; i--) {
                ContentRecord inline = project.contents.get(i);
                if (!inline.isInline() || inline.inlineOwnerId == null
                    || !target.id.equals(inline.inlineOwnerId) || targets.contains(inline)) {
                    continue;
                }
                CanvasNode node = board == null ? null : board.nodes.stream()
                    .filter(candidate -> inline.id.equals(candidate.contentId))
                    .findFirst().orElse(null);
                List<CanvasEdge> edges = board == null || node == null ? List.of()
                    : board.edges.stream()
                        .filter(edge -> node.id.equals(edge.from) || node.id.equals(edge.to))
                        .toList();
                removed.add(new RemovedInline(inline, i, inline.inlinePath, inline.name, node, edges));
                project.contents.remove(i);
                if (board != null && node != null) {
                    board.nodes.remove(node);
                    board.edges.removeIf(edges::contains);
                }
            }
        }
        return removed;
    }

    /** Applies the inline removals in the given direction (undo = re-add, redo = remove). */
    private void applyRemovedInline(List<RemovedInline> removed, boolean restore) {
        for (RemovedInline item : removed) {
            if (restore) {
                item.record().inlinePath = item.path();
                item.record().name = item.name();
                int index = Math.min(item.index(), project.contents.size());
                if (!project.contents.contains(item.record())) {
                    project.contents.add(index, item.record());
                }
                if (board != null && item.node() != null && !board.nodes.contains(item.node())) {
                    board.nodes.add(item.node());
                    for (CanvasEdge edge : item.edges()) {
                        if (!board.edges.contains(edge)) {
                            board.edges.add(edge);
                        }
                    }
                }
            } else {
                project.contents.remove(item.record());
                if (board != null && item.node() != null) {
                    board.nodes.remove(item.node());
                    board.edges.removeIf(item.edges()::contains);
                }
            }
        }
        if (!removed.isEmpty() && board != null) {
            project.boardsDirty = true;
        }
    }

    private static boolean underAmmoKey(String path, String key) {
        String prefix = "ammoTypes." + JsonPath.escapeSegment(key);
        return path.equals(prefix) || path.startsWith(prefix + ".");
    }

    /** The path after replacing an {@code ammoTypes.<oldKey>} prefix, or null when it does not match. */
    private static String remapAmmoPath(String path, String oldKey, String newKey) {
        String prefix = "ammoTypes." + JsonPath.escapeSegment(oldKey);
        if (!path.equals(prefix) && !path.startsWith(prefix + ".")) {
            return null;
        }
        return "ammoTypes." + JsonPath.escapeSegment(newKey) + path.substring(prefix.length());
    }

    private static boolean changedBy(ReferenceRewrite.Result rewrite, ContentRecord record) {
        return rewrite.changes().stream().anyMatch(change -> change.record() == record);
    }

    /**
     * Applies one side of a reference rewrite. The trees are deep-copied again because the same
     * command can be undone and redone any number of times, and live editors mutate shared values.
     */
    private void applyRewrite(ReferenceRewrite.Result result, boolean before) {
        for (ReferenceRewrite.Change change : result.changes()) {
            change.record().applyTree(
                (before ? change.before() : change.after()).deepCopy());
            change.record().dirty = true;
        }
        project.meta.contentOrder.clear();
        project.meta.contentOrder.addAll(before ? result.orderBefore() : result.orderAfter());
    }

    /** Writes a save plan, putting the unsaved flags back when the write does not finish. */
    private void writeSavePlan(ProjectIo.SavePlan plan) throws IOException {
        try {
            ProjectIo.write(plan);
        } catch (IOException failure) {
            plan.rollback();
            throw failure;
        }
    }

    // --- small conveniences the UI needs --------------------------------------------------------

    /** The bundle key for one of the four localisable fields of a record. */
    public String stringKey(ContentRecord record, String field) {
        requireProject();
        return NameRules.bundleKey(record.type, record.fullName(project.internalName()), field);
    }

    public String stringValue(ContentRecord record, String field, String locale) {
        String value = project.strings.get(stringKey(record, field), locale);
        return value == null ? "" : value;
    }

    public String classNameOf(ContentRecord record) {
        String resolved = registry.resolveClass(record.type, record.className());
        return resolved == null ? record.type.name() : resolved;
    }

    /** What a card and lists show: the English string, else the record name. */
    public String displayName(ContentRecord record) {
        String value = stringValue(record, "name", "en");
        return value == null || value.isBlank() ? record.name : value;
    }

    /** Localised content type name, e.g. 物品 for item under the Chinese UI. */
    public String typeName(ContentType type) {
        return Messages.t("ctype." + type.name());
    }

    /** The second line of a card / inspector header: localised type plus the class with its label. */
    public String subtitleOf(ContentRecord record) {
        String className = classNameOf(record);
        String base = registry.baseClassOf(record.type);
        // "方块 · Block" says the same thing twice; only spell the class out when it is a subclass.
        if (className.equals(base) || className.equals(record.type.defaultClass())) {
            return typeName(record.type);
        }
        return typeName(record.type) + " · " + Labels.clazz(className);
    }

    private void fireProjectChanged() {
        // Invalidate before dispatching: the listeners (and anything they read) must never see
        // findings from the previous state, whichever thread the mutation came from. The session
        // also schedules the background pass that replaces them.
        validation.invalidate();
        if (Platform.isFxApplicationThread()) {
            projectListeners.forEach(Runnable::run);
            return;
        }
        try {
            // Mutations from the shell's io thread must not touch the scene graph directly.
            Platform.runLater(() -> projectListeners.forEach(Runnable::run));
        } catch (IllegalStateException toolkitNotInitialized) {
            // Headless tests have no FX toolkit; run the listeners inline, as before.
            projectListeners.forEach(Runnable::run);
        }
    }

    /** Editing a file that came from a mod re-serialises it as standard JSON - comments and layout
     * in the original text are lost for that file. Tell the user once, not once per edit. */
    private void warnAboutReserialisation(ContentRecord record) {
        if (!project.reserializeNotice && record.raw != null) {
            project.reserializeNotice = true;
            log("note: editing '" + record.name + "' re-serialises its file as standard JSON -"
                + " comments in the original text are lost for that file");
        }
    }

    private void requireProject() {
        if (project == null) {
            throw new IllegalStateException("no project is open");
        }
    }

    private String uniqueName(ContentType type) {
        Set<String> taken = new LinkedHashSet<>();
        project.contents.stream()
            .filter(record -> record.type == type && !record.isInline())
            .forEach(record -> taken.add(record.name));
        String base = "new-" + type.name();
        int index = 1;
        while (taken.contains(base + "-" + index)) {
            index++;
        }
        return base + "-" + index;
    }

    /**
     * A canvas file name no other board uses. "Test Board" and "test board" would otherwise share
     * one file, and {@code ProjectIo.writeAuthoringLayer} would write one board's nodes over the
     * other's on every save; non-ASCII names used to collapse to "-.canvas" as well.
     */
    private String uniqueBoardFile(String name) {
        Set<String> taken = new LinkedHashSet<>();
        project.boards.forEach(existing -> taken.add(existing.file));
        String base = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        base = base.replaceAll("[^a-z0-9\\-_]+", "-").replaceAll("^-+", "").replaceAll("-+$", "");
        if (base.isEmpty() || NameRules.isWindowsReservedName(base)) {
            base = "board";
        }
        int maxStem = 120 - ".canvas".length();
        if (base.length() > maxStem) {
            base = base.substring(0, maxStem);
        }
        String candidate = base + ".canvas";
        int index = 2;
        while (taken.contains(candidate)) {
            candidate = base + "-" + index++ + ".canvas";
        }
        return candidate;
    }
}

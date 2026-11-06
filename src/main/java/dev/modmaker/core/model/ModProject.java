package dev.modmaker.core.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The whole editable mod: metadata, content records, string table, boards and assets.
 *
 * <p>This is the single source of truth both the importer and the exporter work against, so the
 * canvas never has to know about mod package layout.
 */
public final class ModProject {
    public static final int FORMAT = 1;

    public int format = FORMAT;
    public ModMeta meta = new ModMeta();
    /** Name of the meta file as found on disk (mod.json, plugin.hjson, ...). */
    public String metaFile = "";
    /** Meta file name before a format conversion; the old file is deleted on the next save. */
    public transient String previousMetaFile;
    /** Original meta text, written back verbatim while the metadata is untouched. */
    public String metaText = "";
    /**
     * True when the meta file could not be parsed on import: {@link #metaText} holds its original
     * bytes and {@link #meta} is empty. A format conversion must leave such a file alone - renaming
     * it and re-serialising would replace the author's text with an empty metadata object.
     */
    public transient boolean metaParseFailed;
    public boolean metaDirty;
    /** Set when canvas boards changed (drags, deletes, tidy) so the save/exit guard can react. */
    public transient boolean boardsDirty;
    /** The "editing rewrites the file as JSON" warning has been shown this session. */
    public transient boolean reserializeNotice;
    /** Content locales, "en" first. Drives which bundle files are written. */
    public List<String> locales = new ArrayList<>(List.of("en"));
    public StringTable strings = new StringTable();
    public List<ContentRecord> contents = new ArrayList<>();
    public List<Board> boards = new ArrayList<>();
    public List<AssetRef> assets = new ArrayList<>();

    /** Project folder on disk; null until the project is saved. */
    public transient Path root;
    /** Where the project was imported from, for display only. */
    public String importedFrom = "";
    /** Non-null when the authoring layer (modmaker/project.json or a board) could not be read. */
    public transient String authoringWarning;

    /** The Mindustry build family this tool targets; the major-version gate is derived from it. */
    public static final int TARGET_MAJOR = 160;
    /** Mindustry version whose sources produced the schemas (single source of truth). */
    public static final String SCHEMA_PROFILE = "160.7";

    /** Mindustry version whose sources produced the schemas, e.g. "160.7". */
    public String schemaProfile = SCHEMA_PROFILE;

    public String internalName() {
        return meta.internalName();
    }

    public ContentRecord contentById(String id) {
        for (ContentRecord record : contents) {
            if (record.id != null && record.id.equals(id)) {
                return record;
            }
        }
        return null;
    }

    /** Content names are unique per type because they become file names. */
    public ContentRecord contentByName(dev.modmaker.core.fmt.ContentType type, String name) {
        for (ContentRecord record : contents) {
            if (record.type == type && record.name.equals(name)) {
                return record;
            }
        }
        return null;
    }

    public Set<String> assetPaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (AssetRef asset : assets) {
            paths.add(asset.path);
        }
        return paths;
    }

    public String displayName() {
        return meta.displayName == null || meta.displayName.isEmpty() ? meta.name : meta.displayName;
    }

    /**
     * A deep, read-only copy of everything a validation pass reads.
     *
     * <p>Validating off the model's thread used to be impossible: the FX thread owns the records and
     * edits their field trees in place, so a worker reading them could see a torn document. The copy
     * is taken on the model's thread and handed to the worker, which is the standard way to move a
     * read-only pass off the UI thread without locking the editor.
     *
     * <p>Only the parts validation needs are copied (fields, names, flags, bundles, boards, assets);
     * the copy is not meant to be saved or edited.
     */
    public ModProject snapshot() {
        ModProject copy = new ModProject();
        copy.format = format;
        copy.meta = meta.snapshot();
        copy.metaFile = metaFile;
        copy.previousMetaFile = previousMetaFile;
        copy.metaText = metaText;
        copy.metaParseFailed = metaParseFailed;
        copy.metaDirty = metaDirty;
        copy.boardsDirty = boardsDirty;
        copy.reserializeNotice = reserializeNotice;
        copy.locales = new ArrayList<>(locales);
        copy.strings = strings.snapshot();
        copy.contents = new ArrayList<>(contents.size());
        for (ContentRecord record : contents) {
            copy.contents.add(record.snapshot());
        }
        copy.boards = new ArrayList<>(boards.size());
        for (Board board : boards) {
            copy.boards.add(board.snapshot());
        }
        copy.assets = new ArrayList<>(assets.size());
        for (AssetRef asset : assets) {
            copy.assets.add(asset.snapshot());
        }
        copy.root = root;
        copy.importedFrom = importedFrom;
        copy.authoringWarning = authoringWarning;
        copy.schemaProfile = schemaProfile;
        return copy;
    }
}

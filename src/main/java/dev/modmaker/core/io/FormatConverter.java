package dev.modmaker.core.io;

import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

/**
 * Bulk conversion between the two content formats the game accepts. Conversion re-serialises the
 * parsed field trees through {@link dev.modmaker.core.json.HjsonWriter} or the canonical JSON
 * writer: the data survives exactly, comments and hand formatting in converted files do not.
 * Saving materialises the renamed files (and prunes the old ones), and building stays a plain
 * copy of the saved folder - there is no separate export-time conversion.
 */
public final class FormatConverter {

    private FormatConverter() {
    }

    /** File-backed records whose extension differs from the target format. */
    public static int pendingCount(ModProject project, String ext) {
        return (int) project.contents.stream()
            .filter(record -> !record.isInline())
            // convert() skips these, so counting them made the dialog promise work that never
            // happened.
            .filter(record -> !record.parseFailed)
            .filter(record -> !ext.equals(record.ext == null ? "json" : record.ext))
            .count();
    }

    /**
     * True when the meta file is a mod.json/mod.hjson that the conversion would rename. A meta that
     * could not be parsed is left alone: it is kept as raw text, so renaming and re-serialising it
     * would replace the author's metadata with an empty object.
     */
    public static boolean metaPending(ModProject project, String ext) {
        if (project.metaParseFailed) {
            return false;
        }
        return isModMeta(project.metaFile) && !project.metaFile.equals("mod." + ext);
    }

    /**
     * Flips every file-backed record (and a mod.json/mod.hjson meta) to the target extension.
     * Converted records are marked dirty and lose their original bytes, so the next save
     * re-serialises them in the new format; plugin.* meta files are left alone.
     */
    public static void convert(ModProject project, String ext) {
        if (!ext.equals("json") && !ext.equals("hjson")) {
            throw new IllegalArgumentException("unsupported content format: " + ext);
        }
        for (ContentRecord record : project.contents) {
            if (record.isInline()) {
                continue;
            }
            String current = record.ext == null ? "json" : record.ext;
            if (current.equals(ext)) {
                continue;
            }
            if (record.parseFailed) {
                // The field tree is empty; re-serialising would replace the file with an empty
                // object. The original bytes are kept verbatim until the file is fixed by hand.
                continue;
            }
            record.ext = ext;
            record.dirty = true;
            record.raw = null;
        }
        if (metaPending(project, ext)) {
            project.previousMetaFile = project.metaFile;
            project.metaFile = "mod." + ext;
            project.metaDirty = true;
        }
    }

    private static boolean isModMeta(String metaFile) {
        return "mod.json".equals(metaFile) || "mod.hjson".equals(metaFile);
    }
}

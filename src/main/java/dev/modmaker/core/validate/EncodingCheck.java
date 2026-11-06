package dev.modmaker.core.validate;

import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

/**
 * Encoding damage the game's HJSON reader cannot survive.
 *
 * <p>Windows Notepad saves UTF-8 with a BOM, which the reader takes for the first key. Only untouched
 * records are flagged: edited ones are re-serialised clean on save.
 */
final class EncodingCheck implements ProjectCheck {

    /** UTF-8 byte order mark; Windows Notepad adds it and the game's HJSON reader chokes on it. */
    private static final String UTF8_BOM = "\uFEFF";

    @Override
    public String id() {
        return "encoding";
    }

    @Override
    public boolean requiresInternalName() {
        return false;
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        if (!project.metaDirty && project.metaText != null && project.metaText.startsWith(UTF8_BOM)) {
            context.report(Issue.error("validate.encoding.metaBom"));
        }
        for (ContentRecord record : project.contents) {
            if (!record.isInline() && !record.dirty && record.raw != null
                && record.raw.startsWith(UTF8_BOM)) {
                context.report(Issue.errorOn(record.id, "validate.encoding.contentBom", record.name));
            }
        }
    }
}

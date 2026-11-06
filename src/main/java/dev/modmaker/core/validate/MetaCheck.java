package dev.modmaker.core.validate;

import dev.modmaker.core.model.ModMeta;

import java.util.Arrays;

/**
 * The metadata that decides whether anything else can even be addressed: the file name the game
 * probes and the mod name every content name is prefixed with.
 */
final class MetaCheck implements ProjectCheck {

    @Override
    public String id() {
        return "meta";
    }

    @Override
    public boolean requiresInternalName() {
        return false;
    }

    @Override
    public void check(ValidationContext context) {
        String metaFile = context.project().metaFile;
        // The game only probes four meta file names at the package root; anything else is not found.
        if (metaFile != null && !metaFile.isEmpty()
            && !Arrays.asList(ModMeta.META_FILES).contains(metaFile)) {
            context.report(Issue.error("validate.meta.badFileName", metaFile));
        }
        if (context.project().meta.name == null || context.project().meta.name.isBlank()) {
            context.report(Issue.error("validate.meta.emptyName"));
        }
    }
}

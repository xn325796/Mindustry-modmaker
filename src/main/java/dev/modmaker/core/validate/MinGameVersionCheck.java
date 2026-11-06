package dev.modmaker.core.validate;

import dev.modmaker.core.model.ModMeta;
import dev.modmaker.core.model.ModProject;

import java.util.regex.Pattern;

/**
 * The version gate that decides whether the game loads the package at all. A mod that fails it is
 * marked unsupported/outdated and dropped whole - no content, no bundles, no scripts
 * (Mods.java:1314-1331, 575, 614) - and the drop is silent, so every finding here is the
 * difference between "loads" and "nothing happens".
 *
 * <p>The game reads only the number before the first dot ({@code getMinMajor}, Mods.java:1457-1461)
 * and falls back to 0 on anything it cannot parse, which is below both floors it enforces:
 * {@code minModGameVersion} 136 for every mod (Vars.java:53) and {@code minJavaModGameVersion} 154
 * for class mods (Vars.java:55, second gate at Mods.java:1180). {@code legacyCompatible: true} is
 * the only escape from the two floors (Mods.java:1329-1331); it is kept in {@link ModMeta#extra} and
 * read through {@link ModMeta#legacyCompatible()} (ModMetaCodec.java:36-40).
 */
final class MinGameVersionCheck implements ProjectCheck {

    /** The build family this tool targets; newer majors will not load here. */
    private static final int CURRENT_MAJOR = ModProject.TARGET_MAJOR;
    /** Vars.java:53 - every mod below this is outdated unless legacyCompatible. */
    private static final int MIN_MOD_MAJOR = 136;
    /** Vars.java:55 - class mods below this are outdated unless legacyCompatible. */
    private static final int MIN_JAVA_MOD_MAJOR = 154;

    /** The shape the game can read: a plain build number with at most two dot-separated parts. */
    private static final Pattern BUILD_NUMBER = Pattern.compile("^\\d+(\\.\\d+){0,2}$");

    @Override
    public String id() {
        return "minGameVersion";
    }

    @Override
    public boolean requiresInternalName() {
        return false;
    }

    @Override
    public void check(ValidationContext context) {
        ModMeta meta = context.project().meta;
        String value = meta.minGameVersion;
        if (value == null || value.isBlank()) {
            // Omitted or blank: the game defaults it to "0" (Mods.java:1417) -> outdated -> dropped.
            context.report(Issue.error("validate.meta.minGameVersion.empty"));
            return;
        }
        String trimmed = value.trim();
        if (!BUILD_NUMBER.matcher(trimmed).matches()) {
            // Anything else parses as 0 (Mods.java:1457-1461) -> outdated -> dropped.
            context.report(Issue.error("validate.meta.minGameVersion.badFormat", trimmed));
            return;
        }
        int major = leadingNumber(trimmed);
        boolean classMod = meta.java || (meta.mainClass != null && !meta.mainClass.isBlank());
        boolean legacyCompatible = meta.legacyCompatible();
        if (major > CURRENT_MAJOR) {
            // Version.isAtLeast fails (Version.java:64-73): the mod is listed but never loaded.
            // For a class mod that verdict cannot be trusted to be the author's intent, so it is
            // a hard error there and a warning for pure content mods.
            context.report(classMod
                ? Issue.error("validate.meta.minGameVersion.tooHigh", trimmed, major)
                : Issue.warning("validate.meta.minGameVersion.tooHigh", trimmed, major));
        }
        if (classMod && major < MIN_JAVA_MOD_MAJOR && !legacyCompatible) {
            context.report(Issue.error("validate.meta.minGameVersion.classModTooOld", trimmed));
        }
        if (major < MIN_MOD_MAJOR && !legacyCompatible) {
            context.report(Issue.error("validate.meta.minGameVersion.tooOld", trimmed));
        }
    }

    /** {@code Strings.parseInt(..., 0)} semantics: the digits before the first dot, 0 on overflow. */
    private static int leadingNumber(String value) {
        int dot = value.indexOf('.');
        try {
            return Integer.parseInt(dot == -1 ? value : value.substring(0, dot));
        } catch (NumberFormatException tooBig) {
            return 0;
        }
    }
}

package dev.modmaker.core.validate;

import dev.modmaker.core.fmt.BundleCodec;
import dev.modmaker.core.model.ModProject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bundle keys that name neither this mod's content nor vanilla content - leftovers of a rename. */
final class StringsCheck implements ProjectCheck {

    @Override
    public String id() {
        return "strings";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        for (Map.Entry<String, LinkedHashMap<String, String>> entry
            : project.strings.values.entrySet()) {
            BundleCodec.ContentKey key = BundleCodec.parseKey(entry.getKey());
            if (key == null) {
                continue;
            }
            String bare = BundleCodec.contentNameOf(key.fullName(), context.internalName());
            if (project.contentByName(key.type(), bare) == null
                && !context.registry().isVanillaName(key.type(), bare)) {
                // A key that names neither this mod's content nor vanilla content is a leftover: the
                // mod's own bundle file still carries a string for content that was renamed or
                // deleted. Overriding a vanilla entry (team.blue.name and the like) is legitimate -
                // Mods.java loads mod bundles into the game's own bundle chain.
                context.report(Issue.warning("validate.bundle.orphanKey", entry.getKey()));
            }
        }
    }
}

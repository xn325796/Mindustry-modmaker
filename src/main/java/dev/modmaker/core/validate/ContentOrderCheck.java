package dev.modmaker.core.validate;

import dev.modmaker.core.model.ModProject;

import java.util.LinkedHashSet;
import java.util.Set;

/** Entries of the meta's content-order list that name no content the mod has. */
final class ContentOrderCheck implements ProjectCheck {

    @Override
    public String id() {
        return "contentOrder";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        Set<String> names = new LinkedHashSet<>();
        project.contents.stream().filter(record -> !record.isInline())
            .forEach(record -> names.add(record.name));
        for (String ordered : project.meta.contentOrder) {
            if (!names.contains(ordered)) {
                context.report(Issue.warning("validate.contentOrder.unknown", ordered));
            }
        }
    }
}

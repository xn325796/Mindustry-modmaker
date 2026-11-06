package dev.modmaker.core.validate;

import dev.modmaker.core.fmt.SpriteRegions;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Shared, read-only state one validation pass needs: the project, the schema registry and the
 * accumulated findings.
 *
 * <p>Derived values that several checks read - the internal name and the atlas regions the project's
 * sprites produce - are computed once here instead of per check. The context is not thread-safe; a
 * pass owns one instance, and {@link Validator} never shares it.
 */
public final class ValidationContext {

    private final ModProject project;
    private final SchemaRegistry registry;
    private final List<Issue> issues = new ArrayList<>();
    private Set<String> spriteRegions;

    public ValidationContext(ModProject project, SchemaRegistry registry) {
        this.project = project;
        this.registry = registry;
    }

    public ModProject project() {
        return project;
    }

    public SchemaRegistry registry() {
        return registry;
    }

    /** The prefix every content name, sprite region and reference of this mod carries. */
    public String internalName() {
        return project.internalName();
    }

    /** Records one finding; checks never build the result list themselves. */
    public void report(Issue issue) {
        issues.add(issue);
    }

    public void reportAll(List<Issue> findings) {
        issues.addAll(findings);
    }

    /** The findings so far, in the order the checks ran. */
    public List<Issue> issues() {
        return Collections.unmodifiableList(issues);
    }

    /** Atlas regions the project's sprites actually produce, computed on first use. */
    public Set<String> spriteRegions() {
        if (spriteRegions == null) {
            spriteRegions = SpriteRegions.regionsOf(project, internalName());
        }
        return spriteRegions;
    }
}

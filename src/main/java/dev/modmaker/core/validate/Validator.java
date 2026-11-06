package dev.modmaker.core.validate;

import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a project against the rules the game loader actually enforces, plus mistakes that are silent
 * in game but wrong (missing sprites, dangling references, bundle keys with no content behind them).
 *
 * <p>The rules used to live in one 1 000-line {@code validate()} with a dozen private helpers, so
 * every new rule edited the main flow and a caller could not ask for only one family of findings.
 * They are now independent {@link ProjectCheck}s, run in a fixed order by this class; the checks are
 * the extension seam, and this class only aggregates.
 *
 * <p>Deliberately conservative: only things that make content fail to load are errors. The
 * gameplay-level pitfall checks (bullet reach, drill tiers, recipe buffers, ...) come from the
 * modding-tutorial distillation in the Obsidian note "Mindustry-JSON键值工程指南" and stay at warning
 * level: the game tolerates them, the player just sees the consequences.
 *
 * <p>Unknown JSON fields are deliberately not checked, and this comment used to claim they were a
 * warning here when they never were. The game only warns about them - {@code ignoreUnknownFields} is
 * true (ContentParser.java:58), so an unknown key is logged and skipped (ContentParser.java:1277-1280)
 * - and a key the schema does not list is far more likely to be a hole in the generated field data
 * than an authoring mistake, so warning on all of them would bury real problems in noise. They are
 * surfaced where they can be judged instead: the inspector shows the record's whole field tree in its
 * raw-JSON region, schema-known keys or not.
 */
public final class Validator {

    private final SchemaRegistry registry;
    private final List<ProjectCheck> checks;

    public Validator(SchemaRegistry registry) {
        this(registry, defaultChecks());
    }

    /** Builds a validator over an explicit rule set, in the order given. */
    public Validator(SchemaRegistry registry, List<ProjectCheck> checks) {
        this.registry = registry;
        this.checks = List.copyOf(checks);
    }

    /**
     * The built-in rules in the order they run. Meta, the minGameVersion gate and encoding come
     * first: the name decides whether records can be addressed at all, and the version gate decides
     * whether the game even loads the package. The per-record rules must precede the reference
     * sweep so a broken record is reported before its references.
     */
    public static List<ProjectCheck> defaultChecks() {
        return List.of(
            new MetaCheck(),
            new MinGameVersionCheck(),
            new EncodingCheck(),
            new RecordCheck(),
            new ReferenceCheck(),
            new ContentOrderCheck(),
            new StringsCheck(),
            new TeamCheck(),
            new BoardCheck(),
            new BulletReachCheck(),
            new DrillTierCheck(),
            new EnvironmentSpriteCheck(),
            new CrafterOutputCheck(),
            new RecipeCapacityCheck(),
            new SoundReferenceCheck(),
            new PlanetCheck());
    }

    public List<ProjectCheck> checks() {
        return checks;
    }

    /** Runs every rule against the project. Pure reads; no check may mutate the project. */
    public List<Issue> validate(ModProject project) {
        return run(project, checks);
    }

    /**
     * Runs only the named rules, keeping their canonical order. Used where a scene knows which
     * findings can have changed - after a rename, only the reference sweep is worth re-running.
     *
     * @throws IllegalArgumentException for an unknown check id
     */
    public List<Issue> validate(ModProject project, Set<String> onlyIds) {
        if (onlyIds == null || onlyIds.isEmpty()) {
            return validate(project);
        }
        List<ProjectCheck> selected = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>();
        for (ProjectCheck check : checks) {
            known.add(check.id());
            if (onlyIds.contains(check.id())) {
                selected.add(check);
            }
        }
        List<String> unknown = onlyIds.stream().filter(id -> !known.contains(id)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown validation check(s): " + unknown
                + "; known: " + known);
        }
        return run(project, selected);
    }

    private List<Issue> run(ModProject project, Collection<ProjectCheck> selected) {
        ValidationContext context = new ValidationContext(project, registry);
        for (ProjectCheck check : selected) {
            if (check.requiresInternalName() && project.internalName().isEmpty()) {
                continue;
            }
            check.check(context);
        }
        return List.copyOf(context.issues());
    }
}

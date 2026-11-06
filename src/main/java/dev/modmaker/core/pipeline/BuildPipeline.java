package dev.modmaker.core.pipeline;

import dev.modmaker.core.io.ExportReport;
import dev.modmaker.core.io.ModExporter;
import dev.modmaker.core.io.ProjectIo;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.core.validate.Issue;
import dev.modmaker.core.validate.Validator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The one "load, validate, save, export" flow shared by the headless CLI and the desktop app.
 *
 * <p>Both front ends used to reimplement it: the CLI opened the project itself and drove
 * {@link Validator} + {@link ProjectIo} + {@link ModExporter}, while the UI's controller did the same
 * again through its save-plan and build methods. Anything that changed - a new validation stage, a
 * different save order, a new artifact layout - had to be changed twice and could drift. The
 * pipeline is that flow, and the front ends only add what is genuinely theirs: the CLI's exit codes
 * and manifests, the UI's threading and overwrite prompts.
 *
 * <p>Split in phases on purpose. {@link #plan} is the model half and must run on the thread that owns
 * the model (the FX thread in the app); {@link #write} only touches the filesystem and may run on a
 * worker. {@link #build} runs both in order for callers that own the model outright.
 */
public final class BuildPipeline {

    private final SchemaRegistry registry;
    private final Validator validator;

    public BuildPipeline(SchemaRegistry registry) {
        this.registry = registry;
        this.validator = new Validator(registry);
    }

    public SchemaRegistry registry() {
        return registry;
    }

    /**
     * A build decided while the model could still be read: the validation findings, the folder the
     * artifact is packed from, and the save the write phase has to perform first.
     */
    public record Plan(Path root, String internalName, List<Issue> issues,
        ProjectIo.SavePlan save) {

        public boolean hasErrors() {
            return issues.stream().anyMatch(Issue::isError);
        }

        public long errorCount() {
            return issues.stream().filter(Issue::isError).count();
        }

        public long warningCount() {
            return issues.stream().filter(issue -> !issue.isError()).count();
        }
    }

    /** Opens a project folder the way every command needs it, with an optional schema profile. */
    public ModProject open(Path root, String profile) throws IOException {
        ModProject project = ProjectIo.open(root, registry);
        if (profile != null && !profile.isBlank()) {
            project.schemaProfile = profile;
        }
        return project;
    }

    /**
     * Runs the whole rule set over an open project. Pure reads, but not thread-safe with respect to
     * edits: call it either on the model's thread or on a snapshot (see
     * {@code ModProject#snapshot()}).
     */
    public List<Issue> validate(ModProject project) {
        return validator.validate(project);
    }

    /**
     * The model half of a build: validates and prepares the save. <b>Must run on the thread that owns
     * the model</b>, like {@link ProjectIo#plan}.
     */
    public Plan plan(ModProject project) throws IOException {
        return plan(project, List.copyOf(validator.validate(project)));
    }

    /**
     * The same, with findings the caller already computed (the UI validates a snapshot off the FX
     * thread, so the pass does not have to run again).
     */
    public Plan plan(ModProject project, List<Issue> issues) throws IOException {
        Path root = ModExporter.requireRoot(project);
        String internal = ModExporter.requireInternalName(project);
        return new Plan(root, internal, List.copyOf(issues), ProjectIo.plan(project, root));
    }

    /**
     * The disk half of a build: saves the project and packs the artifact. A failed save rolls its
     * unsaved flags back, so a half-written save can never look persisted.
     */
    public ExportReport write(Plan plan, Path destination, boolean zip) throws IOException {
        try {
            ProjectIo.write(plan.save());
        } catch (IOException failure) {
            plan.save().rollback();
            throw failure;
        }
        return zip
            ? ModExporter.buildZip(plan.root(), plan.internalName(), destination, plan.issues())
            : ModExporter.buildFolder(plan.root(), plan.internalName(), destination, plan.issues());
    }

    /** Builds without splitting the phases; for callers that already own the model. */
    public ExportReport build(ModProject project, Path destination, boolean zip) throws IOException {
        return write(plan(project), destination, zip);
    }
}

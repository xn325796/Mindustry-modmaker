package dev.modmaker.ui;

import dev.modmaker.core.io.ExportReport;
import dev.modmaker.core.io.ImportCancelledException;
import dev.modmaker.core.io.ImportProgress;
import dev.modmaker.core.io.ImportReport;
import dev.modmaker.core.io.ModImporter;
import dev.modmaker.core.io.ModPackage;
import dev.modmaker.core.io.ModPackageReader;
import dev.modmaker.core.io.ProjectIo;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.pipeline.BuildPipeline;
import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.core.validate.Issue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The stateless orchestration of a project: reading folders and packages off disk, and driving the
 * shared build pipeline. Split out of {@link ProjectController} so that class owns only the live
 * model, the edit commands and the view notifications; this half never touches a view and may run
 * on the file worker.
 */
public final class ProjectActions {

    private final SchemaRegistry registry;
    private final BuildPipeline pipeline;

    public ProjectActions(SchemaRegistry registry) {
        this.registry = registry;
        this.pipeline = new BuildPipeline(registry);
    }

    /** Reads a project folder without publishing it; the caller owns the returned model. */
    public ModProject readProject(Path root) throws IOException {
        return ProjectIo.open(root, registry);
    }

    /** A package read off disk and unpacked, waiting to be published on the model's thread. */
    public record ImportResult(ModProject project, Path root, ImportReport report) {
    }

    public ImportResult readImport(Path source, Path destinationRoot,
        Predicate<Path> overwriteApprover, Consumer<String> log) throws IOException {
        return readImport(source, destinationRoot, ImportProgress.NONE, overwriteApprover, log);
    }

    /**
     * Reads a mod package (zip or folder) and unpacks it into a project folder, without publishing
     * it. Reporting each phase to {@code progress} and honouring its cancel between files; a cancel
     * after the write phase has started removes the half-written project folder when the import
     * created it, while an existing folder the user agreed to overwrite is left as it was.
     */
    public ImportResult readImport(Path source, Path destinationRoot, ImportProgress progress,
        Predicate<Path> overwriteApprover, Consumer<String> log) throws IOException {
        Path root = null;
        boolean folderWasEmpty = false;
        boolean unpackStarted = false;
        try {
            ModPackage pkg = ModPackageReader.read(source, progress);
            ModImporter.Result result = new ModImporter(registry).importPackage(pkg, progress);
            String internal = result.project().internalName();
            if (!internal.isEmpty() && !dev.modmaker.core.fmt.NameRules.isFolderSafe(internal)) {
                // A meta name with separators would place the unpacked project outside the chosen
                // destination - refuse the import instead of trusting the name.
                throw new IOException("the mod's name is not a safe folder name: '" + internal + "'");
            }
            root = internal.isEmpty() ? destinationRoot : destinationRoot.resolve(internal);
            // Importing a folder into itself (or into a folder that already holds it) copies the source
            // into its own subtree: a folder import whose destination was the mod folder itself left a
            // <mod>/<internalName>/ copy inside the mod, doubling the tree with no error. Refuse the
            // overlap before anything is written.
            if (Files.isDirectory(source)) {
                String sourcePath = source.toAbsolutePath().normalize().toString();
                String targetPath = root.toAbsolutePath().normalize().toString();
                char separator = java.io.File.separatorChar;
                if (sourcePath.equalsIgnoreCase(targetPath)
                    || sourcePath.startsWith(targetPath + separator)
                    || targetPath.startsWith(sourcePath + separator)) {
                    throw new IOException("refusing to import '" + sourcePath + "' into '" + targetPath
                        + "': the project folder would sit inside its own source and copy the mod"
                        + " into itself");
                }
            }
            folderWasEmpty = !Files.isDirectory(root) || isDirectoryEmpty(root);
            if (!folderWasEmpty && !overwriteApprover.test(root)) {
                log.accept("import cancelled: " + root + " already exists");
                throw new IOException("import cancelled: " + root + " already exists");
            }
            unpackStarted = true;
            ProjectIo.unpack(result.project(), pkg, root, progress);
            return new ImportResult(result.project(), root, result.report());
        } catch (ImportCancelledException cancelled) {
            if (unpackStarted) {
                if (folderWasEmpty) {
                    // The folder did not exist (or held nothing) before the import: a cancelled
                    // write must not leave a half-materialised project the user never kept.
                    deleteTreeQuietly(root);
                    log.accept("import cancelled: removed the half-written project folder " + root);
                } else {
                    log.accept("import cancelled: kept the existing files in " + root);
                }
            } else {
                log.accept("import cancelled: " + source);
            }
            throw cancelled;
        }
    }

    /** The model half of a build: validates and captures the values the packer needs. */
    public BuildPipeline.Plan planBuild(ModProject project) throws IOException {
        return pipeline.plan(project);
    }

    /** A build with findings already computed from a snapshot, so rules do not run twice. */
    public BuildPipeline.Plan planBuild(ModProject project, List<Issue> issues) throws IOException {
        return pipeline.plan(project, issues);
    }

    /** The disk half of a folder build: pure file work, so the worker thread may run it. */
    public ExportReport writeBuildFolder(BuildPipeline.Plan plan, Path destination)
        throws IOException {
        return pipeline.write(plan, destination, false);
    }

    /** The disk half of a zip build: pure file work, so the worker thread may run it. */
    public ExportReport writeBuildZip(BuildPipeline.Plan plan, Path zipFile) throws IOException {
        return pipeline.write(plan, zipFile, true);
    }

    /** Best-effort recursive delete of a folder a cancelled import had just created. */
    private static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException locked) {
                    // A single locked file stays behind; the rest of the half-written tree is
                    // still removed. There is no second chance to take here anyway.
                }
            }
        } catch (IOException unreachable) {
            // The tree cannot even be walked (locked or vanished); nothing more can be cleaned.
        }
    }

    private static boolean isDirectoryEmpty(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (IOException failure) {
            // An unreadable folder counts as "not empty": reporting it empty skipped the overwrite
            // confirmation entirely, so a locked destination folder was silently overwritten.
            return false;
        }
    }
}

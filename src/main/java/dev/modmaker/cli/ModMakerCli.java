package dev.modmaker.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.io.BuildManifest;
import dev.modmaker.core.io.ExportReport;
import dev.modmaker.core.io.ModExporter;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.pipeline.BuildPipeline;
import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.core.validate.Issue;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The headless entry point: validate and build without starting JavaFX.
 *
 * <p>Everything it touches lives in {@code core}; the class must never reference a JavaFX type, or
 * the launcher's classpath-only setup would demand the graphics module for a CI build.
 */
public final class ModMakerCli {

    public static final int OK = 0;
    /** Validation refused the project (errors, or warnings under {@code --strict}). */
    public static final int VALIDATION_FAILED = 2;
    /** Building or writing the artifact failed. */
    public static final int BUILD_FAILED = 3;
    /** Bad arguments, or a project that could not be opened at all. */
    public static final int USAGE = 5;

    private static final String DEFAULT_PROJECT = "<project>";
    private static final String DEFAULT_OUT = "<dir|zip>";

    private ModMakerCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** True when the first argument is a command this CLI owns, so the launcher can skip JavaFX. */
    public static boolean handles(String[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        return switch (args[0]) {
            case "validate", "build", "--help", "-h", "help", "--version", "-V", "version" -> true;
            default -> false;
        };
    }

    /** Runs one command and returns its process exit code. */
    public static int run(String[] args) {
        if (args == null || args.length == 0) {
            printHelp(System.out);
            return USAGE;
        }
        try {
            return switch (args[0]) {
                case "--help", "-h", "help" -> {
                    printHelp(System.out);
                    yield OK;
                }
                case "--version", "-V", "version" -> {
                    System.out.println(BuildManifest.TOOL + " " + BuildManifest.TOOL_VERSION);
                    yield OK;
                }
                case "validate" -> validate(args);
                case "build" -> build(args);
                default -> {
                    System.err.println("modmaker: unknown command '" + args[0] + "'");
                    printHelp(System.err);
                    yield USAGE;
                }
            };
        } catch (UsageException problem) {
            System.err.println("modmaker: " + problem.getMessage());
            return USAGE;
        }
    }

    // --- commands -------------------------------------------------------------------------------

    private static int validate(String[] args) throws UsageException {
        Options options = parse(args, false);
        BuildPipeline pipeline;
        ModProject project;
        try {
            pipeline = new BuildPipeline(SchemaRegistry.loadDefault());
            project = pipeline.open(options.project(), options.profile());
        } catch (Exception failure) {
            return projectFailure(failure);
        }
        List<Issue> issues = pipeline.validate(project);
        printIssues(issues);
        if (options.json() != null) {
            try {
                writeReport(options.json(), project.root, project.schemaProfile, issues);
            } catch (IOException failure) {
                System.err.println("modmaker: cannot write the report: " + failure.getMessage());
                return USAGE;
            }
        }
        return exitCode(issues, options.strict());
    }

    private static int build(String[] args) throws UsageException {
        Options options = parse(args, true);
        if (options.out() == null) {
            throw new UsageException("build needs --out <dir|zip>");
        }
        BuildPipeline pipeline;
        ModProject project;
        try {
            pipeline = new BuildPipeline(SchemaRegistry.loadDefault());
            project = pipeline.open(options.project(), options.profile());
        } catch (Exception failure) {
            return projectFailure(failure);
        }
        // A project without a usable internal name cannot be packed at all; that stays a usage
        // error (exit 5), as it was before the pipeline existed.
        try {
            ModExporter.requireInternalName(project);
        } catch (IOException failure) {
            System.err.println("modmaker: " + failure.getMessage());
            return USAGE;
        }
        // The pipeline's plan is the same "validate + decide the save" the UI runs; the CLI only
        // adds its own exit-code, target and manifest policy on top.
        BuildPipeline.Plan plan;
        try {
            plan = pipeline.plan(project);
        } catch (IOException failure) {
            System.err.println("modmaker: cannot save the project: " + failure.getMessage());
            return BUILD_FAILED;
        }
        List<Issue> issues = plan.issues();
        printIssues(issues);
        int validationExit = exitCode(issues, options.strict());
        if (validationExit != OK) {
            return validationExit;
        }

        String internal = plan.internalName();
        boolean zip = options.out().getFileName() != null && options.out().getFileName().toString()
            .toLowerCase(Locale.ROOT).endsWith(".zip");
        Path manifestPath = options.manifest() != null
            ? options.manifest()
            : defaultManifestPath(options.out());

        // A build target nested in the project is a build failure, not a manifest-path problem;
        // report it first so the exit code stays 3 as documented.
        Path buildTarget = zip
            ? options.out().toAbsolutePath().normalize()
            : options.out().toAbsolutePath().normalize().resolve(internal).normalize();
        Path projectRoot = project.root.toAbsolutePath().normalize();
        if (buildTarget.startsWith(projectRoot) && !buildTarget.equals(projectRoot)) {
            System.err.println("modmaker: build target is inside the project folder: " + buildTarget);
            return BUILD_FAILED;
        }

        try {
            requireManifestOutsidePayload(project.root, options.out(), internal, zip, manifestPath);
        } catch (IOException failure) {
            System.err.println("modmaker: " + failure.getMessage());
            return USAGE;
        }

        try {
            ExportReport report = pipeline.write(plan, options.out(), zip);
            String sourceHash = BuildManifest.sourceHash(project.root);
            String artifactHash = zip
                ? BuildManifest.sha256(options.out())
                : BuildManifest.treeHash(options.out().resolve(internal));
            JsonObject manifest = BuildManifest.create(project, project.schemaProfile,
                report.files().size(), report.bytes(), sourceHash, artifactHash);
            BuildManifest.write(manifest, manifestPath);
            System.out.println("built " + report.files().size() + " file(s), " + report.bytes()
                + " bytes -> " + report.target());
            System.out.println("manifest: " + manifestPath.toAbsolutePath());
            System.out.println("sha256: " + artifactHash);
            return OK;
        } catch (IOException failure) {
            System.err.println("modmaker: build failed: " + failure.getMessage());
            return BUILD_FAILED;
        }
    }

    private static int projectFailure(Exception failure) {
        System.err.println("modmaker: cannot open the project: " + failure.getMessage());
        return USAGE;
    }

    private static int exitCode(List<Issue> issues, boolean strict) {
        long errors = issues.stream().filter(Issue::isError).count();
        long warnings = issues.stream().filter(issue -> !issue.isError()).count();
        if (errors > 0) {
            return VALIDATION_FAILED;
        }
        return strict && warnings > 0 ? VALIDATION_FAILED : OK;
    }

    private static void printIssues(List<Issue> issues) {
        long errors = issues.stream().filter(Issue::isError).count();
        System.out.println("validation: " + errors + " error(s), "
            + (issues.size() - errors) + " warning(s)");
        for (Issue issue : issues) {
            String id = issue.contentId() == null ? "" : " [" + issue.contentId() + "]";
            System.out.println("  " + issue.level() + " " + issue.key() + id + ": "
                + issue.message());
        }
    }

    private static void writeReport(Path target, Path projectRoot, String profile,
        List<Issue> issues) throws IOException {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("project", projectRoot == null ? "" : projectRoot.toAbsolutePath().toString());
        report.addProperty("profile", profile == null ? "" : profile);
        JsonArray array = new JsonArray();
        for (Issue issue : issues) {
            JsonObject entry = new JsonObject();
            entry.addProperty("severity", issue.level().name());
            entry.addProperty("code", issue.key());
            if (issue.contentId() == null) {
                entry.add("contentId", JsonNull.INSTANCE);
            } else {
                entry.addProperty("contentId", issue.contentId());
            }
            entry.addProperty("message", issue.message());
            JsonArray args = new JsonArray();
            for (Object argument : issue.args()) {
                args.add(argument(argument));
            }
            entry.add("args", args);
            array.add(entry);
        }
        report.add("issues", array);
        Path absolute = target.toAbsolutePath();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        Files.writeString(absolute, report.toString() + "\n", StandardCharsets.UTF_8);
    }

    private static JsonElement argument(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    // --- argument parsing -----------------------------------------------------------------------

    private record Options(Path project, Path out, Path manifest, Path json, boolean strict,
        String profile) {
    }

    private static Options parse(String[] args, boolean building) throws UsageException {
        if (args.length < 2) {
            throw new UsageException(building ? "build needs <project> and --out" : "validate needs <project>");
        }
        Path project = Path.of(args[1]);
        Path out = null;
        Path manifest = null;
        Path json = null;
        boolean strict = false;
        String profile = null;
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--strict" -> strict = true;
                case "--out" -> {
                    requireBuilding(building, arg);
                    out = Path.of(value(args, ++i, arg));
                }
                case "--manifest" -> {
                    requireBuilding(building, arg);
                    manifest = Path.of(value(args, ++i, arg));
                }
                case "--json" -> {
                    if (building) {
                        throw new UsageException("--json is only supported by validate");
                    }
                    json = Path.of(value(args, ++i, arg));
                }
                case "--profile" -> {
                    String version = value(args, ++i, arg);
                    if (version.isBlank()) {
                        throw new UsageException("--profile needs a version");
                    }
                    profile = version.trim();
                }
                case "--help", "-h" -> throw new UsageException("see --help");
                default -> throw new UsageException("unknown option '" + arg + "'");
            }
        }
        return new Options(project, out, manifest, json, strict, profile);
    }

    private static void requireBuilding(boolean building, String option) throws UsageException {
        if (!building) {
            throw new UsageException(option + " is only supported by build");
        }
    }

    private static String value(String[] args, int index, String option) throws UsageException {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new UsageException(option + " needs a value");
        }
        return args[index];
    }

    private static void printHelp(PrintStream out) {
        out.println("Mindustry ModMaker " + BuildManifest.TOOL_VERSION + " - headless tools");
        out.println();
        out.println("Usage:");
        out.println("  modmaker validate " + DEFAULT_PROJECT
            + " [--json <report>] [--strict] [--profile <ver>]");
        out.println("  modmaker build " + DEFAULT_PROJECT + " --out " + DEFAULT_OUT
            + " [--manifest <file>] [--strict] [--profile <ver>]");
        out.println("  modmaker --help | --version");
        out.println();
        out.println("Exit codes:");
        out.println("  0  success");
        out.println("  2  validation failed (errors, or warnings with --strict)");
        out.println("  3  build failed");
        out.println("  5  bad arguments or unreadable project");
    }

    /** An argument error: reported on stderr, exit code 5. */
    private static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    /**
     * Keeps the manifest out of both the built payload and the project source tree. Writing it
     * inside either makes it part of the next build (and changes sourceHash between identical
     * builds); writing it over the zip silently destroys the artifact while still exiting 0.
     */
    static void requireManifestOutsidePayload(Path projectRoot, Path out, String internal,
        boolean zip, Path manifest) throws IOException {
        Path target = manifest.toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        if (target.startsWith(root)) {
            throw new IOException("the manifest must be outside the project source tree: " + target);
        }
        if (zip) {
            if (target.equals(out.toAbsolutePath().normalize())) {
                throw new IOException("the manifest path must differ from the zip output: " + target);
            }
            return;
        }
        Path modDir = out.resolve(internal).toAbsolutePath().normalize();
        if (target.startsWith(modDir)) {
            throw new IOException("the manifest must be outside the built mod folder: " + target);
        }
    }

    /** The manifest path for a build target, used by callers that need the default. */
    public static Path defaultManifestPath(Path out) {
        String name = out.getFileName() == null ? "" : out.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".zip")
            ? Path.of(out.toString() + ".manifest.json")
            : out.resolve("manifest.json");
    }
}

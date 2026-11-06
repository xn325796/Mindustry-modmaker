package dev.modmaker.core.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.modmaker.core.json.Json;
import dev.modmaker.core.model.ModProject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * The provenance record written next to a build.
 *
 * <p>A manifest is deliberately kept <em>outside</em> the built mod: the zip and folder stay exactly
 * what the project contains, so "project content = build content" remains byte-for-byte true and a
 * mod that is unpacked from the build can be imported again without a synthetic file appearing.
 */
public final class BuildManifest {

    public static final int FORMAT = 1;
    public static final String TOOL = "Mindustry ModMaker";
    /** Kept in step with the Gradle project version; also printed by {@code --version}. */
    public static final String TOOL_VERSION = "0.1.0";

    private BuildManifest() {
    }

    /**
     * Builds the manifest object. Hashes are hex strings prefixed with {@code sha256:}, matching the
     * published format.
     *
     * @param schemaProfile the profile the project was built for
     * @param files         number of files in the built payload
     * @param bytes         total payload bytes
     * @param sourceHash    hash of the project source tree (authoring directory excluded)
     * @param artifactHash  hash of the zip or the built folder tree
     */
    public static JsonObject create(ModProject project, String schemaProfile, int files, long bytes,
        String sourceHash, String artifactHash) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", FORMAT);
        manifest.addProperty("tool", TOOL);
        manifest.addProperty("toolVersion", TOOL_VERSION);
        JsonObject mod = new JsonObject();
        mod.addProperty("name", project.internalName());
        mod.addProperty("version", project.meta.version == null ? "" : project.meta.version);
        mod.addProperty("minGameVersion",
            project.meta.minGameVersion == null ? "" : project.meta.minGameVersion);
        manifest.add("mod", mod);
        manifest.addProperty("schemaProfile", schemaProfile);
        JsonArray dependencies = new JsonArray();
        if (project.meta.dependencies != null) {
            project.meta.dependencies.forEach(dependencies::add);
        }
        manifest.add("dependencies", dependencies);
        manifest.addProperty("builtAt",
            Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        manifest.addProperty("sourceHash", sourceHash);
        manifest.addProperty("files", files);
        manifest.addProperty("bytes", bytes);
        manifest.addProperty("sha256", artifactHash);
        return manifest;
    }

    /** Writes the manifest as UTF-8 JSON, creating parent folders as needed. */
    public static void write(JsonObject manifest, Path target) throws IOException {
        Path absolute = target.toAbsolutePath();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        Files.writeString(absolute, Json.write(manifest) + "\n", StandardCharsets.UTF_8);
    }

    /** SHA-256 of one file, as {@code sha256:<hex>}. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    /**
     * A stable hash of the project's source files: relative path plus content, in path order. The
     * authoring directory and save/crash temp files are excluded, so the hash describes exactly what
     * a build will package.
     */
    public static String sourceHash(Path projectRoot) throws IOException {
        return hashTree(projectRoot, true);
    }

    /** A stable hash of a built folder: every file below {@code folder}, in path order. */
    public static String treeHash(Path folder) throws IOException {
        return hashTree(folder, false);
    }

    private static String hashTree(Path root, boolean excludeAuthoring) throws IOException {
        MessageDigest digest = newDigest();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                .filter(file -> !excludeAuthoring || !isAuthoring(root, file))
                .filter(file -> !file.getFileName().toString().endsWith(ProjectIo.TEMP_SUFFIX))
                .sorted()
                .toList();
        }
        List<String> relatives = new ArrayList<>();
        for (Path file : files) {
            relatives.add(root.relativize(file).toString().replace('\\', '/'));
        }
        relatives.sort(String::compareTo);
        for (String relative : relatives) {
            // Sorted again by relative path: Files.walk's order is filesystem-dependent, and the
            // hash must not be.
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            Path file = root.resolve(relative);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            digest.update((byte) 0);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static boolean isAuthoring(Path root, Path file) {
        Path relative = root.relativize(file);
        return relative.getNameCount() > 0
            && relative.getName(0).toString().equalsIgnoreCase(ProjectIo.AUTHORING_DIR);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}

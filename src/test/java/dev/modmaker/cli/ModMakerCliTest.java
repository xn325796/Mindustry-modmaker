package dev.modmaker.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.io.BuildManifest;
import dev.modmaker.core.io.ProjectFactory;
import dev.modmaker.core.io.ProjectIo;
import dev.modmaker.core.json.Json;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The headless contract: exit codes, the JSON report and a deterministic zip plus its manifest. */
class ModMakerCliTest {

    private static Path project(String internal, Consumer<ModProject> configure)
        throws IOException {
        Path root = Files.createTempDirectory("mm-cli-" + internal);
        ModProject project = ProjectFactory.create(internal, root);
        configure.accept(project);
        ProjectIo.save(project, root);
        return root;
    }

    private static ContentRecord record(String id, ContentType type, String name) {
        return ContentRecord.create(id, type, name);
    }

    @Test
    void helpAndVersionExitZero() {
        assertEquals(0, ModMakerCli.run(new String[] {"--help"}));
        assertEquals(0, ModMakerCli.run(new String[] {"--version"}));
        assertTrue(ModMakerCli.handles(new String[] {"validate", "x"}));
        assertTrue(ModMakerCli.handles(new String[] {"build", "x"}));
        assertFalse(ModMakerCli.handles(new String[] {"--open=x"}));
    }

    @Test
    void validationErrorsExitTwoAndProduceStructuredJson() throws IOException {
        Path root = project("clierror", project -> {
            ContentRecord block = record("c1", ContentType.block, "huge");
            block.fields.put("size", new JsonPrimitive(20));
            project.contents.add(block);
        });
        Path report = root.resolve("report.json");
        int code = ModMakerCli.run(new String[] {"validate", root.toString(),
            "--json", report.toString()});
        assertEquals(ModMakerCli.VALIDATION_FAILED, code);

        JsonObject json = Json.parseObject(Files.readString(report));
        assertEquals(1, json.get("schemaVersion").getAsInt());
        JsonArray issues = json.getAsJsonArray("issues");
        assertTrue(issues.size() > 0);
        boolean found = false;
        for (var element : issues) {
            JsonObject issue = element.getAsJsonObject();
            if ("validate.block.tooLarge".equals(issue.get("code").getAsString())) {
                found = true;
                assertEquals("error", issue.get("severity").getAsString());
                assertEquals("c1", issue.get("contentId").getAsString());
                assertFalse(issue.get("message").getAsString().isBlank());
                assertTrue(issue.get("args").isJsonArray());
            }
        }
        assertTrue(found, "the block-size error is in the report");
    }

    @Test
    void strictPromotesWarningsToExitTwo() throws IOException {
        Path root = project("cliwarn", project -> {
            ContentRecord block = record("c1", ContentType.block, "kiln");
            // A dangling "research" is a warning in game (it only logs "isn't in the tech tree"),
            // so plain validate exits 0 and --strict promotes it. A dangling requirements item, by
            // contrast, is an error: the game throws "No item found with name ..." there.
            block.fields.put("research", new JsonPrimitive("not-a-real-research-node"));
            project.contents.add(block);
        });
        assertEquals(ModMakerCli.OK,
            ModMakerCli.run(new String[] {"validate", root.toString()}));
        assertEquals(ModMakerCli.VALIDATION_FAILED,
            ModMakerCli.run(new String[] {"validate", root.toString(), "--strict"}));
    }

    @Test
    void buildStrictBlocksWarningsAndWritesNothing() throws IOException {
        Path root = project("clistrictbuild", project -> {
            ContentRecord block = record("c1", ContentType.block, "kiln");
            block.fields.put("research", new JsonPrimitive("not-a-real-research-node"));
            project.contents.add(block);
        });
        Path outDir = Files.createTempDirectory("mm-cli-strict-out");
        Path zip = outDir.resolve("strict.zip");

        assertEquals(ModMakerCli.VALIDATION_FAILED,
            ModMakerCli.run(new String[] {"build", root.toString(), "--out", zip.toString(),
                "--strict"}));
        assertFalse(Files.exists(zip), "strict mode must not write the zip");
        assertFalse(Files.exists(Path.of(zip.toString() + ".manifest.json")),
            "strict mode must not write the default manifest either");
    }

    @Test
    void buildProducesAZipAndAManifestWithStableHashes() throws IOException {
        Path root = project("clibuild", project -> {
            project.contents.add(record("c1", ContentType.item, "alloy"));
        });
        Path outDir = Files.createTempDirectory("mm-cli-out");
        Path first = outDir.resolve("first.zip");
        Path second = outDir.resolve("second.zip");
        Path manifestPath = outDir.resolve("build.manifest.json");

        assertEquals(ModMakerCli.OK, ModMakerCli.run(new String[] {"build", root.toString(),
            "--out", first.toString(), "--manifest", manifestPath.toString()}));
        assertTrue(Files.isRegularFile(first));
        assertTrue(Files.isRegularFile(manifestPath));

        JsonObject manifest = Json.parseObject(Files.readString(manifestPath));
        assertEquals(BuildManifest.FORMAT, manifest.get("format").getAsInt());
        assertEquals(BuildManifest.TOOL, manifest.get("tool").getAsString());
        assertFalse(manifest.get("toolVersion").getAsString().isBlank());
        JsonObject mod = manifest.getAsJsonObject("mod");
        assertEquals("clibuild", mod.get("name").getAsString());
        assertEquals("1.0", mod.get("version").getAsString());
        assertEquals("160.7", manifest.get("schemaProfile").getAsString());
        assertTrue(manifest.get("sourceHash").getAsString().startsWith("sha256:"));
        assertEquals(BuildManifest.sourceHash(root), manifest.get("sourceHash").getAsString());
        assertEquals(BuildManifest.sha256(first), manifest.get("sha256").getAsString());
        assertTrue(manifest.get("files").getAsInt() >= 2, "meta plus one content file");
        assertTrue(manifest.get("bytes").getAsInt() > 0);

        // The manifest must not be part of the payload: project content = build content.
        try (ZipFile zip = new ZipFile(first.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                assertTrue(name.startsWith("clibuild/"),
                    "the zip keeps its single wrapping folder: " + name);
                assertFalse(name.contains("manifest"), "no synthetic file inside the mod: " + name);
            }
        }

        assertEquals(ModMakerCli.OK, ModMakerCli.run(new String[] {"build", root.toString(),
            "--out", second.toString()}));
        assertEquals(BuildManifest.sha256(first), BuildManifest.sha256(second),
            "two builds of the same project must produce identical archives");
        assertTrue(Files.isRegularFile(Path.of(second.toString() + ".manifest.json")),
            "a zip build defaults its manifest to <zip>.manifest.json");
    }

    @Test
    void folderBuildWritesTheManifestBesideTheModFolder() throws IOException {
        Path root = project("clifolder", project -> {
            project.contents.add(record("c1", ContentType.item, "alloy"));
        });
        Path outDir = Files.createTempDirectory("mm-cli-folder-out");

        assertEquals(ModMakerCli.OK, ModMakerCli.run(new String[] {"build", root.toString(),
            "--out", outDir.toString()}));
        Path manifestPath = outDir.resolve("manifest.json");
        assertTrue(Files.isRegularFile(manifestPath));
        assertTrue(Files.isRegularFile(outDir.resolve("clifolder/mod.hjson")));

        JsonObject manifest = Json.parseObject(Files.readString(manifestPath));
        assertEquals(BuildManifest.treeHash(outDir.resolve("clifolder")),
            manifest.get("sha256").getAsString(),
            "a folder build hashes the built tree, not the outer directory");
    }

    @Test
    void manifestInsideThePayloadOrSourceTreeIsRejected() throws IOException {
        Path root = project("cliguard", project -> {
            project.contents.add(record("c1", ContentType.item, "alloy"));
        });
        Path outDir = Files.createTempDirectory("mm-cli-guard-out");
        Path zip = outDir.resolve("pkg.zip");
        assertEquals(ModMakerCli.USAGE, ModMakerCli.run(new String[] {"build", root.toString(),
            "--out", zip.toString(), "--manifest", zip.toString()}),
            "a manifest that overwrites the zip is an argument error");
        assertFalse(Files.exists(zip), "the guard runs before the artifact is written");

        Path folderOut = Files.createTempDirectory("mm-cli-guard-folder");
        assertEquals(ModMakerCli.USAGE, ModMakerCli.run(new String[] {"build", root.toString(),
            "--out", folderOut.toString(), "--manifest",
            folderOut.resolve("cliguard/manifest.json").toString()}),
            "a manifest inside the built mod folder would become payload");
        assertEquals(ModMakerCli.USAGE, ModMakerCli.run(new String[] {"build", root.toString(),
            "--out", zip.toString(), "--manifest",
            root.resolve("manifest.json").toString()}),
            "a manifest inside the source tree would make sourceHash unstable");
    }

    @Test
    void aFailingTargetExitsThree() throws IOException {
        Path root = project("clifail", project -> {
            project.contents.add(record("c1", ContentType.item, "alloy"));
        });
        // A build target inside the project would nest a copy of the mod into itself; the exporter
        // refuses it and the CLI reports a build failure rather than a validation problem.
        assertEquals(ModMakerCli.BUILD_FAILED, ModMakerCli.run(new String[] {"build",
            root.toString(), "--out", root.resolve("nested").toString()}));
    }

    @Test
    void usageErrorsExitFive() throws IOException {
        Path root = project("cliusage", project -> {
            project.contents.add(record("c1", ContentType.item, "alloy"));
        });
        Path report = root.resolve("report.json");

        assertEquals(ModMakerCli.USAGE,
            ModMakerCli.run(new String[] {"frobnicate", root.toString()}),
            "an unknown command is a usage error");
        assertEquals(ModMakerCli.USAGE,
            ModMakerCli.run(new String[] {"build", root.toString()}),
            "build requires --out");
        assertEquals(ModMakerCli.USAGE,
            ModMakerCli.run(new String[] {"build", root.toString(), "--json", report.toString()}),
            "--json is validate-only");
        assertFalse(Files.exists(report), "a rejected build must not write a JSON report");
    }

    @Test
    void blankProfileIsRejected() throws IOException {
        Path root = project("clipractice", project -> {
            project.contents.add(record("c1", ContentType.item, "alloy"));
        });

        assertEquals(ModMakerCli.USAGE,
            ModMakerCli.run(new String[] {"validate", root.toString(), "--profile", " "}),
            "a blank --profile is a usage error");
        assertEquals(0,
            ModMakerCli.run(new String[] {"validate", root.toString(), "--profile", "161"}),
            "a non-blank --profile still validates");

        Path report = root.resolve("report.json");
        assertEquals(0,
            ModMakerCli.run(new String[] {"validate", root.toString(), "--profile", " 161 ",
                "--json", report.toString()}),
            "a padded --profile is kept as a usable value");
        JsonObject json = Json.parseObject(Files.readString(report));
        assertEquals("161", json.get("profile").getAsString(),
            "the report stores the profile with its surrounding whitespace trimmed");
    }

    @Test
    void badArgumentsAndUnreadableProjectsExitFive() {
        assertEquals(ModMakerCli.USAGE, ModMakerCli.run(new String[] {"build", "x"}));
        assertEquals(ModMakerCli.USAGE, ModMakerCli.run(new String[] {"validate"}));
        assertEquals(ModMakerCli.USAGE,
            ModMakerCli.run(new String[] {"validate", "no-such-project-dir-xyz"}));
        assertEquals(ModMakerCli.USAGE,
            ModMakerCli.run(new String[] {"validate", "x", "--wat"}));
    }
}

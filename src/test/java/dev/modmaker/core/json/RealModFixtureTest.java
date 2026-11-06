package dev.modmaker.core.json;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Parser acceptance against a real, large, hand-written mod that uses the full HJSON dialect
 * (hash comments, members separated by newlines, unquoted values, nested folders). Skipped when
 * the fixture is not next to the project so the suite stays portable.
 */
class RealModFixtureTest {

    private static final Path MOD_ROOT =
        dev.modmaker.core.io.TestFixtures.realModRoot();

    @Test
    void parsesTheRealMetaFileWithComments() throws IOException {
        assumeTrue(MOD_ROOT != null && Files.isDirectory(MOD_ROOT), "real mod fixture not present");
        Path meta = null;
        for (String candidate : dev.modmaker.core.model.ModMeta.META_FILES) {
            Path found = MOD_ROOT.resolve(candidate);
            if (Files.exists(found)) {
                meta = found;
                break;
            }
        }
        assumeTrue(meta != null, "real mod fixture has no meta file");
        JsonObject object = HjsonReader.readObject(Json.readFile(meta));
        assertTrue(object.get("name") != null && !object.get("name").getAsString().isBlank(),
            meta.getFileName() + " must parse to a named mod despite its comments");
    }

    @Test
    void parsesARealItemFile() throws IOException {
        assumeTrue(MOD_ROOT != null && Files.isDirectory(MOD_ROOT), "real mod fixture not present");
        Path items = MOD_ROOT.resolve("content").resolve("items");
        assumeTrue(Files.isDirectory(items), "fixture has no items");
        try (Stream<Path> files = Files.list(items)) {
            Path item = files
                .filter(path -> path.toString().endsWith(".json")
                    || path.toString().endsWith(".hjson"))
                .findFirst()
                .orElseThrow();
            JsonObject object = HjsonReader.readObject(Json.readFile(item));
            assertTrue(object.size() > 0, item.getFileName() + " parses to a non-empty object");
        }
    }

    @Test
    void mostContentFilesOfARealModParse() throws IOException {
        assumeTrue(MOD_ROOT != null, "real mod fixture not present");
        Path content = MOD_ROOT.resolve("content");
        assumeTrue(Files.isDirectory(content), "real mod fixture not present");

        List<String> failures = new ArrayList<>();
        int total = 0;
        try (Stream<Path> walk = Files.walk(content)) {
            List<Path> files = walk
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase();
                    return name.endsWith(".json") || name.endsWith(".hjson");
                })
                .toList();
            for (Path file : files) {
                total++;
                try {
                    if (file.toString().toLowerCase().endsWith(".json")) {
                        HjsonReader.readJson(Json.readFile(file));
                    } else {
                        HjsonReader.read(Json.readFile(file));
                    }
                } catch (Exception failure) {
                    failures.add(MOD_ROOT.relativize(file) + " -> " + failure.getMessage());
                }
            }
        }

        assertTrue(total > 100, "expected a substantial fixture, saw " + total + " content files");
        System.out.println("parsed " + (total - failures.size()) + "/" + total + " content files");
        failures.forEach(failure -> System.out.println("  FAILED " + failure));
        assertTrue(failures.size() * 10 <= total,
            "more than 10% of content files failed: " + failures.size() + "/" + total);
    }
}

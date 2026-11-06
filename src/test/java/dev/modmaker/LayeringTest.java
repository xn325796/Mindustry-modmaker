package dev.modmaker;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layering claim, checked instead of trusted.
 *
 * <p>"core has no JavaFX" and "the CLI is runnable headless" are the two properties the whole
 * architecture rests on, and until now both were comments. The project is a single Gradle module, so
 * a stray import would not fail the build - it would only fail in CI when the graphics module is
 * missing. These scans turn the convention into a test failure at the point it is broken.
 */
class LayeringTest {

    private static final String JAVA_FX_IMPORT = "import javafx";
    private static final String UI_IMPORT = "import dev.modmaker.ui";

    @Test
    void coreDoesNotReferenceJavaFx() throws IOException {
        List<String> offenders = offendingFiles(
            Path.of("src", "main", "java", "dev", "modmaker", "core"), JAVA_FX_IMPORT);
        assertTrue(offenders.isEmpty(), "core must stay headless, but these files reference JavaFX: "
            + offenders);
    }

    @Test
    void theCliDoesNotReferenceJavaFxOrTheUi() throws IOException {
        Path cli = Path.of("src", "main", "java", "dev", "modmaker", "cli");
        List<String> offenders = new ArrayList<>();
        offenders.addAll(offendingFiles(cli, JAVA_FX_IMPORT));
        offenders.addAll(offendingFiles(cli, UI_IMPORT));
        assertTrue(offenders.isEmpty(),
            "the CLI must run without the graphics module or the UI package: " + offenders);
    }

    @Test
    void coreDoesNotReferenceTheUiPackage() throws IOException {
        List<String> offenders = offendingFiles(
            Path.of("src", "main", "java", "dev", "modmaker", "core"), UI_IMPORT);
        assertTrue(offenders.isEmpty(), "core must not depend on the UI: " + offenders);
    }

    private static List<String> offendingFiles(Path root, String needle) throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(root), "source tree not available from " + root);
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains(needle)) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        return offenders;
    }
}

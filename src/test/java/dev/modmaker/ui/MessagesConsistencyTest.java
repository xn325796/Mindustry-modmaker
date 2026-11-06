package dev.modmaker.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the i18n bundles honest. {@link Messages#t(String)} silently falls back to the key itself, so
 * a typo only ever shows up as a bare key in the UI and never fails at runtime; these checks turn
 * that class of mistake into a build failure.
 *
 * <p>Three things are asserted:
 * <ul>
 *   <li>the translations expose exactly the same key set as the English source bundle;</li>
 *   <li>{@code messages_en.properties} stays empty, so an English request resolves to the English
 *       base bundle instead of the JVM default locale's translation;</li>
 *   <li>every key written literally in the sources ({@code Messages.t("...")} and the
 *       {@code Issue.error/warning("...")} factories) exists in the bundle.</li>
 * </ul>
 */
class MessagesConsistencyTest {

    /** Keys used with string concatenation ("ctype." + type.name()); resolved per enum instead. */
    private static final Set<String> DYNAMIC_PREFIXES = Set.of("workspace.", "ctype.", "field.",
        "techtree.problem.");

    private static final Pattern T_CALL = Pattern.compile("(?:Messages\\.)?\\bt\\(\\s*\"([^\"]+)\"");
    private static final Pattern ISSUE_CALL = Pattern.compile(
        "Issue\\.(?:error|warning|errorOn|warningOn)\\(\\s*(?:[A-Za-z0-9_.]+,\\s*)?\"([^\"]+)\"");

    @Test
    void translationsHaveTheSameKeysAsTheEnglishSource() {
        Set<String> base = keysOf("messages.properties");
        assertTrue(base.size() > 100, "the base bundle should carry the UI's strings, found "
            + base.size());
        for (String locale : List.of("zh_CN")) {
            Set<String> translated = keysOf("messages_" + locale + ".properties");
            List<String> missing = base.stream().filter(key -> !translated.contains(key)).toList();
            List<String> extra = translated.stream().filter(key -> !base.contains(key)).toList();
            assertTrue(missing.isEmpty(), "messages_" + locale + ".properties is missing keys: "
                + missing);
            assertTrue(extra.isEmpty(), "messages_" + locale + ".properties has keys the source "
                + "does not: " + extra);
        }
    }

    @Test
    void theEnglishBundleStaysEmptySoEnglishResolvesToTheSourceBundle() throws IOException {
        Set<String> english = keysOf("messages_en.properties");
        assertTrue(english.isEmpty(),
            "messages_en.properties must stay empty (it only blocks the default-locale fallback): "
                + english);
        // The placeholder only works when the base bundle resolves to English, which is what the
        // language menu and Issue.message() rely on.
        ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.ENGLISH);
        assertTrue(bundle.getString("app.title").contains("ModMaker"),
            "English must resolve to the English base bundle");
    }

    @Test
    void everyLiterallyReferencedKeyExists() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        Assumptions.assumeTrue(Files.isDirectory(sourceRoot),
            "source tree not available from the test working directory");
        Set<String> bundle = keysOf("messages.properties");
        Set<String> unknown = new LinkedHashSet<>();
        List<String> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    sources.add(Files.readString(path, StandardCharsets.UTF_8));
                } catch (IOException unreadable) {
                    throw new IllegalStateException(unreadable);
                }
            });
        }
        for (String source : sources) {
            collectUnknown(T_CALL, source, bundle, unknown);
            collectUnknown(ISSUE_CALL, source, bundle, unknown);
        }
        assertTrue(unknown.isEmpty(),
            "these keys are referenced in the sources but missing from messages.properties (the UI "
                + "would show the bare key): " + unknown);
    }

    private static void collectUnknown(Pattern pattern, String source, Set<String> bundle,
        Set<String> unknown) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String key = matcher.group(1);
            // A key ending in '.' is the prefix of a dynamic lookup (e.g. "ctype." + type.name()),
            // not a key of its own; the enum-derived keys are covered by the bundle equality check.
            if (key.endsWith(".") || DYNAMIC_PREFIXES.contains(key)) {
                continue;
            }
            if (!bundle.contains(key)) {
                unknown.add(key);
            }
        }
    }

    private static Set<String> keysOf(String resource) {
        Properties properties = new Properties();
        try (InputStream stream = MessagesConsistencyTest.class.getClassLoader()
            .getResourceAsStream(resource)) {
            if (stream != null) {
                properties.load(stream);
            } else {
                // The test JVM may run without the build output on the classpath; read the file.
                Path file = Path.of("src", "main", "resources", resource);
                Assumptions.assumeTrue(Files.exists(file), resource + " not found");
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
        return properties.stringPropertyNames();
    }
}

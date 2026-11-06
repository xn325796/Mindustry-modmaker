package dev.modmaker.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Locates files and folders that live outside the project: the Mindustry source tree the schemas
 * are scanned from, the game jar, the arc jar the noise port is compared against, the real mod the
 * golden tests and the live-edit probe run against.
 *
 * <p>Every lookup climbs the same short ladder, so a checkout works on any machine instead of
 * depending on one absolute path baked into the sources: an explicit setting first (system
 * property, then environment variable), then the relative candidates the usual workspace layout
 * provides, then a shallow scan of the folders around the working directory. Callers decide what
 * to do when nothing matches - the tools fail with a message, the tests skip.
 */
public final class SearchPaths {

    private SearchPaths() {
    }

    /**
     * An explicit override for a location: the system property when set and non-blank, otherwise
     * the environment variable. Returns null when neither is set.
     */
    public static Path override(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null || value.isBlank()
            ? null
            : Path.of(value).toAbsolutePath().normalize();
    }

    /** The working directory Gradle, the tools and the tests all start from. */
    public static Path workingDirectory() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /** The first candidate that is a directory; null when none is. */
    public static Path firstDirectory(List<Path> candidates) {
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    /** The first candidate that is a regular file; null when none is. */
    public static Path firstFile(List<Path> candidates) {
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    /**
     * The shallowest, name-first match of {@code matches} at or below {@code root}, scanning at
     * most {@code depth} levels. Deterministic: same tree, same answer - search order is part of
     * the contract because callers (imports, schema scans) must be reproducible.
     */
    public static Path find(Path root, int depth, Predicate<Path> matches) {
        if (root == null || depth < 1 || !Files.isDirectory(root)) {
            return null;
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, depth)) {
            walk.filter(matches).forEach(found::add);
        } catch (IOException unreadable) {
            return null;
        }
        return found.stream()
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .sorted(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString))
            .findFirst()
            .orElse(null);
    }
}

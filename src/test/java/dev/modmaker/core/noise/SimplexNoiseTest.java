package dev.modmaker.core.noise;

import dev.modmaker.core.io.SearchPaths;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The port must produce byte-identical noise to arc's Simplex - planet appearance previews claim to
 * match the game's rendering, and terrain shapes diverge visibly if the hash or skewing differs.
 * Verified against the real arc jar (located in the Gradle cache, wherever that is) via reflection;
 * skips when neither a cache nor an explicit jar is available.
 */
class SimplexNoiseTest {

    /** Deep enough for caches/modules-2/files-2.1/&lt;group&gt;/&lt;module&gt;/&lt;version&gt;/&lt;hash&gt;/jar. */
    private static final int CACHE_SCAN_DEPTH = 8;

    /**
     * The arc jar to compare against: an explicit {@code -Dmodmaker.arcJar} / {@code ARC_JAR}
     * first, otherwise any arc-core jar in the Gradle cache. The cache's hashed layout is Gradle's
     * business, so this probes for the jar instead of spelling a path out.
     */
    private static Path findArcJar() {
        Path explicit = SearchPaths.override("modmaker.arcJar", "ARC_JAR");
        if (explicit != null && Files.isRegularFile(explicit)) {
            return explicit;
        }
        List<Path> caches = new ArrayList<>();
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        if (gradleHome != null && !gradleHome.isBlank()) {
            caches.add(Path.of(gradleHome, "caches", "modules-2", "files-2.1"));
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            caches.add(Path.of(home, ".gradle", "caches", "modules-2", "files-2.1"));
        }
        for (Path cache : caches) {
            Path jar = SearchPaths.find(cache, CACHE_SCAN_DEPTH, path -> {
                String name = path.getFileName().toString();
                return name.startsWith("arc-core") && name.endsWith(".jar");
            });
            if (jar != null) {
                return jar;
            }
        }
        return null;
    }

    @Test
    void matchesArcSimplexExactly() throws Exception {
        Path arcJar = findArcJar();
        assumeTrue(arcJar != null, "no arc jar found (set -Dmodmaker.arcJar=<path>)");
        Class<?> arc = Class.forName("arc.util.noise.Simplex", true,
            new java.net.URLClassLoader(new java.net.URL[] {arcJar.toUri().toURL()}));
        Method arcNoise = arc.getMethod("noise3d", int.class, double.class, double.class,
            double.class, double.class, double.class, double.class);

        double maxDelta = 0;
        int samples = 0;
        for (int seed = 0; seed <= 12; seed += 6) {
            for (int octaves = 1; octaves <= 3; octaves++) {
                for (double persistence = 0.3; persistence <= 0.6; persistence += 0.15) {
                    for (double scale = 0.5; scale <= 2.0; scale += 0.75) {
                        for (double x = -5; x <= 5.5; x += 1.37) {
                            for (double y = -5; y <= 5.5; y += 1.41) {
                                for (double z = -5; z <= 5.5; z += 1.43) {
                                    double expected = ((Number) arcNoise.invoke(null, seed, octaves,
                                        persistence, scale, 5.0 + x, 5.0 + y, 5.0 + z)).doubleValue();
                                    double actual = SimplexNoise.noise3d(seed, octaves, persistence,
                                        scale, 5.0 + x, 5.0 + y, 5.0 + z);
                                    maxDelta = Math.max(maxDelta, Math.abs(expected - actual));
                                    samples++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(maxDelta < 1e-9, "port diverges from arc Simplex by " + maxDelta
            + " over " + samples + " samples");
    }

    @Test
    void noiseIsDeterministicAndBounded() {
        float a = SimplexNoise.noise3d(3, 3, 0.5f, 1f, 1.5, 2.5, -3.5);
        float b = SimplexNoise.noise3d(3, 3, 0.5f, 1f, 1.5, 2.5, -3.5);
        assertEquals(a, b, 0f, "same inputs must give the same value");
        assertTrue(a >= -0.1 && a <= 1.1, "multi-octave noise is normalized to ~[0,1], saw " + a);
    }

    @Test
    void differentSeedsGiveDifferentTerrain() {
        boolean differs = false;
        for (double x = 0; x < 20 && !differs; x += 0.5) {
            for (double y = 0; y < 20 && !differs; y += 0.5) {
                if (SimplexNoise.noise3d(0, 3, 0.5f, 1f, x, y, 0) !=
                    SimplexNoise.noise3d(7, 3, 0.5f, 1f, x, y, 0)) {
                    differs = true;
                }
            }
        }
        assertTrue(differs, "seeds must change the terrain");
    }
}

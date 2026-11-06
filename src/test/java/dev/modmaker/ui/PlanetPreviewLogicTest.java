package dev.modmaker.ui;

import dev.modmaker.core.fmt.PlanetMeshes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure-math helpers of the planet preview: the ray-sphere intersection, the optical-depth
 * integral and the layer-shading classification ported from the game's shaders. No JavaFX toolkit
 * needed.
 */
class PlanetPreviewLogicTest {

    @Test
    void lengthComputesTheEuclideanNorm() {
        assertEquals(5, PlanetPreview.length(new double[] {3, 4, 0}), 1e-9);
        assertEquals(3, PlanetPreview.length(new double[] {1, 2, 2}), 1e-9);
        assertEquals(0, PlanetPreview.length(new double[] {0, 0, 0}), 1e-9);
    }

    @Test
    void rayIntersectionFromTheOriginSpansTheSphere() {
        assertArrayEquals(new double[] {-5, 5},
            PlanetPreview.rayIntersection(0, 0, 0, new double[] {1, 0, 0}, 5), 1e-9);
    }

    @Test
    void rayIntersectionTowardTheSphereReportsNearAndFar() {
        assertArrayEquals(new double[] {5, 15},
            PlanetPreview.rayIntersection(10, 0, 0, new double[] {-1, 0, 0}, 5), 1e-9);
    }

    @Test
    void rayIntersectionPointingAwayIsBehindTheRay() {
        double[] hits = PlanetPreview.rayIntersection(10, 0, 0, new double[] {1, 0, 0}, 5);
        assertTrue(hits[0] < 0 && hits[1] < 0, "a ray pointing away must not hit the sphere");
    }

    @Test
    void shadeKindOfClassifiesUnlitSkyAndSurfaceLayers() {
        PlanetMeshes.Layer sun = new PlanetMeshes.Layer(null, 1f, 1f, 3, true, false, false, 0f);
        PlanetMeshes.Layer cloud = new PlanetMeshes.Layer(null, 1f, 1f, 3, false, true, false, 0f);
        PlanetMeshes.Layer surface = new PlanetMeshes.Layer(null, 1f, 1f, 3, false, false, false, 0f);
        assertEquals(PlanetPreview.ShadeKind.SUN, PlanetPreview.shadeKindOf(sun));
        assertEquals(PlanetPreview.ShadeKind.CLOUD, PlanetPreview.shadeKindOf(cloud));
        assertEquals(PlanetPreview.ShadeKind.SURFACE, PlanetPreview.shadeKindOf(surface));
    }
}

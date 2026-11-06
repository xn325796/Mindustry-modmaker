package dev.modmaker.core.fmt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fixed mesh field lists must mirror ContentParser.parseMesh exactly. */
class MeshTypesTest {

    @Test
    void exactlyTheParseableTypes() {
        assertEquals(java.util.Set.of("NoiseMesh", "SunMesh", "HexSkyMesh", "MultiMesh", "MatMesh"),
            MeshTypes.PARSEABLE);
        assertNull(MeshTypes.spec("HexMesh"), "serpulo's HexMesh is not JSON-parseable");
        assertNull(MeshTypes.spec("ShaderSphereMesh"));
    }

    @Test
    void noiseMeshFieldsMatchTheKeysParseMeshReads() {
        var spec = MeshTypes.spec("NoiseMesh");
        assertEquals(List.of("seed", "divisions", "radius", "octaves", "persistence", "scale",
                "mag", "color1", "color2", "color", "colorOct", "colorPersistence", "colorScale",
                "colorThreshold"),
            spec.fields().stream().map(MeshTypes.Field::name).toList(),
            "color is the shorthand parseMesh falls back to for both color1 and color2");
    }

    @Test
    void typeNamesAreCapitalisedTheWayTheGameDoes() {
        // parseMesh runs Strings.capitalize over the type name, which raises only the first letter.
        assertEquals("NoiseMesh", MeshTypes.canonical("noiseMesh"));
        assertEquals("NoiseMesh", MeshTypes.canonical("NoiseMesh"));
        assertEquals("Noisemesh", MeshTypes.canonical("noisemesh"));
        assertNull(MeshTypes.canonical(null));
        assertEquals("NoiseMesh", MeshTypes.spec("noiseMesh").type());
        assertNull(MeshTypes.spec("noisemesh"), "the game rejects it as an unknown mesh type");
        assertTrue(MeshTypes.isParseable("hexSkyMesh"));
        assertFalse(MeshTypes.isParseable("HexMesh"), "serpulo's HexMesh has no parseMesh case");
    }

    @Test
    void skyMeshAndSunMeshFields() {
        assertEquals(List.of("seed", "speed", "radius", "divisions", "color", "octaves",
                "persistence", "scale", "thresh"),
            MeshTypes.spec("HexSkyMesh").fields().stream()
                .map(MeshTypes.Field::name).toList());
        assertEquals(List.of("divisions", "octaves", "persistence", "scl", "pow", "mag",
                "colorScale", "colors"),
            MeshTypes.spec("SunMesh").fields().stream()
                .map(MeshTypes.Field::name).toList());
    }
}

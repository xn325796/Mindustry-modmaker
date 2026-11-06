package dev.modmaker.core.fmt;

import dev.modmaker.core.schema.FieldType;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The mesh types the game's JSON parser accepts (ContentParser.parseMesh) and the JSON keys it
 * reads for each. A planet's "mesh" and "cloudMesh" are edited against these fixed lists.
 *
 * <p>Two things parseMesh does that a naive field list gets wrong: a key it never reads is simply
 * ignored (it reads each key explicitly with its own default), while an unrecognised <em>type</em>
 * throws "Unknown mesh type" - and the type name is passed through {@code Strings.capitalize}, so
 * {@code noiseMesh} is accepted but {@code noisemesh} is not (only the first letter is raised).
 *
 * <p>Notably, serpulo's own HexMesh is NOT parseable from JSON, so it must never be offered.
 */
public final class MeshTypes {

    /** One editable mesh field: its JSON key and the widget that edits it. */
    public record Field(String name, FieldType type) {
    }

    /** One mesh type and its fixed field list (MultiMesh/MatMesh nest children in the editor). */
    public record Spec(String type, List<Field> fields) {
    }

    public static final List<Spec> ALL = List.of(
        new Spec("NoiseMesh", List.of(
            new Field("seed", FieldType.number),
            new Field("divisions", FieldType.number),
            new Field("radius", FieldType.number),
            new Field("octaves", FieldType.number),
            new Field("persistence", FieldType.number),
            new Field("scale", FieldType.number),
            new Field("mag", FieldType.number),
            new Field("color1", FieldType.color),
            new Field("color2", FieldType.color),
            // parseMesh reads color1 and color2 each with a fallback to "color", which is how a mod
            // (and the serpulo template) gives both colours at once: color1 -> color -> "ffffff".
            new Field("color", FieldType.color),
            new Field("colorOct", FieldType.number),
            new Field("colorPersistence", FieldType.number),
            new Field("colorScale", FieldType.number),
            new Field("colorThreshold", FieldType.number))),
        new Spec("SunMesh", List.of(
            new Field("divisions", FieldType.number),
            new Field("octaves", FieldType.number),
            new Field("persistence", FieldType.number),
            new Field("scl", FieldType.number),
            new Field("pow", FieldType.number),
            new Field("mag", FieldType.number),
            new Field("colorScale", FieldType.number),
            new Field("colors", FieldType.textArray))),
        new Spec("HexSkyMesh", List.of(
            new Field("seed", FieldType.number),
            new Field("speed", FieldType.number),
            new Field("radius", FieldType.number),
            new Field("divisions", FieldType.number),
            new Field("color", FieldType.color),
            new Field("octaves", FieldType.number),
            new Field("persistence", FieldType.number),
            new Field("scale", FieldType.number),
            new Field("thresh", FieldType.number))),
        // MultiMesh: "meshes" is a list of child meshes (editor builds them recursively).
        new Spec("MultiMesh", List.of()),
        // MatMesh: "mesh" is a nested mesh, "mat" a raw Mat3D matrix (editor special-cases both).
        new Spec("MatMesh", List.of()));

    /** The type names parseMesh accepts; anything else throws "Unknown mesh type". */
    public static final Set<String> PARSEABLE =
        ALL.stream().map(Spec::type).collect(Collectors.toUnmodifiableSet());

    /** The spec for a mesh type name, or null when the game cannot parse that type. */
    public static Spec spec(String type) {
        String canonical = canonical(type);
        for (Spec spec : ALL) {
            if (spec.type().equals(canonical)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * The type name parseMesh ends up switching on: {@code Strings.capitalize} raises the first
     * letter and nothing else, so {@code noiseMesh} is the NoiseMesh type while {@code noisemesh}
     * stays an unknown type the game rejects.
     */
    public static String canonical(String type) {
        if (type == null || type.isEmpty() || !Character.isLowerCase(type.charAt(0))) {
            return type;
        }
        return Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    /** Whether the game can build this type from JSON (after the same capitalisation). */
    public static boolean isParseable(String type) {
        return spec(type) != null;
    }
}

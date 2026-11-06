package dev.modmaker.core.fmt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.noise.SimplexNoise;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Planet surface/cloud mesh construction, mirroring MeshBuilder.buildHex + the game's mesh classes:
 * every planet mesh is a set of grid tiles whose corners are scaled by (1 + height * intensity) *
 * radius, colored per tile by the mesher, optionally skipped (clouds). Simplex noise calls match the
 * game exactly (NoiseMesh uses offsets 5 + position with seeds 7 + seed and 8 + seed).
 */
public final class PlanetMeshes {

    private PlanetMeshes() {
    }

    /** Simple RGBA color used by the mesher math. */
    public record Rgb(float r, float g, float b, float a) {
        public static Rgb of(String hex) {
            String text = hex.startsWith("#") ? hex.substring(1) : hex;
            long value = Long.parseUnsignedLong(text.length() == 6 ? text + "ff" : text, 16);
            return new Rgb(
                (float) ((value >> 24) & 0xFF) / 255f,
                (float) ((value >> 16) & 0xFF) / 255f,
                (float) ((value >> 8) & 0xFF) / 255f,
                (float) (value & 0xFF) / 255f);
        }
    }

    /** Per-tile mesher callbacks, mirroring the game's HexMesher interface. */
    public interface Mesher {
        float height(PlanetGrids.Vec3 position);

        Rgb color(PlanetGrids.Vec3 position);

        default boolean skip(PlanetGrids.Vec3 position) {
            return false;
        }
    }

    /** The rendered geometry of one mesh layer: tile polygons + face colors. */
    public static final class MeshModel {
        public final int sectorSize;
        public final float radius;
        /** Per tile: corners as flat [x,y,z, x,y,z, ...] (already scaled by height * radius). */
        public final List<double[]> tileCorners = new ArrayList<>();
        public final List<Rgb> tileColors = new ArrayList<>();
        public final List<Boolean> tileSkipped = new ArrayList<>();
        public final List<Integer> tileIndexes = new ArrayList<>();

        MeshModel(int sectorSize, float radius) {
            this.sectorSize = sectorSize;
            this.radius = radius;
        }
    }

    // --- mesher builders (defaults mirror ContentParser.parseMesh) ------------------------------

    public static Mesher noiseMesh(int seed, int octaves, float persistence, float scale, float mag,
        Rgb color1, Rgb color2, int colorOct, float colorPersistence, float colorScale,
        float colorThreshold) {
        return new Mesher() {
            @Override
            public float height(PlanetGrids.Vec3 position) {
                return SimplexNoise.noise3d(7 + seed, octaves, persistence, scale,
                    5f + position.x, 5f + position.y, 5f + position.z) * mag;
            }

            @Override
            public Rgb color(PlanetGrids.Vec3 position) {
                double mask = SimplexNoise.noise3d(8 + seed, colorOct, colorPersistence, colorScale,
                    5f + position.x, 5f + position.y, 5f + position.z);
                return mask > colorThreshold ? color2 : color1;
            }
        };
    }

    public static Mesher singleColorMesh(int seed, int octaves, float persistence, float scale,
        float mag, Rgb color) {
        return noiseMesh(seed, octaves, persistence, scale, mag, color, color, 1, 0.5f, 1, 0.5f);
    }

    public static Mesher sunMesh(double octaves, double persistence, double scl, double pow,
        double mag, float colorScale, List<Rgb> colors) {
        return new Mesher() {
            @Override
            public float height(PlanetGrids.Vec3 position) {
                return 0;
            }

            @Override
            public Rgb color(PlanetGrids.Vec3 position) {
                double height = Math.pow(
                    SimplexNoise.noise3d(0, octaves, persistence, scl,
                        position.x, position.y, position.z), pow) * mag;
                int index = Math.max(0, Math.min(colors.size() - 1,
                    (int) (height * colors.size())));
                Rgb color = colors.get(index);
                return new Rgb(color.r() * colorScale, color.g() * colorScale,
                    color.b() * colorScale, color.a());
            }
        };
    }

    public static Mesher hexSkyMesh(int seed, int octaves, float persistence, float scl,
        float thresh, Rgb color) {
        return new Mesher() {
            @Override
            public float height(PlanetGrids.Vec3 position) {
                return 1f;
            }

            @Override
            public Rgb color(PlanetGrids.Vec3 position) {
                return color;
            }

            @Override
            public boolean skip(PlanetGrids.Vec3 position) {
                return SimplexNoise.noise3d(7 + seed, octaves, persistence, scl,
                    position.x, position.y * 3f, position.z) >= thresh;
            }
        };
    }

    // --- config parsing (defaults mirror ContentParser.parseMesh) -------------------------------

    public static Rgb colorOr(JsonElement element, String fallback) {
        if (element != null && element.isJsonPrimitive()) {
            try {
                return Rgb.of(element.getAsString());
            } catch (NumberFormatException ignored) {
                return Rgb.of(fallback);
            }
        }
        return Rgb.of(fallback);
    }

    /** Builds the surface mesher from a planet's "mesh" object (any parseable type; unknown falls back). */
    public static Mesher mesherFromConfig(JsonObject mesh) {
        String type = meshType(mesh);
        switch (type) {
            case "SunMesh": {
                List<Rgb> colors = new ArrayList<>();
                JsonElement raw = mesh.get("colors");
                if (raw != null && raw.isJsonArray()) {
                    for (JsonElement element : raw.getAsJsonArray()) {
                        colors.add(Rgb.of(element.getAsString()));
                    }
                }
                if (colors.isEmpty()) {
                    colors.add(Rgb.of("ff7a38"));
                }
                return sunMesh(intOr(mesh, "octaves", 1), numOr(mesh, "persistence", 0.5f),
                    numOr(mesh, "scl", 1f), numOr(mesh, "pow", 1f), numOr(mesh, "mag", 0.5f),
                    (float) numOr(mesh, "colorScale", 1f), colors);
            }
            case "HexSkyMesh":
                return hexSkyMesh(intOr(mesh, "seed", 0), intOr(mesh, "octaves", 1),
                    (float) numOr(mesh, "persistence", 0.5f), (float) numOr(mesh, "scale", 1f),
                    (float) numOr(mesh, "thresh", 0.5f),
                    colorOr(mesh.get("color"), "ffffff"));
            case "HexMesh":
                // The game renders HexMesh through the planet's generator; serpulo's terrain is
                // the closest visual approximation we can compute without the generator class.
                return serpuloMesher(intOr(mesh, "seed", 0));
            case "MatMesh": {
                JsonElement nested = mesh.get("mesh");
                if (nested != null && nested.isJsonObject()) {
                    return mesherFromConfig(nested.getAsJsonObject());
                }
                return defaultMesher();
            }
            case "MultiMesh": {
                JsonElement children = mesh.get("meshes");
                if (children != null && children.isJsonArray() && children.getAsJsonArray().size() > 0) {
                    return mesherFromConfig(children.getAsJsonArray().get(0).getAsJsonObject());
                }
                return defaultMesher();
            }
            default:
                if (!type.equals("NoiseMesh")) {
                    return defaultMesher(); // unparseable types fall back
                }
                return noiseMesh(intOr(mesh, "seed", 0), intOr(mesh, "octaves", 1),
                    (float) numOr(mesh, "persistence", 0.5f), (float) numOr(mesh, "scale", 1f),
                    (float) numOr(mesh, "mag", 0.5f),
                    colorOr(mesh.get("color1"), stringOr(mesh, "color", "ffffff")),
                    colorOr(mesh.get("color2"), stringOr(mesh, "color", "ffffff")),
                    intOr(mesh, "colorOct", 1), (float) numOr(mesh, "colorPersistence", 0.5f),
                    (float) numOr(mesh, "colorScale", 1f), (float) numOr(mesh, "colorThreshold", 0.5f));
        }
    }

    public static Mesher defaultMesher() {
        return singleColorMesh(0, 1, 0.5f, 1f, 0.5f, Rgb.of("ffffff"));
    }

    // --- vanilla serpulo terrain (port of SerpuloPlanetGenerator, visual path) ------------------

    /** The block palette of serpulo's terrain: colours sampled from the vanilla sprites (the game
     * derives mapColor from the sprite's centre pixel), alpha = 1 - block.albedo so the renderer
     * can add the same specular the game's shader derives from albedo. */
    private static final Rgb WATER = new Rgb(0x59 / 255f, 0x6a / 255f, 0xb8 / 255f, 0.1f),
        DEEP_WATER = new Rgb(0x4d / 255f, 0x5c / 255f, 0xa4 / 255f, 0.1f),
        TAINTED_WATER = new Rgb(0x5e / 255f, 0x48 / 255f, 0x92 / 255f, 0.1f),
        DEEP_TAINTED_WATER = new Rgb(0x43 / 255f, 0x33 / 255f, 0x6a / 255f, 0.1f),
        DARKSAND_WATER = new Rgb(0x41 / 255f, 0x44 / 255f, 0x66 / 255f, 0.1f),
        SAND_WATER = new Rgb(0x92 / 255f, 0x83 / 255f, 0x9c / 255f, 0.1f),
        DARKSAND = new Rgb(0x3c / 255f, 0x38 / 255f, 0x38 / 255f, 1f),
        SAND = new Rgb(0xd3 / 255f, 0xae / 255f, 0x8d / 255f, 1f),
        SALT = new Rgb(1f, 1f, 1f, 1f),
        DARKSAND_TAINTED_WATER = new Rgb(0x48 / 255f, 0x3d / 255f, 0x5d / 255f, 0.1f),
        STONE = new Rgb(0x54 / 255f, 0x54 / 255f, 0x5b / 255f, 1f),
        SNOW = new Rgb(0xe1 / 255f, 0xe9 / 255f, 0xf0 / 255f, 0.3f),
        ICE_SNOW = new Rgb(0xe1 / 255f, 0xe9 / 255f, 0xf0 / 255f, 0.4f),
        ICE = new Rgb(0xc2 / 255f, 0xbf / 255f, 0xfb / 255f, 0.35f),
        BASALT = new Rgb(0x51 / 255f, 0x51 / 255f, 0x51 / 255f, 1f),
        MOSS = new Rgb(0x6b / 255f, 0x44 / 255f, 0x74 / 255f, 1f),
        HOTROCK = new Rgb(0x3c / 255f, 0x38 / 255f, 0x38 / 255f, 1f),
        SPORE_MOSS = new Rgb(0x6b / 255f, 0x44 / 255f, 0x74 / 255f, 1f),
        SHALE = new Rgb(0x67 / 255f, 0x65 / 255f, 0x93 / 255f, 1f);

    /** Temperature-band × height-step block table, verbatim from SerpuloPlanetGenerator.arr. */
    private static final Rgb[][] SERPULO_ARR = {
        {WATER, DARKSAND_WATER, DARKSAND, DARKSAND, DARKSAND, DARKSAND, SAND, SAND, SAND, SAND, DARKSAND_TAINTED_WATER, STONE, STONE},
        {WATER, DARKSAND_WATER, DARKSAND, DARKSAND, SAND, SAND, SAND, SAND, SAND, DARKSAND_TAINTED_WATER, STONE, STONE, STONE},
        {WATER, DARKSAND_WATER, DARKSAND, SAND, SALT, SAND, SAND, SAND, SAND, DARKSAND_TAINTED_WATER, STONE, STONE, STONE},
        {WATER, SAND_WATER, SAND, SALT, SALT, SALT, SAND, STONE, STONE, STONE, SNOW, ICE_SNOW, ICE},
        {DEEP_WATER, WATER, SAND_WATER, SAND, SALT, SAND, SAND, BASALT, SNOW, SNOW, SNOW, SNOW, ICE},
        {DEEP_WATER, WATER, SAND_WATER, SAND, SAND, SAND, MOSS, ICE_SNOW, SNOW, SNOW, ICE, SNOW, ICE},
        {DEEP_WATER, SAND_WATER, SAND, SAND, MOSS, MOSS, SNOW, BASALT, BASALT, BASALT, ICE, SNOW, ICE},
        {DEEP_TAINTED_WATER, DARKSAND_TAINTED_WATER, DARKSAND, DARKSAND, BASALT, MOSS, BASALT, HOTROCK, BASALT, ICE, SNOW, ICE, ICE},
        {DARKSAND_WATER, DARKSAND, DARKSAND, DARKSAND, MOSS, SPORE_MOSS, SNOW, BASALT, BASALT, ICE, SNOW, ICE, ICE},
        {DARKSAND_WATER, DARKSAND, DARKSAND, SPORE_MOSS, ICE, ICE, SNOW, SNOW, SNOW, SNOW, ICE, ICE, ICE},
        {DEEP_TAINTED_WATER, DARKSAND_TAINTED_WATER, DARKSAND, SPORE_MOSS, SPORE_MOSS, ICE, ICE, SNOW, SNOW, ICE, ICE, ICE, ICE},
        {TAINTED_WATER, DARKSAND_TAINTED_WATER, DARKSAND, SPORE_MOSS, MOSS, SPORE_MOSS, ICE_SNOW, SNOW, ICE, ICE, ICE, ICE, ICE},
        {DARKSAND_WATER, DARKSAND, SNOW, ICE, ICE_SNOW, SNOW, SNOW, SNOW, ICE, ICE, ICE, ICE, ICE}
    };

    /** Vanilla serpulo terrain: ports SerpuloPlanetGenerator.rawHeight/getBlock (visual path, no
     * base lighting), so planets without a mesh config look like they do in the game. */
    public static Mesher serpuloMesher(int seed) {
        return new Mesher() {
            private static final float SCL = 5f, HEIGHT_Y_OFFSET = 42.7f,
                WATER_OFFSET = 0.04f, HEIGHT_SCL = 1.01f, WATER = 2f / 13;

            private float rawHeight(PlanetGrids.Vec3 position) {
                double height = SimplexNoise.noise3d(seed, 7, 0.5f, 1f / 3f,
                    position.x * SCL, position.y * SCL + HEIGHT_Y_OFFSET, position.z * SCL) * HEIGHT_SCL;
                return (float) ((Math.pow(height, 2.3) + WATER_OFFSET) / (1 + WATER_OFFSET));
            }

            @Override
            public float height(PlanetGrids.Vec3 position) {
                return Math.max(rawHeight(position), WATER);
            }

            @Override
            public Rgb color(PlanetGrids.Vec3 position) {
                float height = rawHeight(position);
                float px = position.x * SCL, py = position.y * SCL, pz = position.z * SCL;
                float temp = Math.max(0, Math.min(1, Math.abs(py * 2f) / SCL));
                float tnoise = SimplexNoise.noise3d(seed, 7, 0.56f, 1f / 3f, px, py + 999f - 0.1f, pz);
                temp = temp + (tnoise - temp) * 0.5f;
                height = Math.max(0, Math.min(1, height * 1.2f));
                float tar = (float) (SimplexNoise.noise3d(seed, 4, 0.55f, 1f / 2f, px, py + 999f, pz) * 0.3f
                    + distanceToSouthPole(position) * 0.2f);
                Rgb res = SERPULO_ARR[Math.max(0, Math.min(12, (int) (temp * 13)))][Math
                    .max(0, Math.min(12, (int) (height * 13)))];
                if (tar > 0.5f && (res == MOSS || res == SPORE_MOSS)) {
                    res = SHALE;
                }
                if (res == SALT) {
                    res = SAND; // SerpuloPlanetGenerator.getColor replaces salt with sand
                }
                return res;
            }

            /** position.dst(0, 0, 1) in the game. */
            private double distanceToSouthPole(PlanetGrids.Vec3 position) {
                double dx = position.x, dy = position.y, dz = position.z - 1;
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        };
    }

    /** Cloud layers from a "cloudMesh" object: MultiMesh children, or the mesh itself. */
    public static List<Layer> cloudLayersFromConfig(JsonObject cloud) {
        List<Layer> layers = new ArrayList<>();
        if (cloud == null) {
            return layers;
        }
        String type = meshType(cloud);
        if (type.equals("MultiMesh") && cloud.has("meshes") && cloud.get("meshes").isJsonArray()) {
            for (JsonElement element : cloud.getAsJsonArray("meshes")) {
                if (element != null && element.isJsonObject()) {
                    layers.add(layerFrom(element.getAsJsonObject()));
                }
            }
        } else {
            layers.add(layerFrom(cloud));
        }
        return layers;
    }

    private static Layer layerFrom(JsonObject config) {
        String type = meshType(config);
        // Radius semantics straight from the game sources:
        // - NoiseMesh builds with its JSON radius as an ABSOLUTE mesh radius
        //   (NoiseMesh.java: buildHex(mesher, divisions, radius, 0.2f));
        // - SunMesh/HexMesh-style builds use planet.radius (HexMesh.java) and the sun's shader
        //   is unlit (no diffuse shading at all);
        // - HexSkyMesh builds at planet.radius with the JSON radius as the buildHex intensity
        //   (HexSkyMesh.java), and rotates with Time.globalTime * speed / 40.
        boolean sky = "HexSkyMesh".equals(type);
        boolean sun = "SunMesh".equals(type);
        float intensity = sky ? (float) numOr(config, "radius", 1f) : 0.2f;
        boolean relative = sky || sun;
        float radius = relative ? 1f : (float) numOr(config, "radius", 1f);
        int divisions = Math.max(1, intOr(config, "divisions", sky ? 3 : 1));
        float cloudSpeed = sky ? (float) numOr(config, "speed", 0f) : 0f;
        Mesher mesher = sky
            ? hexSkyMesh(intOr(config, "seed", 0), intOr(config, "octaves", 1),
                (float) numOr(config, "persistence", 0.5f), (float) numOr(config, "scale", 1f),
                (float) numOr(config, "thresh", 0.5f), colorOr(config.get("color"), "ffffff"))
            : mesherFromConfig(config);
        return new Layer(mesher, intensity, radius, divisions, sun, sky, relative, cloudSpeed);
    }

    /**
     * One rendered mesh layer: a mesher plus its radius, buildHex intensity and grid divisions.
     * {@code unlit} mirrors Shaders.unlit (SunMesh renders raw albedo), {@code sky} marks
     * HexSkyMesh layers (clouds.vert shading: no specular, alpha from the colour), {@code
     * radiusIsPlanetRelative} mirrors which buildHex call the game uses (planet.radius vs the
     * JSON radius), and {@code cloudSpeed} drives the HexSkyMesh rotation (relRot = time * speed / 40).
     */
    public record Layer(Mesher mesher, float intensity, float radius, int divisions,
        boolean unlit, boolean sky, boolean radiusIsPlanetRelative, float cloudSpeed) {
    }

    /**
     * Normalises a "mesh"/"cloudMesh" value into the object form the layer extractors accept.
     * The game's JSON parser treats a bare array as a MultiMesh shorthand
     * (ContentParser.parseMesh: {@code if(data.isArray()) return new MultiMesh(...)}), and real
     * mods write their multi-biome planets that way - so {@code [{...},{...}]} becomes
     * {@code {type: MultiMesh, meshes: [...]}}. Returns null for absent or non-mesh values.
     */
    /** The mesh type name, canonicalised the way the game does (Strings.capitalize). */
    private static String meshType(JsonObject mesh) {
        return MeshTypes.canonical(stringOr(mesh, "type", "NoiseMesh"));
    }

    public static JsonObject normalizeMeshConfig(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        if (value.isJsonArray() && !value.getAsJsonArray().isEmpty()) {
            JsonObject multi = new JsonObject();
            multi.addProperty("type", "MultiMesh");
            multi.add("meshes", value.getAsJsonArray());
            return multi;
        }
        return null;
    }

    /**
     * Raw layer objects of a "mesh"/"cloudMesh" value, for structured editing: a MultiMesh object
     * contributes its children, a bare array (the game's MultiMesh shorthand) its elements, a
     * single mesh object itself. The inverse of packing one object / an array.
     */
    public static List<JsonObject> meshLayerConfigs(JsonElement value) {
        JsonObject normalized = normalizeMeshConfig(value);
        List<JsonObject> layers = new ArrayList<>();
        if (normalized == null) {
            return layers;
        }
        String type = meshType(normalized);
        if (type.equals("MultiMesh") && normalized.has("meshes")
            && normalized.get("meshes").isJsonArray()) {
            for (JsonElement element : normalized.getAsJsonArray("meshes")) {
                if (element != null && element.isJsonObject()) {
                    layers.add(element.getAsJsonObject());
                }
            }
        } else {
            layers.add(normalized);
        }
        return layers;
    }

    /**
     * All surface layers of a "mesh" object: MultiMesh children render as separate shells (rings,
     * moons), MatMesh wraps one nested mesh, everything else is a single layer.
     */
    public static List<Layer> surfaceLayersFromConfig(JsonObject mesh) {
        List<Layer> layers = new ArrayList<>();
        if (mesh == null) {
            return layers;
        }
        String type = meshType(mesh);
        if (type.equals("MultiMesh") && mesh.has("meshes") && mesh.get("meshes").isJsonArray()) {
            for (JsonElement element : mesh.getAsJsonArray("meshes")) {
                if (element != null && element.isJsonObject()) {
                    layers.add(layerFrom(element.getAsJsonObject()));
                }
            }
        } else {
            layers.add(layerFrom(mesh));
        }
        return layers;
    }

    // --- model building -------------------------------------------------------------------------

    /** Builds tile geometry for one layer, mirroring MeshBuilder.buildHex. */
    public static MeshModel buildModel(int sectorSize, float radius, Mesher mesher, float intensity) {
        MeshModel model = new MeshModel(sectorSize, radius);
        if (sectorSize <= 0) {
            return model;
        }
        PlanetGrids.Grid grid = PlanetGrids.create(sectorSize);
        float[] heights = new float[grid.corners.length];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = (1f + mesher.height(grid.corners[i].v) * intensity) * radius;
        }
        for (int i = 0; i < grid.tiles.length; i++) {
            PlanetGrids.Tile tile = grid.tiles[i];
            if (mesher.skip(tile.v)) {
                model.tileSkipped.add(true);
                model.tileColors.add(null);
                model.tileCorners.add(new double[0]);
                model.tileIndexes.add(i);
                continue;
            }
            model.tileSkipped.add(false);
            model.tileColors.add(mesher.color(tile.v));
            double[] corners = new double[tile.corners.length * 3];
            for (int k = 0; k < tile.corners.length; k++) {
                PlanetGrids.Corner corner = tile.corners[k];
                float height = heights[corner.id];
                corners[k * 3] = corner.v.x * height;
                corners[k * 3 + 1] = corner.v.y * height;
                corners[k * 3 + 2] = corner.v.z * height;
            }
            model.tileCorners.add(corners);
            model.tileIndexes.add(i);
        }
        return model;
    }

    // --- small parsers --------------------------------------------------------------------------

    public static String stringOr(JsonObject object, String key, String fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    public static int intOr(JsonObject object, String key, int fallback) {
        JsonElement value = object == null ? null : object.get(key);
        if (value != null && value.isJsonPrimitive()) {
            try {
                return value.getAsInt();
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static double numOr(JsonObject object, String key, double fallback) {
        JsonElement value = object == null ? null : object.get(key);
        if (value != null && value.isJsonPrimitive()) {
            try {
                return value.getAsDouble();
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** Locale-independent parse guard used by callers formatting noise inputs. */
    public static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}

package dev.modmaker.core.validate;

import com.google.gson.JsonElement;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.MeshTypes;
import dev.modmaker.core.fmt.PlanetGrids;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Planets and their presets: a sector id is bounded by the planet's sectors, which are the
 * geodesic grid's 10 x 3^sectorSize + 2 tiles plus the one degenerate sector the game always
 * appends (Planet.java:227-233 builds the tiles, ContentParser.java:856-857 adds the extra
 * sector). {@link PlanetGrids#capacity} is exactly that count.
 */
final class PlanetCheck implements ProjectCheck {

    /**
     * Planet generators the game ships (mindustry.maps.planet and maps.generators, v160.5). None of
     * them is honoured - the registered PlanetGenerator parser ignores the JSON value and always
     * builds an AsteroidGenerator (ContentParser.java:270-276) - so this list only tells a name the
     * game knows from one it does not, which is what the warning below reports.
     */
    private static final Set<String> VANILLA_GENERATORS = Set.of("PlanetGenerator",
        "BlankPlanetGenerator", "AsteroidGenerator", "ErekirPlanetGenerator",
        "SerpuloPlanetGenerator", "TantrosPlanetGenerator");

    /**
     * Planet meshes the game ships (mindustry.graphics.g3d plus graphics/CubemapMesh.java, v160.5);
     * parseMesh switches over the type name (ContentParser.java:1128-1156), so a name outside this
     * list cannot be built. The parseable subset lives in {@link MeshTypes}.
     */
    private static final Set<String> VANILLA_MESHES = Set.of("GenericMesh", "HexMesh", "HexSkyMesh",
        "MatMesh", "MultiMesh", "NoiseMesh", "PlanetMesh", "ShaderSphereMesh", "SunMesh", "CubemapMesh");

    @Override
    public String id() {
        return "planets";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        Map<String, ContentRecord> planets = new LinkedHashMap<>();
        // A script mod (rhino JS) can define its own classes; a pure JSON mod cannot.
        boolean scriptCapable = project.assets.stream()
            .anyMatch(asset -> asset.path.startsWith("scripts/"));
        for (ContentRecord record : project.contents) {
            if (record.type != ContentType.planet || record.isInline()) {
                continue;
            }
            planets.put(record.name, record);
            JsonElement generator = record.fields.get("generator");
            if (generator != null && generator.isJsonPrimitive()
                && !VANILLA_GENERATORS.contains(generator.getAsString())) {
                // The game's PlanetGenerator parser ignores the JSON value completely and always
                // returns an AsteroidGenerator (ContentParser.java:270-276), so this name has no
                // effect on the planet - but the planet still loads, so it is not an error. Scripts
                // cannot supply a generator either: the parser is a plain Java lambda, not a class
                // map lookup, hence the same wording for a script mod. Names the game does ship stay
                // silent even though their value is ignored too, because every vanilla planet file
                // names one and warning on those would be pure noise.
                context.report(Issue.warningOn(record.id, "validate.planet.badGenerator",
                    record.name, generator.getAsString()));
            }
            Double sectorSize = CheckSupport.number(record.fields.get("sectorSize"));
            if (sectorSize != null && sectorSize > 0
                && !PlanetGrids.isRepresentable((int) Math.ceil(sectorSize))) {
                // The game derives an int-sized grid from this value (10 * 3^n + 2 tiles, 30 * 3^n
                // edges; Planet.java:227-233), so past the representable range the planet cannot be
                // built at all - and its sector count would wrap around to a nonsense number.
                context.report(Issue.errorOn(record.id, "validate.planet.sectorSizeTooLarge",
                    record.name, (int) Math.ceil(sectorSize), PlanetGrids.MAX_SECTOR_SIZE));
            }
            checkMeshType(context, record, record.fields.get("mesh"), "mesh", scriptCapable);
            checkMeshType(context, record, record.fields.get("cloudMesh"), "cloudMesh", scriptCapable);
        }
        for (ContentRecord record : project.contents) {
            if (record.type != ContentType.sector || record.isInline()) {
                continue;
            }
            Double sectorIndex = CheckSupport.number(record.fields.get("sector"));
            if (sectorIndex == null) {
                continue;
            }
            Integer sectors = planetSectorCount(context, project, planets, record);
            if (sectors == null) {
                continue;
            }
            if (sectorIndex < 0 || sectorIndex >= sectors) {
                context.report(Issue.warningOn(record.id, "validate.sector.outOfRange",
                    record.name, (int) Math.ceil(sectorIndex), sectors, sectors - 1));
            }
        }
    }

    private void checkMeshType(ValidationContext context, ContentRecord record, JsonElement mesh,
        String field, boolean scriptCapable) {
        if (mesh == null) {
            return;
        }
        // A bare array is the MultiMesh shorthand parseMeshes takes directly, but anything that is
        // neither an object nor an array is fatal: the planet parser rejects it with "Meshes must be
        // objects." while the content is being read (ContentParser.java:813-815 for mesh, 828-830 for
        // cloudMesh), so the planet never loads.
        if (!mesh.isJsonObject()) {
            if (!mesh.isJsonArray()) {
                context.report(Issue.errorOn(record.id, "validate.planet.meshNotObject",
                    record.name, field));
            }
            return;
        }
        JsonElement type = mesh.getAsJsonObject().get("type");
        if (type == null || !type.isJsonPrimitive()) {
            return;
        }
        String name = MeshTypes.canonical(type.getAsString());
        if (VANILLA_MESHES.contains(name)) {
            if (!MeshTypes.isParseable(name)) {
                context.report(Issue.warningOn(record.id, "validate.planet.meshUnparseable",
                    record.name, field, name, String.join(", ", MeshTypes.PARSEABLE)));
            }
        } else if (scriptCapable) {
            context.report(Issue.warningOn(record.id, "validate.planet.meshUnknownScript",
                record.name, field, name));
        } else {
            context.report(Issue.errorOn(record.id, "validate.planet.meshUnknownJson",
                record.name, field, name));
        }
    }

    /** Sector slots of the planet a sector preset sits on, or null when it cannot be determined. */
    private Integer planetSectorCount(ValidationContext context, ModProject project,
        Map<String, ContentRecord> planets, ContentRecord sector) {
        JsonElement planetRef = sector.fields.get("planet");
        if (planetRef == null || !planetRef.isJsonPrimitive()) {
            return null;
        }
        String name = CheckSupport.stripPrefix(planetRef.getAsString(), project.internalName());
        ContentRecord planet = planets.get(name);
        Double size = planet == null ? null : CheckSupport.number(planet.fields.get("sectorSize"));
        if (planet != null && (size == null || size <= 0)) {
            context.report(Issue.warningOn(sector.id, "validate.planet.noSectorSize",
                planet.name, sector.name));
            return null;
        }
        if (planet == null) {
            Integer vanillaSize = PlanetGrids.VANILLA_SIZES.get(name.toLowerCase(Locale.ROOT));
            if (vanillaSize == null) {
                return null;
            }
            return PlanetGrids.capacity(vanillaSize);
        }
        if (planet != null && size != null && !PlanetGrids.isRepresentable((int) Math.ceil(size))) {
            // PlanetCheck already reported this once; a sector count here would be meaningless.
            return null;
        }
        return PlanetGrids.capacity((int) Math.ceil(size));
    }
}

package dev.modmaker.core.validate;

import com.google.gson.JsonElement;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.fmt.SpriteRegions;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.ClassSchema;
import dev.modmaker.core.schema.SchemaRegistry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-record rules: identity (inline host, duplicate names, file-name safety, file-backed type) and
 * the class/spite side of the record (declared class, block size, unit type/template, sprite region).
 */
final class RecordCheck implements ProjectCheck {

    /** Vars.maxBlockSize; the block parser throws for anything larger (ContentParser.java:604-605). */
    private static final int MAX_BLOCK_SIZE = 16;

    @Override
    public String id() {
        return "records";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        SchemaRegistry registry = context.registry();
        String internal = context.internalName();
        Set<String> spriteRegions = context.spriteRegions();

        Map<String, ContentRecord> byName = new LinkedHashMap<>();
        Map<String, ContentRecord> byFoldedName = new LinkedHashMap<>();
        for (ContentRecord record : project.contents) {
            if (record.isInline()) {
                // Nested content has no name of its own - it is just an object inside a host file,
                // so duplicate names and file-name rules do not apply to it.
                if (project.contentById(record.inlineOwnerId) == null) {
                    context.report(Issue.errorOn(record.id, "validate.inline.noHost",
                        record.type, record.name));
                }
                continue;
            }
            String key = record.type.name() + ":" + record.name;
            ContentRecord previous = byName.put(key, record);
            if (previous != null) {
                // The game only throws when a *new* content object is registered twice
                // (ContentLoader.handleMappableContent): patches reuse the located content, and new
                // content registers under "<mod>-<name>", so duplicates that involve a patch are
                // tolerated and only the all-new case fails the file.
                if (!record.patch && !previous.patch) {
                    context.report(Issue.errorOn(record.id, "validate.name.duplicate",
                        record.type, record.name));
                } else {
                    context.report(Issue.warningOn(record.id, "validate.name.duplicatePatch",
                        record.type, record.name));
                }
            } else {
                // Names that differ only in case are distinct content in the game's maps, but they
                // cannot coexist as files on Windows/macOS: the project folder itself would lose
                // one of the two records on the next save.
                String foldedKey = key.toLowerCase(Locale.ROOT);
                ContentRecord folded = byFoldedName.get(foldedKey);
                if (folded != null && !folded.name.equals(record.name)) {
                    context.report(Issue.errorOn(record.id, "validate.name.caseCollision",
                        record.type, folded.name, record.name));
                } else if (folded == null) {
                    byFoldedName.put(foldedKey, record);
                }
            }
            String problem = NameRules.contentNameProblem(record.name);
            if (problem != null) {
                context.report(Issue.errorOn(record.id, "validate.name.invalid",
                    record.type, record.name, problem));
            }
            if (!record.type.fileBacked()) {
                context.report(Issue.errorOn(record.id, "validate.type.notFileBacked",
                    record.type, record.name, record.type.folderName()));
            }
            validateClass(registry, record, context);
            validateSprites(registry, record, internal, spriteRegions, context);
        }
    }

    private void validateClass(SchemaRegistry registry, ContentRecord record,
        ValidationContext context) {
        if (record.type == ContentType.block) {
            checkBlockSize(record, context);
        }
        String declared = record.className();
        if (record.type == ContentType.unit) {
            validateUnitType(registry, record, declared, context);
            validateUnitTemplate(registry, record, context);
            return;
        }
        if (declared == null) {
            if (record.type.defaultClass().isEmpty()) {
                context.report(Issue.errorOn(record.id, "validate.class.missingType",
                    record.type, record.name, record.type));
            }
            return;
        }
        // Canonicalised the way the game does before it consults its class map, so a mod that writes
        // "beamDrill" resolves to BeamDrill instead of being reported as unknown.
        String simple = SchemaRegistry.canonicalClassName(declared);
        ClassSchema schema = registry.schema(simple);
        if (schema == null) {
            context.report(Issue.warningOn(record.id, "validate.class.unknown",
                record.type, record.name, declared));
            return;
        }
        if (record.patch && record.type == ContentType.block) {
            context.report(Issue.warningOn(record.id, "validate.block.patchWithType", record.name));
        }
        if (!record.patch && schema.ctype != null && schema.ctype != record.type) {
            context.report(Issue.warningOn(record.id, "validate.class.typeMismatch",
                record.type, record.name, declared, schema.ctype));
        }
    }

    /**
     * A block larger than Vars.maxBlockSize is refused outright: the block parser throws
     * "Blocks cannot be larger than 16" (ContentParser.java:604-605, maxBlockSize in Vars.java:103)
     * after the fields are read, so the whole content file fails to load. The check sits in the read
     * step shared by new blocks and patches (ContentParser.java:596-612), so it covers both. The
     * game's field is an int (Block.java:218), which is why the declared value is truncated first -
     * "size": 16.9 is a size-16 block in game, not an error.
     */
    private void checkBlockSize(ContentRecord record, ValidationContext context) {
        Double size = CheckSupport.number(record.fields.get("size"));
        if (size == null) {
            return;
        }
        int declared = size.intValue();
        if (declared > MAX_BLOCK_SIZE) {
            context.report(Issue.errorOn(record.id, "validate.block.tooLarge",
                record.name, declared, MAX_BLOCK_SIZE));
        }
    }

    /**
     * A unit's "type" is not a class: it names the entity the unit spawns, from a closed set the
     * game switches over (ContentParser.unitType) and throws on anything else - so a class name
     * there is a hard error, not a fall back to raw JSON. Omitting it is fine (the game defaults to
     * the plain flying entity).
     */
    private void validateUnitType(SchemaRegistry registry, ContentRecord record, String declared,
        ValidationContext context) {
        // The game throws "Unit '...' has an incorrect type. Types must be strings."
        // (ContentParser.java:627-628) for an object/array here, which className() cannot see
        // (it only reads primitives) - report it before the null-declared early return.
        JsonElement type = record.fields.get("type");
        if (type != null && (type.isJsonObject() || type.isJsonArray())) {
            context.report(Issue.errorOn(record.id, "validate.unit.typeNotString", record.name));
            return;
        }
        if (declared == null || registry.unitTypes().isEmpty()) {
            return;
        }
        if (registry.isUnitType(declared)) {
            return;
        }
        context.report(Issue.errorOn(record.id, "validate.unit.badType",
            record.name, declared, registry.unitTypes()));
    }

    /**
     * A unit's class comes from "template" (ContentParser.java:622). An unknown name makes the
     * game's {@code resolve} log "No type ... found, defaulting to UnitType" and build the plain
     * class (ContentParser.java:1454), so a typo silently drops the template's constructor defaults
     * - TankUnitType sets speed/rotateSpeed, MissileUnitType a dozen more - instead of failing.
     * That is worth a warning, not an error.
     *
     * <p>The template does not change which fields exist, which is why the field set is still read
     * from UnitType: every template class the game ships (ErekirUnitType, TankUnitType,
     * MissileUnitType, NeoplasmUnitType) declares no field of its own - each only assigns inherited
     * UnitType fields in its constructor.
     */
    private void validateUnitTemplate(SchemaRegistry registry, ContentRecord record,
        ValidationContext context) {
        JsonElement template = record.fields.get("template");
        if (template == null || !template.isJsonPrimitive()
            || !template.getAsJsonPrimitive().isString()) {
            return;
        }
        String name = template.getAsString().trim();
        // A dotted name is a fully qualified class the game resolves by name; whether it exists can
        // only be known by loading it, so stay silent rather than guessing. Like validateClass, the
        // name is canonicalised first: the game capitalises the first letter before its class-map
        // lookup (ContentParser.java:1438), so "tankUnitType" resolves fine.
        if (name.isEmpty() || name.indexOf('.') >= 0
            || registry.classNames().contains(SchemaRegistry.canonicalClassName(name))) {
            return;
        }
        context.report(Issue.warningOn(record.id, "validate.unit.badTemplate", record.name, name));
    }

    /** Blocks and units need sprites; items, liquids and the rest do not. */
    private void validateSprites(SchemaRegistry registry, ContentRecord record, String internal,
        Set<String> regions, ValidationContext context) {
        if (!SpriteRegions.needsSprite(record)) {
            return;
        }
        String className = registry.resolveClass(record.type, record.className());
        if (className != null && CheckSupport.isEnvironmentClass(registry, className)) {
            // Environment sprites are checked by EnvironmentSpriteCheck, which knows the tile
            // conventions and the folder the map editor reads them from.
            return;
        }
        String region = SpriteRegions.expectedRegion(record, internal);
        if (regions.isEmpty() || SpriteRegions.hasSprite(regions, region)) {
            return;
        }
        context.report(Issue.warningOn(record.id, "validate.sprite.missing", record.type, record.name,
            region, record.name, record.name, record.name));
    }
}

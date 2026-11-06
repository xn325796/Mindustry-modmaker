package dev.modmaker.core.fmt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;
import dev.modmaker.core.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the reference graph in sync when content records are renamed or deleted.
 *
 * <p>Content files are edited as plain JSON, so a reference is just a string. Renaming a record
 * used to leave every {@code requirements}, {@code research} and {@code ammoTypes} value pointing
 * at a name that no longer exists, silently breaking the project. This class rewrites those values
 * in one place and reports exactly which records changed, so the caller can make the whole rewrite
 * one undo step.
 *
 * <p>Name matching follows the game's two lookup paths. The prefixed spelling
 * {@code <mod>-<name>} is unambiguous and is always rewritten. The bare spelling is only rewritten
 * when it actually means this mod's content: for the vanilla-first fields listed in
 * {@link #vanillaFirst(FieldDef)} - status references, {@code planet} and {@code research} - a bare
 * name that also names vanilla content keeps binding to vanilla and is left alone. Everywhere else
 * the game resolves the mod's content first, so a bare name of a target is that target.
 */
public final class ReferenceRewrite {

    private static final String RESEARCH = "research";
    private static final String PARENT = "parent";
    private static final String OBJECTIVES = "objectives";
    private static final String REQUIREMENTS = "requirements";
    private static final String TYPE = "type";
    private static final String CONTENT = "content";
    private static final String PRESET = "preset";
    private static final String PLANET = "planet";

    /** Object keys the game reads as a content reference inside a stack-like value. */
    private static final List<String> CONTENT_KEYS =
        List.of("item", "liquid", "payload", "block", "unit", "content");

    /**
     * Fields the curated schema models as raw JSON but the game still reads as references. Keeping
     * them here (rather than in the schema data) avoids changing the shipped schema files.
     */
    private static final List<String> EXTRA_REFERENCE_FIELDS =
        List.of("requirements", "ammoTypes", "consumes", "plans", "upgrades", "ammo");

    private ReferenceRewrite() {
    }

    /** One record whose field tree changed, with the exact trees before and after. */
    public record Change(ContentRecord record, JsonObject before, JsonObject after) {
    }

    /**
     * The records touched by one rewrite, plus the {@code meta.contentOrder} list on both sides.
     * Undo restores the "before" side, redo applies the "after" side.
     */
    public record Result(List<Change> changes, List<String> orderBefore, List<String> orderAfter) {
        public boolean changed() {
            return !changes.isEmpty() || !orderBefore.equals(orderAfter);
        }
    }

    /**
     * Rewrites every reference to {@code target} from {@code oldName} to {@code newName} and
     * returns the changed records. Patch records are never renamed: their name is the vanilla name
     * they override, so the caller must leave them alone.
     */
    public static Result rename(ModProject project, SchemaRegistry registry, ContentRecord target,
        String oldName, String newName) {
        if (project == null || target == null || target.patch || oldName == null || newName == null
            || oldName.equals(newName)) {
            return unchanged(project);
        }
        Match match = new Match(project, registry, List.of(new Target(project, target, oldName,
            newName)));
        return rewrite(project, registry, match);
    }

    /** Removes every reference to the given records as part of deleting them in one step. */
    public static Result remove(ModProject project, SchemaRegistry registry,
        List<ContentRecord> targets) {
        if (project == null || targets == null || targets.isEmpty()) {
            return unchanged(project);
        }
        List<Target> refs = new ArrayList<>();
        for (ContentRecord target : targets) {
            // A patch is registered under the vanilla name it overrides; deleting the patch brings
            // vanilla content back, so references to that name still mean vanilla and must stay.
            if (target != null && !target.patch) {
                refs.add(new Target(project, target, target.name, null));
            }
        }
        if (refs.isEmpty()) {
            return unchanged(project);
        }
        return rewrite(project, registry, new Match(project, registry, refs));
    }

    /** Convenience overload for one record. */
    public static Result remove(ModProject project, SchemaRegistry registry, ContentRecord target) {
        return target == null ? unchanged(project) : remove(project, registry, List.of(target));
    }

    private static Result unchanged(ModProject project) {
        List<String> order = project == null ? List.of() : List.copyOf(project.meta.contentOrder);
        return new Result(List.of(), order, order);
    }

    private static Result rewrite(ModProject project, SchemaRegistry registry, Match match) {
        List<String> orderBefore = List.copyOf(project.meta.contentOrder);
        List<Change> changes = new ArrayList<>();
        // Copy the list first so a concurrent caller cannot see a half-rewritten collection.
        for (ContentRecord record : List.copyOf(project.contents)) {
            JsonObject before = record.tree().deepCopy();
            rewriteRecord(record, registry, match);
            JsonObject after = record.tree();
            if (!before.equals(after)) {
                record.dirty = true;
                changes.add(new Change(record, before, after.deepCopy()));
            }
        }
        if (rewriteContentOrder(project, match)) {
            project.metaDirty = true;
        }
        return new Result(List.copyOf(changes), orderBefore,
            List.copyOf(project.meta.contentOrder));
    }

    private static void rewriteRecord(ContentRecord record, SchemaRegistry registry, Match match) {
        String className = registry.resolveClass(record.type, record.className());
        Set<String> handled = new LinkedHashSet<>();
        if (className != null) {
            for (FieldDef field : registry.fieldsOf(className)) {
                if (!field.isReference()) {
                    continue;
                }
                handled.add(field.name());
                if (RESEARCH.equals(field.name())) {
                    // research is not a class field in the game's parser either; it is handled
                    // below with its own shape (parent/objectives/planet).
                    continue;
                }
                if (field.type() == FieldType.struct && "ammoMap".equals(field.stackKind())) {
                    // The map KEYS are the item/liquid references, not the values.
                    rewriteAmmoMap(record, field.name(), match, keyTypeOf(field));
                    continue;
                }
                rewriteField(record, field.name(), match, field, hintOf(field));
            }
        }
        for (String extra : EXTRA_REFERENCE_FIELDS) {
            // A unit factory's "requirements" is modelled as raw JSON while a block's is a stack
            // array; only the unmodelled spelling needs the extra pass.
            if (!handled.contains(extra) && record.fields.containsKey(extra)) {
                if ("ammoTypes".equals(extra)) {
                    // ammoTypes is ObjectMap<Item, BulletType>: the map KEY is the item name, not
                    // just a value. Rewriting only values left every turret pointing at the old
                    // item after a rename or delete.
                    rewriteAmmoMap(record, extra, match, ContentType.item);
                } else if ("ammo".equals(extra)) {
                    // LiquidTurret.ammo: the same map shape over liquids.
                    rewriteAmmoMap(record, extra, match, ContentType.liquid);
                } else if (REQUIREMENTS.equals(extra)) {
                    // Unit factory requirements: block plus ItemStacks.
                    rewriteField(record, extra, match, null, ContentType.item);
                } else if ("plans".equals(extra)) {
                    // {unit, requirements, time} plans: the unit key is the reference.
                    rewriteField(record, extra, match, null, ContentType.unit);
                } else if ("upgrades".equals(extra)) {
                    // Reconstructor upgrades are [[fromUnit, toUnit], ...] pairs of bare names.
                    rewriteUpgrades(record, match);
                } else {
                    rewriteField(record, extra, match, null, null);
                }
            }
        }
        rewriteResearch(record, match);
    }

    /** The content type a struct map field's keys name: items for a turret, liquids otherwise. */
    private static ContentType keyTypeOf(FieldDef field) {
        String ctype = field.contentCtype();
        if (ctype != null && !ctype.isEmpty()) {
            try {
                return ContentType.valueOf(ctype);
            } catch (IllegalArgumentException unknown) {
                return ContentType.item;
            }
        }
        return ContentType.item;
    }

    /** Rewrites the unit pairs of a Reconstructor's {@code upgrades}: [[from, to], ...]. */
    private static void rewriteUpgrades(ContentRecord record, Match match) {
        JsonElement current = record.fields.get("upgrades");
        if (current == null || current.isJsonNull() || !current.isJsonArray()) {
            return;
        }
        JsonArray out = new JsonArray();
        for (JsonElement pair : current.getAsJsonArray()) {
            if (!pair.isJsonArray()) {
                out.add(pair.deepCopy());
                continue;
            }
            JsonArray rewritten = new JsonArray();
            boolean removed = false;
            for (JsonElement unit : pair.getAsJsonArray()) {
                if (unit.isJsonPrimitive() && unit.getAsJsonPrimitive().isString()) {
                    Target target = match.matchUnit(unit.getAsString());
                    if (target != null && match.removing()) {
                        removed = true;
                        break;
                    }
                    rewritten.add(target != null
                        ? new JsonPrimitive(target.replacement(unit.getAsString()))
                        : unit.deepCopy());
                } else {
                    rewritten.add(unit.deepCopy());
                }
            }
            if (!removed && rewritten.size() > 0) {
                out.add(rewritten);
            }
        }
        JsonElement before = record.fields.get("upgrades");
        if (out.isEmpty() && match.removing()) {
            record.fields.remove("upgrades");
        } else if (!out.equals(before)) {
            record.fields.put("upgrades", out);
        }
    }

    private static void rewriteField(ContentRecord record, String field, Match match,
        FieldDef definition, ContentType hint) {
        JsonElement current = record.fields.get(field);
        if (current == null || current.isJsonNull()) {
            return;
        }
        JsonElement rewritten = rewriteValue(current.deepCopy(), match, definition, hint);
        if (rewritten == null || (match.removing() && rewritten.isJsonArray()
            && rewritten.getAsJsonArray().isEmpty())) {
            record.fields.remove(field);
            return;
        }
        if (!rewritten.equals(current)) {
            record.fields.put(field, rewritten);
        }
    }

    /** Rewrites the item/liquid keys (and nested values) of an ammo map. */
    private static void rewriteAmmoMap(ContentRecord record, String fieldName, Match match,
        ContentType keyType) {
        JsonElement current = record.fields.get(fieldName);
        if (current == null || current.isJsonNull()) {
            return;
        }
        JsonElement rewritten = rewriteAmmoMapValue(current.deepCopy(), match, keyType);
        if (rewritten == null) {
            record.fields.remove(fieldName);
            return;
        }
        if (!rewritten.equals(current)) {
            record.fields.put(fieldName, rewritten);
        }
    }

    private static JsonElement rewriteAmmoMapValue(JsonElement value, Match match,
        ContentType keyType) {
        // Older/hand-written data sometimes used an array of {item, type} objects. Keep supporting
        // that shape through the generic walker, but treat the real map form's keys as references.
        if (!value.isJsonObject()) {
            JsonElement rewritten = rewriteValue(value, match, null, null);
            return match.removing() && rewritten != null && rewritten.isJsonArray()
                && rewritten.getAsJsonArray().isEmpty() ? null : rewritten;
        }
        JsonObject out = new JsonObject();
        for (var entry : value.getAsJsonObject().entrySet()) {
            Target target = match.matchAmmoKey(entry.getKey(), keyType);
            String key = entry.getKey();
            if (target != null) {
                if (match.removing()) {
                    continue;
                }
                key = target.replacement(key);
            }
            JsonElement nested = rewriteValue(entry.getValue().deepCopy(), match, null, null);
            if (nested != null) {
                out.add(key, nested);
            }
        }
        return out.isEmpty() && match.removing() ? null : out;
    }

    /**
     * Rewrites one field value (on a copy). Returns null when a removal consumed the whole value.
     * Arrays keep their order; objects keep every unknown key exactly as written. {@code hint} is
     * the content type the surrounding slot expects, which keeps raw fields type-safe.
     */
    private static JsonElement rewriteValue(JsonElement value, Match match, FieldDef field,
        ContentType hint) {
        if (value == null || value.isJsonNull()) {
            return value;
        }
        if (value.isJsonPrimitive()) {
            return rewritePrimitive(value.getAsJsonPrimitive(), match, field, hint);
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) {
                if (element.isJsonObject() && match.removing()
                    && referencesTarget(element.getAsJsonObject(), match, field, hint)) {
                    continue;
                }
                JsonElement rewritten = rewriteValue(element.deepCopy(), match, field, hint);
                if (rewritten != null) {
                    out.add(rewritten);
                }
            }
            return out;
        }
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (var entry : value.getAsJsonObject().entrySet()) {
                ContentType childHint = hintForKey(entry.getKey(), hint);
                if (CONTENT_KEYS.contains(entry.getKey())) {
                    JsonElement rewritten = rewriteValue(entry.getValue().deepCopy(), match, field,
                        childHint);
                    if (rewritten != null) {
                        out.add(entry.getKey(), rewritten);
                    }
                    continue;
                }
                // Structural keys such as consumes.items hold arrays of stacks. Recurse into
                // containers, but never treat an arbitrary string under an unknown key as a
                // reference: "type": "BasicBulletType" must survive untouched.
                if (entry.getValue().isJsonArray() || entry.getValue().isJsonObject()) {
                    JsonElement rewritten = rewriteValue(entry.getValue().deepCopy(), match, field,
                        childHint);
                    if (rewritten != null) {
                        out.add(entry.getKey(), rewritten);
                    }
                } else {
                    out.add(entry.getKey(), entry.getValue());
                }
            }
            return out.isEmpty() && match.removing() ? null : out;
        }
        return value;
    }

    /** The content type a field's values hold, used to keep raw/extra fields type-safe. */
    private static ContentType hintOf(FieldDef field) {
        String ctype = field.contentCtype();
        if (ctype != null && !ctype.isEmpty() && !"any".equals(ctype)) {
            for (ContentType type : ContentType.values()) {
                if (type.name().equals(ctype)) {
                    return type;
                }
            }
        }
        String stackKind = field.stackKind();
        if (stackKind != null && !stackKind.isEmpty() && !"any".equals(stackKind)) {
            for (ContentType type : ContentType.values()) {
                if (type.name().equals(stackKind)) {
                    return type;
                }
            }
        }
        return null;
    }

    /**
     * The type a structural key implies. Raw fields such as {@code consumes} and unit-factory
     * {@code requirements} are not in the schema, so the key is the only type evidence available;
     * without it a status rename could rewrite an item stack of the same name.
     */
    private static ContentType hintForKey(String key, ContentType inherited) {
        return switch (key) {
            case "item", "items", "requirements" -> ContentType.item;
            case "liquid", "liquids" -> ContentType.liquid;
            case "block" -> ContentType.block;
            case "unit" -> ContentType.unit;
            default -> inherited;
        };
    }

    private static JsonElement rewritePrimitive(JsonPrimitive value, Match match, FieldDef field,
        ContentType hint) {
        if (!value.isString()) {
            return value;
        }
        String text = value.getAsString();
        // Stack values spell the amount after a slash ("copper/10"); the amount is not a name.
        int slash = text.indexOf('/');
        String head = slash < 0 ? text : text.substring(0, slash);
        String suffix = slash < 0 ? "" : text.substring(slash);
        Target target = match.matchField(head, field, hint);
        if (target == null) {
            return value;
        }
        return match.removing()
            ? null
            : new JsonPrimitive(target.replacement(head) + suffix);
    }

    /** Whether a stack-like object carries a reference to a target under one of its named keys. */
    private static boolean referencesTarget(JsonObject object, Match match, FieldDef field,
        ContentType hint) {
        for (String key : CONTENT_KEYS) {
            JsonElement named = object.get(key);
            if (named != null && named.isJsonPrimitive()
                && named.getAsJsonPrimitive().isString()) {
                String text = named.getAsString();
                int slash = text.indexOf('/');
                String head = slash < 0 ? text : text.substring(0, slash);
                if (match.matchField(head, field, hint) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void rewriteResearch(ContentRecord record, Match match) {
        JsonElement research = record.fields.get(RESEARCH);
        if (research == null || research.isJsonNull()) {
            return;
        }
        JsonElement rewritten = rewriteResearchValue(research.deepCopy(), match);
        if (match.removing() && (rewritten == null || isEmptyResearch(rewritten))) {
            record.fields.remove(RESEARCH);
            return;
        }
        if (rewritten == null) {
            record.fields.remove(RESEARCH);
        } else if (!rewritten.equals(research)) {
            record.fields.put(RESEARCH, rewritten);
        }
    }

    private static boolean isEmptyResearch(JsonElement value) {
        if (value.isJsonObject()) {
            return value.getAsJsonObject().isEmpty();
        }
        return value.isJsonPrimitive() && value.getAsString().isBlank();
    }

    private static JsonElement rewriteResearchValue(JsonElement value, Match match) {
        if (value.isJsonPrimitive()) {
            // A bare parent name resolves vanilla-first in the tech tree, so only touch it when it
            // does not also name vanilla content.
            return rewriteResearchPrimitive(value.getAsJsonPrimitive(), match, "any", null);
        }
        if (!value.isJsonObject()) {
            return value;
        }
        JsonObject out = new JsonObject();
        for (var entry : value.getAsJsonObject().entrySet()) {
            switch (entry.getKey()) {
                case PARENT -> {
                    JsonElement parent = entry.getValue();
                    if (!parent.isJsonPrimitive() || !parent.getAsJsonPrimitive().isString()) {
                        out.add(PARENT, parent);
                        break;
                    }
                    JsonElement rewritten = rewriteResearchPrimitive(
                        parent.getAsJsonPrimitive(), match, "any", null);
                    if (rewritten != null) {
                        out.add(PARENT, rewritten);
                    }
                }
                case OBJECTIVES -> {
                    JsonArray rewritten = rewriteObjectives(entry.getValue(), match);
                    if (!rewritten.isEmpty()) {
                        out.add(OBJECTIVES, rewritten);
                    }
                }
                case REQUIREMENTS -> {
                    // Research requirements are ItemStacks ("copper/10" or {item, amount}), just
                    // like block build costs; they are not walked by the class-field loop because
                    // research is removed from the JSON before the class parser sees it.
                    JsonElement rewritten = rewriteValue(entry.getValue().deepCopy(), match, null,
                        ContentType.item);
                    if (rewritten != null && !(match.removing() && rewritten.isJsonArray()
                        && rewritten.getAsJsonArray().isEmpty())) {
                        out.add(REQUIREMENTS, rewritten);
                    }
                }
                case PLANET -> {
                    JsonElement planet = entry.getValue();
                    if (!planet.isJsonPrimitive() || !planet.getAsJsonPrimitive().isString()) {
                        out.add(PLANET, planet);
                        break;
                    }
                    JsonElement rewritten = rewriteResearchPrimitive(
                        planet.getAsJsonPrimitive(), match, "planet", ContentType.planet);
                    if (rewritten != null) {
                        out.add(PLANET, rewritten);
                    }
                }
                default -> out.add(entry.getKey(), entry.getValue());
            }
        }
        return out.isEmpty() && match.removing() ? null : out;
    }

    private static JsonElement rewriteResearchPrimitive(JsonPrimitive value, Match match,
        String ctype, ContentType requiredType) {
        if (!value.isString()) {
            return value;
        }
        String text = value.getAsString();
        int slash = text.indexOf('/');
        String head = slash < 0 ? text : text.substring(0, slash);
        String suffix = slash < 0 ? "" : text.substring(slash);
        Target target = match.matchResearch(head, ctype, requiredType);
        if (target == null) {
            return value;
        }
        return match.removing()
            ? null
            : new JsonPrimitive(target.replacement(head) + suffix);
    }

    private static JsonArray rewriteObjectives(JsonElement value, Match match) {
        JsonArray out = new JsonArray();
        if (value == null || !value.isJsonArray()) {
            return out;
        }
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                out.add(element);
                continue;
            }
            JsonObject objective = element.getAsJsonObject();
            String type = objective.has(TYPE) && objective.get(TYPE).isJsonPrimitive()
                ? objective.get(TYPE).getAsString()
                : "";
            String targetKey = objectiveTargetKey(type);
            JsonElement target = objective.get(targetKey);
            if (target != null && target.isJsonPrimitive()
                && target.getAsJsonPrimitive().isString()) {
                String text = target.getAsString();
                int slash = text.indexOf('/');
                String head = slash < 0 ? text : text.substring(0, slash);
                ContentType required = switch (type) {
                    case "SectorComplete", "OnSector" -> ContentType.sector;
                    case "OnPlanet" -> ContentType.planet;
                    default -> null;
                };
                Target matched = match.matchResearch(head, ctypeOfObjective(type), required);
                if (matched != null) {
                    if (match.removing()) {
                        // The objective has lost its target; keeping it would make the game warn
                        // about a dangling objective, so the whole entry goes.
                        continue;
                    }
                    JsonObject copy = objective.deepCopy();
                    copy.addProperty(targetKey,
                        matched.replacement(head) + (slash < 0 ? "" : text.substring(slash)));
                    out.add(copy);
                    continue;
                }
            }
            out.add(objective.deepCopy());
        }
        return out;
    }

    /** The content type an objective's target slot expects, for the vanilla-first check. */
    private static String ctypeOfObjective(String type) {
        return switch (type) {
            case "SectorComplete", "OnSector" -> "sector";
            case "OnPlanet" -> "planet";
            default -> "any";
        };
    }

    /** Mirrors {@code TechTreeRules.objectiveTargetKey} without the UI-facing helpers. */
    private static String objectiveTargetKey(String type) {
        return switch (type) {
            case "SectorComplete", "OnSector" -> PRESET;
            case "OnPlanet" -> PLANET;
            default -> CONTENT;
        };
    }

    private static boolean rewriteContentOrder(ModProject project, Match match) {
        List<String> before = List.copyOf(project.meta.contentOrder);
        List<String> rewritten = new ArrayList<>();
        for (String entry : project.meta.contentOrder) {
            Target matched = match.matchOrder(entry);
            if (matched == null) {
                rewritten.add(entry);
            } else if (!match.removing()) {
                rewritten.add(matched.replacement(entry));
            }
        }
        if (!before.equals(rewritten)) {
            project.meta.contentOrder.clear();
            project.meta.contentOrder.addAll(rewritten);
            return true;
        }
        return false;
    }

    /**
     * Whether the game resolves this reference vanilla-first, so a bare name shared with vanilla
     * keeps meaning vanilla. Same rule as {@code Validator.vanillaFirst}.
     */
    private static boolean vanillaFirst(FieldDef field) {
        return "status".equals(field.contentCtype())
            || PLANET.equals(field.name())
            || RESEARCH.equals(field.name());
    }

    /** One record being renamed (newName set) or deleted (newName null). */
    private record Target(String oldName, String fullOld, String newName, String fullNew,
        ContentType type) {

        Target(ModProject project, ContentRecord record, String oldName, String newName) {
            this(oldName,
                NameRules.fullName(project.internalName(), oldName, record.patch),
                newName,
                newName == null ? null
                    : NameRules.fullName(project.internalName(), newName, record.patch),
                record.type);
        }

        /** The spelling to write when {@code head} matched this target. */
        String replacement(String head) {
            return head.equals(fullOld) ? fullNew : newName;
        }
    }

    /** One rewrite operation, with the matching rules the game's lookup paths imply. */
    private static final class Match {
        private final SchemaRegistry registry;
        private final List<Target> targets;
        private final boolean removing;

        Match(ModProject project, SchemaRegistry registry, List<Target> targets) {
            this.registry = registry;
            this.targets = List.copyOf(targets);
            this.removing = targets.stream().allMatch(target -> target.newName() == null);
        }

        boolean removing() {
            return removing;
        }

        /** The target a name in this field refers to, or null when it refers to nothing here. */
        Target matchField(String head, FieldDef field, ContentType hint) {
            for (Target target : targets) {
                if (field != null && !accepts(field, target)) {
                    continue;
                }
                if (hint != null && target.type() != hint) {
                    continue;
                }
                boolean bare = field == null || !vanillaFirst(field)
                    || !vanillaHas(target.oldName(), field.contentCtype());
                if (head.equals(target.fullOld()) || (bare && head.equals(target.oldName()))) {
                    return target;
                }
            }
            return null;
        }

        /**
         * The item a real {@code ammoTypes} map key refers to. Keys use ContentParser.readValue's
         * prefix-first content lookup, so a bare key means the mod's item even when vanilla has
         * the same name.
         */
        Target matchAmmoKey(String head, ContentType keyType) {
            for (Target target : targets) {
                if (target.type() == keyType
                    && (head.equals(target.fullOld()) || head.equals(target.oldName()))) {
                    return target;
                }
            }
            return null;
        }

        /** The unit a factory plan or reconstructor upgrade pair names. */
        Target matchUnit(String head) {
            return matchField(head, null, ContentType.unit);
        }

        /**
         * The target a bare research parent/objective name refers to. {@code requiredType} filters
         * slot-specific references (planet and preset) so a same-named item/unit cannot rewrite a
         * planet or sector field by accident; parent/content slots pass null.
         */
        Target matchResearch(String head, String ctype, ContentType requiredType) {
            for (Target target : targets) {
                if (requiredType != null && target.type() != requiredType) {
                    continue;
                }
                boolean bare = !vanillaHas(target.oldName(), ctype);
                if (head.equals(target.fullOld()) || (bare && head.equals(target.oldName()))) {
                    return target;
                }
            }
            return null;
        }

        /** contentOrder lists this project's own content names, so the bare spelling always counts. */
        Target matchOrder(String head) {
            for (Target target : targets) {
                if (head.equals(target.fullOld()) || head.equals(target.oldName())) {
                    return target;
                }
            }
            return null;
        }

        /** Whether the field's declared content type can hold the target at all. */
        private boolean accepts(FieldDef field, Target target) {
            if (field != null && field.type() == FieldType.struct) {
                // A struct field mixes reference kinds (a UnitReq names a block and item costs);
                // its stack kind is a shape name, not a type. The object keys carry the hints.
                return true;
            }
            String ctype = field.contentCtype();
            if (ctype != null && !ctype.isEmpty() && !"any".equals(ctype)
                && !ctype.equals(target.type().name())) {
                return false;
            }
            String stackKind = field.stackKind();
            return stackKind == null || stackKind.isEmpty() || "any".equals(stackKind)
                || stackKind.equals(target.type().name());
        }

        private boolean vanillaHas(String name, String ctype) {
            if (name == null || name.isEmpty()) {
                return false;
            }
            if (ctype == null || ctype.isEmpty() || "any".equals(ctype)) {
                for (ContentType type : ContentType.values()) {
                    if (registry.isVanillaName(type, name)) {
                        return true;
                    }
                }
                return false;
            }
            for (ContentType type : ContentType.values()) {
                if (type.name().equals(ctype)) {
                    return registry.isVanillaName(type, name);
                }
            }
            return false;
        }
    }
}

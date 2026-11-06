package dev.modmaker.core.validate;

import com.google.gson.JsonElement;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;
import dev.modmaker.core.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Reference integrity: every name a record references must resolve to mod or vanilla content, and a
 * bare name that quietly binds to vanilla instead of the mod's own content is reported.
 */
final class ReferenceCheck implements ProjectCheck {

    @Override
    public String id() {
        return "references";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        SchemaRegistry registry = context.registry();
        String internal = context.internalName();
        for (ContentRecord record : project.contents) {
            if (record.isInline()) {
                continue;
            }
            String className = registry.resolveClass(record.type, record.className());
            if (className == null) {
                continue;
            }
            for (FieldDef field : registry.fieldsOf(className)) {
                if (!field.isReference()) {
                    continue;
                }
                JsonElement value = record.fields.get(field.name());
                if (value == null) {
                    continue;
                }
                for (String name : collectNames(value, field)) {
                    if (!resolves(registry, project, name, field, internal)) {
                        // The game's value parser throws "No X found with name ..." for any concrete
                        // content-typed field (ContentParser.java:505-514), so the whole file fails
                        // to load - an error, not a warning. Exception: "research" only logs "isn't
                        // in the tech tree" (ContentParser.java:1401-1405) and stays a warning.
                        if (field.name().equals("research")) {
                            context.report(Issue.warningOn(record.id, "validate.reference.unresolved",
                                record.type, record.name, name, field.name()));
                        } else {
                            context.report(Issue.errorOn(record.id, "validate.reference.unresolved",
                                record.type, record.name, name, field.name()));
                        }
                    } else if (shadowsVanilla(registry, project, name, field, internal)) {
                        context.report(Issue.warningOn(record.id,
                            "validate.reference.shadowsVanilla",
                            record.type, record.name, name, field.name(), internal, name));
                    }
                }
            }
        }
    }

    private boolean resolves(SchemaRegistry registry, ModProject project, String name, FieldDef field,
        String internal) {
        String bare = CheckSupport.stripPrefix(name, internal);
        if (matchesModContent(project, bare, field)) {
            return true;
        }
        for (ContentType type : ContentType.values()) {
            if (typeMatches(field, type) && registry.isVanillaName(type, bare)) {
                return true;
            }
        }
        // Structured groups mix reference kinds (a UnitReq names a factory block AND item costs),
        // so after the hinted type has had its try, the name may still resolve as any content.
        if (field.type() == FieldType.struct) {
            if (matchesAnyContentUnfiltered(project, bare)) {
                return true;
            }
            for (ContentType type : ContentType.values()) {
                if (registry.isVanillaName(type, bare)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Name match across every type, ignoring the field's slot hint. */
    private boolean matchesAnyContentUnfiltered(ModProject project, String name) {
        for (ContentRecord record : project.contents) {
            if (!record.isInline() && record.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a bare reference quietly binds to vanilla instead of the mod's own content of that
     * name. Only the vanilla-first lookups can surprise the author, and {@link #vanillaFirst}
     * lists which fields those are; everywhere else the game tries {@code <mod>-<name>} before the
     * bare name (ContentParser.java:507-516), so the bare name is the right spelling for the mod's
     * own content and must not be reported. Patches are exempt: a patch shares the vanilla name by
     * definition, so a bare reference to it is exactly what the author means.
     */
    private boolean shadowsVanilla(SchemaRegistry registry, ModProject project, String name,
        FieldDef field, String internal) {
        if (!vanillaFirst(field) || name.startsWith(internal + "-")
            || !matchesOwnContent(project, name, field)) {
            return false;
        }
        for (ContentType type : ContentType.values()) {
            if (typeMatches(field, type) && registry.isVanillaName(type, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the game resolves this reference vanilla-first, so that a bare name vanilla also uses
     * silently binds to the vanilla entry:
     *
     * <ul>
     *   <li>status-typed fields go through the StatusEffect class parser, which is a {@code locate()}
     *       call for string values (ContentParser.java:144-148) - the curated schema marks those with
     *       contentCtype "status";</li>
     *   <li>{@code SectorPreset.planet} is removed from the JSON and looked up with {@code locate()}
     *       (ContentParser.java:765, 784), and no other class has a field called "planet";</li>
     *   <li>{@code research} is not a field of the class at all: readFields lifts it out of the JSON
     *       (ContentParser.java:1270) and the tech tree parent is matched by a {@code find} over
     *       {@code TechTree.all} (ContentParser.java:1401). That list is in content-creation order
     *       (TechTree.java:138) and the vanilla trees are built before any mod loads
     *       (ContentLoader.java:72-79), so a bare "copper" picks the vanilla node even when the mod
     *       defines its own copper, while the prefixed "my-mod-copper" spelling reaches the mod's own
     *       node through the first branch of the same predicate - so the advice below really is the
     *       fix there.</li>
     * </ul>
     *
     * <p>Everything else is a concrete {@code Content} subclass and takes the prefix-first path. A
     * field declared exactly {@code UnlockableContent} would also be vanilla-first
     * (ContentParser.java:497-505), but the schema exposes no such field - it does not carry the
     * declared Java type, only the content type.
     */
    private static boolean vanillaFirst(FieldDef field) {
        return "status".equals(field.contentCtype())
            || "planet".equals(field.name())
            || "research".equals(field.name());
    }

    /** Whether {@code name} matches a file-backed record of this mod, honouring the field's ctype. */
    private boolean matchesModContent(ModProject project, String name, FieldDef field) {
        return matchesAnyContent(project, name, field, true);
    }

    /** The same, but only content the mod adds itself - a patch keeps the vanilla name. */
    private boolean matchesOwnContent(ModProject project, String name, FieldDef field) {
        return matchesAnyContent(project, name, field, false);
    }

    private boolean matchesAnyContent(ModProject project, String name, FieldDef field,
        boolean patchesCount) {
        for (ContentRecord record : project.contents) {
            if (record.isInline() || !record.name.equals(name)) {
                continue;
            }
            if (!patchesCount && record.patch) {
                continue;
            }
            if (typeMatches(field, record.type)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the field's declared content type admits this type; "any" or absent admits all. */
    private static boolean typeMatches(FieldDef field, ContentType type) {
        return field == null || field.contentCtype() == null || field.contentCtype().equals("any")
            || field.contentCtype().equals(type.name());
    }

    private List<String> collectNames(JsonElement value, FieldDef field) {
        List<String> out = new ArrayList<>();
        collectNames(value, field, out);
        return out;
    }

    private void collectNames(JsonElement value, FieldDef field, List<String> out) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        // A turret's ammo map: the KEYS are the item/liquid references (the values are bullets).
        if (field.type() == FieldType.struct && "ammoMap".equals(field.stackKind())
            && value.isJsonObject()) {
            value.getAsJsonObject().keySet().forEach(out::add);
            return;
        }
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(element -> collectNames(element, field, out));
            return;
        }
        if (value.isJsonObject()) {
            for (String key : new String[] {"item", "liquid", "payload", "block", "unit", "content"}) {
                JsonElement named = value.getAsJsonObject().get(key);
                if (named != null && named.isJsonPrimitive() && named.getAsJsonPrimitive().isString()) {
                    String text = named.getAsString().trim();
                    if (text.isEmpty() || text.startsWith("icon-")) {
                        continue;
                    }
                    // Consume groups and their kin spell stacks "name/amount" on named keys too.
                    if (field.type().isStack() || field.type() == FieldType.struct) {
                        int slash = text.indexOf('/');
                        if (slash > 0) {
                            text = text.substring(0, slash);
                        }
                    }
                    out.add(text);
                }
            }
            // Structured groups nest their references one level down (a UnitReq's requirements,
            // a consume group's items); recurse into containers only, so an arbitrary string
            // under an unknown key (a bullet's "type") is never taken for a reference.
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (!entry.getKey().matches("item|liquid|payload|block|unit|content")
                    && (entry.getValue().isJsonArray() || entry.getValue().isJsonObject())) {
                    collectNames(entry.getValue(), field, out);
                }
            }
            return;
        }
        if (!value.getAsJsonPrimitive().isString()) {
            return;
        }
        String text = value.getAsString().trim();
        if (text.isEmpty() || text.startsWith("icon-")) {
            return;
        }
        if (field.type().isStack() || field.type() == FieldType.struct) {
            int slash = text.indexOf('/');
            if (slash > 0) {
                text = text.substring(0, slash);
            }
        }
        out.add(text);
    }
}

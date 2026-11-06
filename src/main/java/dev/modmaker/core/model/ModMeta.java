package dev.modmaker.core.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.NameRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mod.json / mod.hjson contents, mirroring Mods.ModMeta (Mods.java:1392-1416).
 *
 * <p>Unknown keys are kept in {@link #extra} so a mod that declares fields this application does not
 * model still round-trips unchanged.
 */
public final class ModMeta {
    public String name = "";
    public String displayName = "";
    public String author = "";
    public String description = "";
    public String subtitle = "";
    public String version = "1.0";
    public String minGameVersion = "160";
    public List<String> dependencies = new ArrayList<>();
    public List<String> softDependencies = new ArrayList<>();
    public boolean hidden;
    public String repo = "";
    public double texturescale = 1;
    public List<String> contentOrder = new ArrayList<>();

    /** Class-mod fields, preserved for round-trip; class mods themselves are out of scope. */
    public String mainClass = "";
    public boolean java;
    public boolean kotlin;

    public Map<String, JsonElement> extra = new LinkedHashMap<>();

    /** The prefix of every content name, sprite region and bundle key (Mods.ModMeta.cleanup). */
    public String internalName() {
        return NameRules.internalName(name);
    }

    /**
     * Whether {@code legacyCompatible: true} is declared. It is the game's only escape from the two
     * version floors (Mods.java:1329-1331), read from {@link #extra} the way the validator does.
     */
    public boolean legacyCompatible() {
        JsonElement value = extra.get("legacyCompatible");
        if (value == null || !value.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isBoolean() ? primitive.getAsBoolean()
            : "true".equalsIgnoreCase(primitive.getAsString());
    }

    /** Sets or clears {@code legacyCompatible}; clearing removes the key so untouched mods stay verbatim. */
    public void setLegacyCompatible(boolean value) {
        if (value) {
            extra.put("legacyCompatible", new JsonPrimitive(true));
        } else {
            extra.remove("legacyCompatible");
        }
    }

    /** The meta file name, honouring the order the game probes (Mods.java:34). */
    public static final String[] META_FILES = {"mod.json", "mod.hjson", "plugin.json", "plugin.hjson"};

    /** A detached copy for read-only work off the model's thread. */
    public ModMeta snapshot() {
        ModMeta copy = new ModMeta();
        copy.name = name;
        copy.displayName = displayName;
        copy.author = author;
        copy.description = description;
        copy.subtitle = subtitle;
        copy.version = version;
        copy.minGameVersion = minGameVersion;
        copy.dependencies = new ArrayList<>(dependencies);
        copy.softDependencies = new ArrayList<>(softDependencies);
        copy.hidden = hidden;
        copy.repo = repo;
        copy.texturescale = texturescale;
        copy.contentOrder = new ArrayList<>(contentOrder);
        copy.mainClass = mainClass;
        copy.java = java;
        copy.kotlin = kotlin;
        copy.extra = new LinkedHashMap<>();
        extra.forEach((key, value) -> copy.extra.put(key, value == null ? null : value.deepCopy()));
        return copy;
    }
}

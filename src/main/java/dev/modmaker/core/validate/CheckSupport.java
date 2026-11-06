package dev.modmaker.core.validate;

import com.google.gson.JsonElement;
import dev.modmaker.core.schema.ClassSchema;
import dev.modmaker.core.schema.SchemaRegistry;

import java.util.HashSet;
import java.util.Set;

/**
 * The small helpers several checks share. Private to the validation package: the individual checks
 * are the public seam, not these utilities.
 */
final class CheckSupport {

    /** Environment block roots whose sprites must live in sprites/blocks/environment/. */
    static final String[] ENVIRONMENT_ROOTS =
        {"Floor", "OreBlock", "StaticWall", "TallBlock", "Cliff", "SeaBush", "Prop"};

    private CheckSupport() {
    }

    /** True when the class or any of its schema ancestors is {@code ancestor} (simple names). */
    static boolean isA(SchemaRegistry registry, String className, String ancestor) {
        String current = simpleName(className);
        Set<String> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            if (current.equals(ancestor)) {
                return true;
            }
            ClassSchema schema = registry.schema(current);
            current = schema == null ? null : simpleName(schema.trueParent());
        }
        return false;
    }

    /** Floors, walls and props whose sprites must live under sprites/blocks/environment/. */
    static boolean isEnvironmentClass(SchemaRegistry registry, String className) {
        for (String root : ENVIRONMENT_ROOTS) {
            if (isA(registry, className, root)) {
                return true;
            }
        }
        return false;
    }

    static String simpleName(String className) {
        return className == null ? null : className.substring(className.lastIndexOf('.') + 1);
    }

    /** Drops the mod's {@code <internal>-} prefix, so bare references compare like the game does. */
    static String stripPrefix(String name, String internal) {
        String prefix = internal + "-";
        return !internal.isEmpty() && name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    /** The value as a number, or null when absent or not numeric. */
    static Double number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsDouble();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Compact number rendering for messages: 150.0 becomes 150, 0.2 stays 0.2. */
    static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}

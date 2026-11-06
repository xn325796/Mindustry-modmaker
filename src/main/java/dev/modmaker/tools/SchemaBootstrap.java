package dev.modmaker.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.io.SearchPaths;
import dev.modmaker.core.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans a Mindustry source tree and writes the schema drafts the editor works from.
 *
 * <p>Outputs, both under schemas/:
 * <ul>
 *   <li>fields.json - every content-relevant class with its parent, content type and public
 *       instance fields, typed the same way the inspector needs them.</li>
 *   <li>vanilla-content.json - names of vanilla content per type, used to recognise patch files on
 *       import and to check references.</li>
 * </ul>
 *
 * <p>This is a regex draft: the curated schemas/schema.json always wins over it. The tree is found
 * rather than assumed ({@link #sourceRoot}), and the paths written into the output stay relative,
 * so a regenerated file carries no trace of the machine that produced it.
 */
public final class SchemaBootstrap {

    /** Levels below the working directory's parent searched for a Mindustry tree. */
    private static final int SOURCE_SCAN_DEPTH = 3;

    /** The Mindustry tree this tool scans by name; kept in sync with ModProject.SCHEMA_PROFILE. */
    private static final String SOURCE_TREE = "Mindustry-160.7";

    /** Classes that root each content type; a subclass inherits the type transitively. */
    private static final Map<String, ContentType> ROOTS = Map.ofEntries(
        Map.entry("Item", ContentType.item),
        Map.entry("Liquid", ContentType.liquid),
        Map.entry("StatusEffect", ContentType.status),
        Map.entry("Block", ContentType.block),
        Map.entry("UnitType", ContentType.unit),
        Map.entry("BulletType", ContentType.bullet),
        Map.entry("Weather", ContentType.weather),
        Map.entry("SectorPreset", ContentType.sector),
        Map.entry("Planet", ContentType.planet),
        Map.entry("TeamEntry", ContentType.team),
        Map.entry("UnitCommand", ContentType.unitCommand),
        Map.entry("UnitStance", ContentType.unitStance));

    private static final Map<String, String> PRIMITIVES = Map.of(
        "boolean", "bool",
        "int", "number",
        "float", "number",
        "double", "number",
        "long", "number",
        "short", "number",
        "byte", "number",
        "String", "text",
        "Color", "color");

    private static final Pattern CLASS_DECLARATION = Pattern.compile(
        "\\bpublic\\s+(?:abstract\\s+|final\\s+)*class\\s+(\\w+)(?:<[^>]*>)?(?:\\s+extends\\s+([\\w.]+))?");
    private static final Pattern FIELD_HEAD = Pattern.compile(
        "^public\\s+([\\w<>\\[\\],. ?]+?)\\s+(\\w+)\\s*(.*)$");
    private static final Pattern ANNOTATION = Pattern.compile("@[\\w.]+(?:\\([^)]*\\))?");
    private static final Pattern CONTENT_CREATION = Pattern.compile(
        "new\\s+([A-Za-z_$][\\w.$]*)\\s*\\(\\s*\"([^\"]+)\"");
    /**
     * Ores are the one vanilla content whose name is derived rather than written out:
     * OreBlock(Item) delegates to {@code "ore-" + item.name} (OreBlock.java:26-28).
     */
    private static final Pattern ORE_CREATION = Pattern.compile(
        "new\\s+OreBlock\\s*\\(\\s*(?:[A-Za-z_$][\\w.$]*\\.)?(\\w+)\\s*\\)");
    /** Teams carry an id argument, so the plain creation pattern above misses them. */
    private static final Pattern TEAM_CREATION = Pattern.compile(
        "new\\s+Team\\s*\\(\\s*\\d+\\s*,\\s*\"([a-z0-9-]+)\"");

    /** Anchor of the switch that maps a unit's "type" to an entity class (ContentParser.unitType). */
    private static final String UNIT_TYPE_ANCHOR = "unitType(JsonValue";
    private static final Pattern UNIT_TYPE_CASE = Pattern.compile("case\\s+\"([a-z-]+)\"\\s*->");

    /** An enum the game's JSON reads by constant name (arc Json: valueOf on the declared name). */
    private static final Pattern ENUM_DECLARATION = Pattern.compile(
        "\\bpublic\\s+enum\\s+(\\w+)(?:\\s+implements\\s+[\\w.,\\s]+)?\\s*\\{([^;}]*)");
    /**
     * Value-set classes that are not enums: instances registered through a static {@code add("name")
     * } factory, like {@code Attribute.heat = add("heat")} (Attribute.java:13-27). The JSON value is
     * the registered name, exactly like an enum constant.
     */
    private static final Pattern ADD_VALUE = Pattern.compile("(\\w+)\\s*=\\s*add\\(\"([\\w-]+)\"\\)");

    private SchemaBootstrap() {
    }

    public static void main(String[] args) throws IOException {
        Path sourceRoot = sourceRoot(args);
        Path schemasDir = Path.of("schemas").toAbsolutePath().normalize();

        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            System.err.println("source root not found: " + sourceRoot);
            System.err.println("pass it as an argument, set -Dmodmaker.mindustrySrc=<dir>"
                + " (or MINDUSTRY_SRC), or keep the tree next to this project as"
                + " " + SOURCE_TREE + "/core/src/mindustry.");
            System.exit(1);
        }

        List<Path> javaFiles = walkJava(sourceRoot);
        System.out.println("scanning " + javaFiles.size() + " java files in " + sourceRoot);

        Map<String, ClassInfo> classes = scanClasses(javaFiles);
        resolveTypes(classes);

        Map<String, List<String>> valueSets = scanValueSets(javaFiles);
        Map<ContentType, Set<String>> vanilla = scanVanilla(javaFiles, classes);
        List<String> unitTypes = scanUnitTypes(sourceRoot);

        writeFields(schemasDir.resolve("fields.json"), sourceRoot, classes, unitTypes, valueSets);
        writeVanilla(schemasDir.resolve("vanilla-content.json"), sourceRoot, vanilla);

        Map<ContentType, Integer> perType = new TreeMap<>();
        int kept = 0;
        for (ClassInfo info : classes.values()) {
            if (info.ctype != null) {
                kept++;
                perType.merge(info.ctype, 1, Integer::sum);
            }
        }
        System.out.println("content classes=" + kept + " " + perType);
        System.out.println("unit types=" + unitTypes);
        for (Map.Entry<ContentType, Set<String>> entry : vanilla.entrySet()) {
            System.out.println("vanilla " + entry.getKey() + "=" + entry.getValue().size());
        }
    }

    // --- locating the sources -------------------------------------------------------------------

    /**
     * The Mindustry source tree to scan. An explicit argument wins, then {@code
     * -Dmodmaker.mindustrySrc} / {@code MINDUSTRY_SRC}, then the spots the workspace layout usually
     * provides, and finally a shallow scan for any folder holding a {@code core/src/mindustry}
     * tree - so the tool runs from any checkout instead of one hard-coded absolute path.
     */
    private static Path sourceRoot(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0]).toAbsolutePath().normalize();
        }
        Path setting = SearchPaths.override("modmaker.mindustrySrc", "MINDUSTRY_SRC");
        if (setting != null) {
            return setting;
        }
        Path working = SearchPaths.workingDirectory();
        Path named = SearchPaths.firstDirectory(List.of(
            working.resolve("../" + SOURCE_TREE + "/core/src/mindustry"),
            working.resolve("../Mindustry/" + SOURCE_TREE + "/core/src/mindustry"),
            working.resolve("../mindustry/" + SOURCE_TREE + "/core/src/mindustry"),
            working.resolve(SOURCE_TREE + "/core/src/mindustry")));
        if (named != null) {
            return named;
        }
        return SearchPaths.find(working.getParent(), SOURCE_SCAN_DEPTH,
            path -> Files.isDirectory(path.resolve("core/src/mindustry")));
    }

    /**
     * Unit "type" values the game accepts. Units are the one content type whose "type" is not a
     * class at all: ContentParser.unitType is a plain switch over a movement kind and throws on
     * anything else, so a JSON mod names "flying", "tank", "legs" and the like there.
     */
    private static List<String> scanUnitTypes(Path sourceRoot) throws IOException {
        Path parser = sourceRoot.resolve("mod/ContentParser.java");
        if (!Files.isRegularFile(parser)) {
            return List.of();
        }
        String source = Files.readString(parser, StandardCharsets.UTF_8);
        int start = source.indexOf(UNIT_TYPE_ANCHOR);
        if (start < 0) {
            return List.of();
        }
        int end = source.indexOf("};", start);
        String body = end < 0 ? source.substring(start) : source.substring(start, end);
        List<String> values = new ArrayList<>();
        Matcher matcher = UNIT_TYPE_CASE.matcher(body);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    /** A path relative to {@code base}, so the generated data carries no machine path. */
    private static String relativePath(Path base, Path target) {
        Path from = base.toAbsolutePath().normalize();
        Path to = target.toAbsolutePath().normalize();
        try {
            return from.relativize(to).toString().replace('\\', '/');
        } catch (IllegalArgumentException differentRoot) {
            return to.toString().replace('\\', '/');
        }
    }

    // --- classes --------------------------------------------------------------------------------

    private static final class Field {
        final String name;
        final String javaType;

        Field(String name, String javaType) {
            this.name = name;
            this.javaType = javaType;
        }
    }

    private static final class ClassInfo {
        final String name;
        final String file;
        final String parent;
        final List<Field> fields;
        ContentType ctype;

        ClassInfo(String name, String file, String parent, List<Field> fields) {
            this.name = name;
            this.file = file;
            this.parent = parent;
            this.fields = fields;
        }
    }

    private static List<Path> walkJava(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static Map<String, ClassInfo> scanClasses(List<Path> files) throws IOException {
        Map<String, ClassInfo> classes = new LinkedHashMap<>();
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher declaration = CLASS_DECLARATION.matcher(source);
            if (!declaration.find()) {
                continue;
            }
            String name = declaration.group(1);
            String parent = declaration.group(2) == null
                ? null
                : declaration.group(2).substring(declaration.group(2).lastIndexOf('.') + 1);
            ClassInfo info = new ClassInfo(name, file.toString().replace('\\', '/'), parent,
                parseFields(source));
            info.ctype = ROOTS.get(name);
            classes.put(name, info);
        }
        return classes;
    }

    /** Fields the current model can express: public, non-static, non-final, no methods. One
     * declaration may define several fields ("float a = 1, b = 2;") - each becomes a field. */
    private static List<Field> parseFields(String source) {
        List<Field> fields = new ArrayList<>();
        for (String rawLine : source.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("public ") || line.indexOf('(') >= 0
                || line.contains(" static ") || line.contains(" final ")) {
                continue;
            }
            String cleaned = ANNOTATION.matcher(line).replaceAll(" ").trim();
            if (cleaned.contains(" transient ")) {
                continue;
            }
            cleaned = cleaned.replaceAll("//.*$", "").trim();
            if (!cleaned.endsWith(";")) {
                continue;
            }
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
            Matcher head = FIELD_HEAD.matcher(cleaned);
            if (!head.matches()) {
                continue;
            }
            String javaType = head.group(1).trim();
            if (!javaType.matches("[\\w<>\\[\\],. ?]+") || javaType.contains("=")) {
                continue;
            }
            for (String part : (head.group(2) + head.group(3)).split(",")) {
                String declarator = part.trim();
                int equals = declarator.indexOf('=');
                String name = (equals >= 0 ? declarator.substring(0, equals) : declarator).trim();
                // a quoted string containing a comma can masquerade as a declarator
                if (name.matches("[A-Za-z_$][\\w$]*")) {
                    fields.add(new Field(name, javaType));
                }
            }
        }
        return fields;
    }

    /** Propagates the content type from a root class down through the extends chain. */
    private static void resolveTypes(Map<String, ClassInfo> classes) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ClassInfo info : classes.values()) {
                if (info.ctype != null || info.parent == null) {
                    continue;
                }
                ClassInfo parent = classes.get(info.parent);
                if (parent != null && parent.ctype != null) {
                    info.ctype = parent.ctype;
                    changed = true;
                }
            }
        }
    }

    private static void writeFields(Path target, Path sourceRoot, Map<String, ClassInfo> classes,
        List<String> unitTypes, Map<String, List<String>> valueSets) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("generated", java.time.Instant.now().toString());
        root.addProperty("source", relativePath(SearchPaths.workingDirectory(), sourceRoot));
        root.addProperty("note", "Draft from source scan. schemas/schema.json overrides these entries.");
        JsonArray unitTypesJson = new JsonArray();
        unitTypes.forEach(unitTypesJson::add);
        root.add("unitTypes", unitTypesJson);

        JsonObject out = new JsonObject();
        for (ClassInfo info : classes.values()) {
            if (info.ctype == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            if (info.parent != null) {
                entry.addProperty("extends", info.parent);
            }
            entry.addProperty("ctype", info.ctype.name());
            entry.addProperty("file", relativePath(sourceRoot, Path.of(info.file)));
            JsonArray fields = new JsonArray();
            for (Field field : info.fields) {
                JsonObject fieldJson = new JsonObject();
                fieldJson.addProperty("n", field.name);
                fieldJson.addProperty("t", draftType(field.javaType, classes, valueSets));
                List<String> values = valueSets.get(field.javaType.replace("[]", "").trim());
                if (values != null && values.size() > 1) {
                    JsonArray allowed = new JsonArray();
                    values.forEach(allowed::add);
                    fieldJson.add("v", allowed);
                }
                fields.add(fieldJson);
            }
            entry.add("fields", fields);
            out.add(info.name, entry);
        }
        root.add("classes", out);
        Json.writeFile(target, Json.write(root));
    }

    private static String draftType(String javaType, Map<String, ClassInfo> classes,
        Map<String, List<String>> valueSets) {
        String base = javaType.replace("[]", "").trim();
        if (javaType.endsWith("[]")) {
            return switch (base) {
                case "ItemStack" -> "stack[]:item";
                case "LiquidStack" -> "stack[]:liquid";
                case "PayloadStack" -> "stack[]:payload";
                case "String" -> "text[]";
                default -> "raw";
            };
        }
        String primitive = PRIMITIVES.get(javaType);
        if (primitive != null) {
            return primitive;
        }
        if (ROOTS.containsKey(javaType)) {
            return "content";
        }
        // Enum fields (and Attribute-style value sets) are one of a closed list the game matches by
        // constant name, so the inspector renders them as a combo box.
        if (valueSets.containsKey(base)) {
            return "enum";
        }
        // Sound resolves from a plain asset name string (ContentParser.java:310-319), so a sound
        // field is text, not free-form JSON.
        if (base.equals("Sound")) {
            return "text";
        }
        ClassInfo info = classes.get(base);
        return info != null && info.ctype != null ? "content" : "raw";
    }

    /**
     * Closed value sets a JSON field may name: enums declared in the tree, plus Attribute-style
     * classes whose instances are registered through {@code add("name")}. Enums whose constants
     * carry anonymous-class bodies (BuildVisibility) are skipped - the regex draft cannot read them
     * reliably, and a partial value list would be worse than none.
     */
    private static Map<String, List<String>> scanValueSets(List<Path> files) throws IOException {
        Map<String, List<String>> sets = new LinkedHashMap<>();
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher declaration = ENUM_DECLARATION.matcher(source);
            while (declaration.find()) {
                String body = declaration.group(2);
                if (body.contains("{")) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (String part : body.split(",")) {
                    String constant = part.trim();
                    int paren = constant.indexOf('(');
                    if (paren >= 0) {
                        constant = constant.substring(0, paren).trim();
                    }
                    String[] tokens = constant.split("\\s+");
                    String name = tokens[tokens.length - 1].trim();
                    if (name.matches("[A-Za-z_$][\\w$]*")) {
                        values.add(name);
                    }
                }
                if (values.size() > 1) {
                    sets.putIfAbsent(declaration.group(1), values);
                }
            }
            Matcher classDeclaration = CLASS_DECLARATION.matcher(source);
            if (classDeclaration.find()) {
                List<String> values = new ArrayList<>();
                Matcher adds = ADD_VALUE.matcher(source);
                while (adds.find()) {
                    values.add(adds.group(2));
                }
                if (values.size() > 1) {
                    sets.putIfAbsent(classDeclaration.group(1), values);
                }
            }
        }
        return sets;
    }

    // --- vanilla content names ------------------------------------------------------------------

    private static Map<ContentType, Set<String>> scanVanilla(
        List<Path> files, Map<String, ClassInfo> classes) throws IOException {
        Map<ContentType, Set<String>> names = new LinkedHashMap<>();
        for (Path file : files) {
            String path = file.toString().replace('\\', '/');
            if (!path.contains("/content/")) {
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = CONTENT_CREATION.matcher(source);
            while (matcher.find()) {
                String created = matcher.group(1);
                String simple = created.substring(created.lastIndexOf('.') + 1);
                ContentType type = ROOTS.get(simple);
                if (type == null) {
                    ClassInfo info = classes.get(simple);
                    type = info == null ? null : info.ctype;
                }
                if (type != null) {
                    names.computeIfAbsent(type, ignored -> new LinkedHashSet<>())
                        .add(matcher.group(2));
                }
            }
            // Derived ore names, e.g. new OreBlock(Items.titanium) becomes "ore-titanium".
            Matcher ores = ORE_CREATION.matcher(source);
            while (ores.find()) {
                names.computeIfAbsent(ContentType.block, ignored -> new LinkedHashSet<>())
                    .add("ore-" + ores.group(1).toLowerCase(Locale.ROOT));
            }
        }
        // Teams live in game/Team.java, not under content/ - same walk, different file. They are
        // declared with an id first (new Team(0, "derelict")), which the creation pattern misses.
        for (Path file : files) {
            if (!file.toString().replace('\\', '/').endsWith("/game/Team.java")) {
                continue;
            }
            Matcher teams = TEAM_CREATION.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (teams.find()) {
                names.computeIfAbsent(ContentType.team, ignored -> new LinkedHashSet<>())
                    .add(teams.group(1));
            }
        }
        return names;
    }

    private static void writeVanilla(Path target, Path sourceRoot, Map<ContentType, Set<String>> vanilla)
        throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("generated", java.time.Instant.now().toString());
        root.addProperty("source", relativePath(SearchPaths.workingDirectory(), sourceRoot));
        root.addProperty("note",
            "Vanilla content names, used to recognise patch files and to check references. "
                + "Names built at runtime from variables are not listed.");
        JsonObject types = new JsonObject();
        for (ContentType type : ContentType.values()) {
            JsonArray names = new JsonArray();
            for (String name : vanilla.getOrDefault(type, Set.of()).stream().sorted().toList()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
            if (!names.isEmpty()) {
                types.add(type.name(), names);
            }
        }
        root.add("content", types);
        Json.writeFile(target, Json.write(root));
    }
}

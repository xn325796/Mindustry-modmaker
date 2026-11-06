package dev.modmaker.core.fmt;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Naming rules shared by the importer and the exporter.
 *
 * <p>The mod's internal name is the single source of every prefix in a mod package: content names,
 * sprite regions and bundle keys are all derived from it. Mods.ModMeta.cleanup (Mods.java:1428-1435)
 * strips colour tags from {@code name} first and only then computes
 * {@code name.toLowerCase(Locale.ROOT).replace(" ", "-")}, so {@code "[red]Foo Mod"} yields
 * {@code foo-mod} rather than {@code [red]foo-mod}.
 */
public final class NameRules {

    /**
     * Colour tags the game removes, i.e. arc's {@code Colors} table (arc.graphics.Colors). It
     * registers every colour twice - the CONSTANT_CASE field name and the same name lower cased
     * with the underscores dropped - which is why {@code [red]} and {@code [RED]} are colour tags
     * while {@code [Red]} is not.
     */
    private static final String[] COLOR_TAGS = {
        "ACID", "BLACK", "BLUE", "BRICK", "BROWN", "CLEAR", "CORAL", "CRIMSON", "CYAN",
        "DARK_GRAY", "DARK_GREY", "FOREST", "GOLD", "GOLDENROD", "GRAY", "GREEN", "GREY",
        "LIGHT_GRAY", "LIGHT_GREY", "LIME", "MAGENTA", "MAROON", "NAVY", "OLIVE", "ORANGE",
        "PINK", "PURPLE", "RED", "ROYAL", "SALMON", "SCARLET", "SKY", "SLATE", "TAN", "TEAL",
        "VIOLET", "WHITE", "YELLOW"
    };

    private static final Set<String> COLOR_NAMES = colorNames();

    /** Upper bound that keeps generated file/folder names inside every supported filesystem. */
    public static final int MAX_NAME_LENGTH = 100;

    /** Windows device names; a file/folder with one of these (even with an extension) is invalid. */
    private static final Set<String> WINDOWS_RESERVED = Set.of(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /** The base part of a file name before its first dot. */
    public static String fileNameBase(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** True for Windows device names such as CON, NUL, COM1, which no file may use. */
    public static boolean isWindowsReservedName(String name) {
        return WINDOWS_RESERVED.contains(fileNameBase(name).toLowerCase(Locale.ROOT));
    }

    private NameRules() {
    }

    private static Set<String> colorNames() {
        Set<String> names = new HashSet<>(COLOR_TAGS.length * 2);
        for (String tag : COLOR_TAGS) {
            names.add(tag);
            names.add(tag.replace("_", "").toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * Mods.ModMeta.cleanup: colour tags are gone before case folding, then lowercase, spaces become
     * dashes. Skipping the strip step made every prefix derived from a name with a colour tag wrong
     * (sprite regions, bundle keys, patch detection and reference resolution all lost the match).
     */
    public static String internalName(String displayName) {
        if (displayName == null) {
            return "";
        }
        return stripColors(displayName).toLowerCase(Locale.ROOT).replace(" ", "-");
    }

    /**
     * The bracket groups arc's {@code Strings.stripColors} removes: a group whose content is empty,
     * starts with {@code '#'} (a literal hex colour) or names a registered colour. An unclosed
     * bracket is ordinary text, and colours are looked up case sensitively against the two
     * spellings arc registers.
     */
    static String stripColors(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '[') {
                int end = text.indexOf(']', index + 1);
                if (end != -1 && isColorTag(text.substring(index + 1, end))) {
                    index = end + 1;
                    continue;
                }
            }
            out.append(c);
            index++;
        }
        return out.toString();
    }

    private static boolean isColorTag(String name) {
        return name.isEmpty() || COLOR_NAMES.contains(name) || isHexColor(name);
    }

    /**
     * A literal colour: '#' followed by one to eight hex digits. Verified against arc - "#abc",
     * "#ff0000" and "#00000000" are tags, while "#", "#abcdefg" (nine characters, non-hex) and
     * "#100000000" (nine digits) are ordinary text.
     */
    private static boolean isHexColor(String name) {
        if (name.length() < 2 || name.length() > 9 || name.charAt(0) != '#') {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (Character.digit(name.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when an internal name is safe to use as a single folder name. The game itself never
     * strips path separators, so a mod.json declaring {@code "name": "..\\..\\pwned"} would make
     * tools that build {@code destination/<internalName>} write outside their destination - the
     * importer and exporter refuse such names instead of trusting them.
     */
    public static boolean isFolderSafe(String internalName) {
        if (internalName == null || internalName.isBlank()) {
            return false;
        }
        if (internalName.length() > MAX_NAME_LENGTH || isWindowsReservedName(internalName)) {
            return false;
        }
        return !(internalName.contains("/") || internalName.contains("\\")
            || internalName.contains("..") || internalName.indexOf(':') >= 0);
    }

    /**
     * The name the game ends up registering a content file under. New content becomes
     * {@code <mod>-<fileName>} (ContentParser: {@code make(..., mod + "-" + name)}), but a patch
     * keeps the name it patches: the parser hands the existing content back, and bundle keys are
     * built from that content's own name (ContentParser.readBundle: {@code type + "." + cont.name}).
     */
    public static String fullName(String internalModName, String contentName, boolean patch) {
        return patch ? contentName : internalModName + "-" + contentName;
    }

    /** {@link #fullName(String, String, boolean)} for content the mod adds itself. */
    public static String fullName(String internalModName, String contentName) {
        return fullName(internalModName, contentName, false);
    }

    /**
     * A content name is a file name, and it also becomes a bundle key fragment and a sprite region
     * name. The game imposes no charset - mods in the wild ship Chinese content names - so only
     * characters that break one of those three uses are rejected.
     */
    public static boolean isValidContentName(String name) {
        return contentNameProblem(name) == null;
    }

    /** Why a content name is unusable, or null when it is fine. */
    public static String contentNameProblem(String name) {
        if (name == null || name.isEmpty()) {
            return "name is empty";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c)) {
                return "name contains whitespace, which breaks the file name and sprite region";
            }
            if (c < 0x20) {
                return "name contains a control character";
            }
            if ("\\/:*?\"<>|".indexOf(c) >= 0) {
                return "name contains '" + c + "', which cannot appear in a file name";
            }
        }
        if (name.startsWith(".") || name.endsWith(".")) {
            return "name must not start or end with '.'";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "name is longer than " + MAX_NAME_LENGTH + " characters";
        }
        if (isWindowsReservedName(name)) {
            return "name is a reserved Windows device name";
        }
        return null;
    }

    /** Bundles are Java .properties; keys in the wild only use these characters. */
    public static String bundleKey(ContentType type, String fullName, String field) {
        return type.name() + "." + fullName + "." + field;
    }

    /** UnlockableContent.java:90 — the four bundle fields every content type supports. */
    public static final String[] BUNDLE_FIELDS = {"name", "description", "details", "credit"};

    /**
     * Atlas region a sprite ends up as.
     *
     * <p>Mods.packSprites (Mods.java:404-412): a file in sprites/ is prefixed with the mod name
     * unless the part after its first hyphen already starts with "&lt;mod&gt;-" (that is how
     * category-prefixed names like {@code block-mod-block-full} stay untouched); files in
     * sprites-override/ are never prefixed. The full base name is used, dots included - only the
     * "does this region exist" warning strips at the first dot.
     */
    public static String spriteRegion(String internalModName, String fileNameWithoutExtension, boolean prefixedFolder) {
        if (fileNameWithoutExtension == null || fileNameWithoutExtension.isEmpty()) {
            return "";
        }
        int hyphen = fileNameWithoutExtension.indexOf('-');
        boolean alreadyPrefixed = hyphen != -1
            && fileNameWithoutExtension.substring(hyphen + 1).startsWith(internalModName + "-");
        return (prefixedFolder && !alreadyPrefixed ? internalModName + "-" : "") + fileNameWithoutExtension;
    }

    /** The region name used only for the "overrides a non-existent sprite" check (Mods.java:393). */
    public static String regionLookupName(String fileNameWithoutExtension) {
        int dot = fileNameWithoutExtension.indexOf('.');
        return dot < 0 ? fileNameWithoutExtension : fileNameWithoutExtension.substring(0, dot);
    }
}

package dev.modmaker.ui;

import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.model.AssetRef;
import dev.modmaker.core.model.ModProject;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Atlas region lookup for the inspector preview. It applies the same naming rules as
 * {@code Mods.packSprites} (see {@link NameRules#spriteRegion}) so a sprite file's region matches
 * what the game will register, then loads the PNG next to the project. Vanilla regions are not
 * needed: the preview only ever shows records that belong to this mod.
 */
final class SpriteStore {

    private final ProjectController controller;

    private ModProject project;
    private Map<String, Path> regions = Map.of();
    private final Map<String, Image> images = new HashMap<>();
    private final Map<String, Image> tints = new HashMap<>();

    SpriteStore(ProjectController controller) {
        this.controller = controller;
    }

    /**
     * Rebuilds the region paths on the next lookup. The decoded image caches are kept: an edit does
     * not change sprite bytes, and rebuilding the 1500-entry region map is cheap.
     */
    void invalidate() {
        project = null;
        regions = Map.of();
    }

    /** The first existing candidate, in priority order. */
    String any(String... candidates) {
        ensure();
        for (String candidate : candidates) {
            if (candidate != null && regions.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Resolves a block/unit body region: the name itself, a numbered floor variant
     * ({@code <name>1}..{@code <name>3}, Floor.java:150), or a conveyor's framed sprite
     * ({@code <name>-<frame>-<direction>}). Dash suffixes such as {@code -team} and {@code -top} are
     * deliberately excluded so they are never mistaken for the body. The choice is deterministic:
     * {@code -0-0} first, then the lowest numbered variant, so two runs preview the same frame.
     */
    String body(String base) {
        ensure();
        if (base == null) {
            return null;
        }
        if (regions.containsKey(base)) {
            return base;
        }
        String framed = base + "-0-0";
        if (regions.containsKey(framed)) {
            return framed;
        }
        String numbered = null;
        String frame = null;
        for (String region : regions.keySet()) {
            if (region.length() <= base.length() || !region.startsWith(base)) {
                continue;
            }
            String tail = region.substring(base.length());
            String digits = tail.startsWith("-") ? tail.substring(1) : tail;
            if (!digits.isEmpty() && digits.chars().allMatch(Character::isDigit)) {
                numbered = lower(numbered, region);
            } else if (tail.startsWith("-") && isFrameAndDirection(tail.substring(1))) {
                frame = lower(frame, region);
            }
        }
        return numbered != null ? numbered : frame;
    }

    /** {@code <frame>-<direction>}, e.g. the {@code 0-0} in {@code conveyor-0-0}. */
    private static boolean isFrameAndDirection(String text) {
        int dash = text.indexOf('-');
        if (dash <= 0 || dash == text.length() - 1 || text.indexOf('-', dash + 1) >= 0) {
            return false;
        }
        return text.substring(0, dash).chars().allMatch(Character::isDigit)
            && text.substring(dash + 1).chars().allMatch(Character::isDigit);
    }

    private static String lower(String current, String candidate) {
        return current == null || candidate.compareTo(current) < 0 ? candidate : current;
    }

    Image image(String region) {
        ensure();
        if (region == null || !regions.containsKey(region)) {
            return null;
        }
        Image cached = images.get(region);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = Files.newInputStream(regions.get(region))) {
            // Pixel art: never let the decoder/loader pre-smooth the texture.
            Image image = new Image(in, 0, 0, true, false);
            // A broken PNG never throws here: the loader only sets the error flag and reports a
            // 0x0 size. Do not cache that image - the preview falls back to the colour card, and a
            // fixed file must be picked up on the next lookup.
            if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return null;
            }
            images.put(region, image);
            return image;
        } catch (Exception failure) {
            return null;
        }
    }

    /**
     * A team mask multiplied by the team colour, matching {@code Draw.color(team.color);
     * Draw.rect(teamRegion)} (BuildingComp.java:1306-1311): the texture is white/grey, the result is
     * the texture channels times the colour channels.
     */
    Image tinted(String region, Color color) {
        String key = region + "|" + color;
        Image cached = tints.get(key);
        if (cached != null) {
            return cached;
        }
        Image source = image(region);
        if (source == null) {
            return null;
        }
        int width = (int) Math.round(source.getWidth());
        int height = (int) Math.round(source.getHeight());
        if (width <= 0 || height <= 0) {
            return null;
        }
        WritableImage out = new WritableImage(width, height);
        PixelReader reader = source.getPixelReader();
        PixelWriter writer = out.getPixelWriter();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = reader.getArgb(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (int) Math.round(((argb >>> 16) & 0xFF) * color.getRed());
                int green = (int) Math.round(((argb >>> 8) & 0xFF) * color.getGreen());
                int blue = (int) Math.round((argb & 0xFF) * color.getBlue());
                writer.setArgb(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        tints.put(key, out);
        return out;
    }

    private void ensure() {
        ModProject current = controller.project();
        if (current == project) {
            return;
        }
        project = current;
        regions = new LinkedHashMap<>();
        images.clear();
        tints.clear();
        if (current == null || current.root == null) {
            return;
        }
        String internal = current.internalName();
        for (AssetRef asset : current.assets) {
            boolean override = asset.path.startsWith("sprites-override/");
            if (!override && !asset.path.startsWith("sprites/")) {
                continue;
            }
            int slash = asset.path.lastIndexOf('/');
            String fileName = slash < 0 ? asset.path : asset.path.substring(slash + 1);
            int dot = fileName.lastIndexOf('.');
            if (dot <= 0 || !fileName.substring(dot + 1).equals("png")) {
                continue;
            }
            String region = NameRules.spriteRegion(internal, fileName.substring(0, dot), !override);
            if (!region.isEmpty()) {
                regions.putIfAbsent(region, current.root.resolve(asset.path));
            }
        }
    }
}

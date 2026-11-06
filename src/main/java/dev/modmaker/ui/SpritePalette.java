package dev.modmaker.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lospec-style palette extraction for the preview strip (report P2-11): the colours a sprite
 * actually uses, ranked by pixel frequency. The ranking is pure JVM so it can be unit-tested
 * without the toolkit; {@link #pixels} is the only JavaFX-bound half (the pixel reader needs a
 * decoded image and a started toolkit).
 */
final class SpritePalette {

    /** Swatches shown: twelve 24px blocks plus gaps fit the 396px context panel in one row. */
    static final int LIMIT = 12;

    private SpritePalette() {
    }

    /**
     * The {@code limit} most-used colours of an ARGB pixel array as web hex strings, most-used
     * first. Fully transparent pixels are skipped - they are not palette entries - while
     * semi-transparent ones count toward their RGB colour, so anti-aliased edge variants never
     * split one palette colour in two. Ties keep first-seen order, which makes the strip stable
     * across refreshes of the same sprite.
     */
    static List<String> topColors(int[] argb, int limit) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int pixel : argb) {
            if ((pixel >>> 24) == 0) {
                continue;
            }
            counts.merge(pixel & 0xFFFFFF, 1, Integer::sum);
        }
        List<Map.Entry<Integer, Integer>> ranked = new ArrayList<>(counts.entrySet());
        // List.sort is stable: equal counts stay in first-seen order.
        ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, ranked.size()); i++) {
            out.add(String.format("#%06x", ranked.get(i).getKey()));
        }
        return out;
    }

    /** The image's raw ARGB pixels, row by row. Needs the FX toolkit for the pixel reader. */
    static int[] pixels(Image image) {
        int width = (int) Math.round(image.getWidth());
        int height = (int) Math.round(image.getHeight());
        if (width <= 0 || height <= 0) {
            return new int[0];
        }
        PixelReader reader = image.getPixelReader();
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[y * width + x] = reader.getArgb(x, y);
            }
        }
        return out;
    }
}

package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.SpriteRegions;
import dev.modmaker.core.model.AssetRef;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM tests for the sprite panel's grid maths and the Lospec palette ranking (report P2-11).
 * No JavaFX toolkit is started here: the selection ranges, region naming and colour counting are
 * static methods, and pixel reading lives in {@link SpritePalette#pixels}, which these tests never
 * touch - they feed it hand-made ARGB arrays instead.
 */
class SpritePaneLogicTest {

    @Test
    void shiftRangeIsInclusiveAndOrderIndependent() {
        assertEquals(List.of(1, 2, 3), SpritePane.rangeIndices(1, 3), "forward drag");
        assertEquals(List.of(1, 2, 3), SpritePane.rangeIndices(3, 1), "backward drag covers the same cells");
        assertEquals(List.of(2), SpritePane.rangeIndices(2, 2), "the ends are inclusive, a zero range is one cell");
    }

    @Test
    void regionOfPathFollowsPackSpritesNaming() {
        assertEquals("my-mod-kiln", SpritePane.regionOfPath("my-mod", "sprites/blocks/kiln.png"),
            "sprites/ files get the mod prefix");
        assertEquals("kiln", SpritePane.regionOfPath("my-mod", "sprites-override/blocks/kiln.png"),
            "sprites-override/ files stay unprefixed");
        assertEquals("block-my-mod-x", SpritePane.regionOfPath("my-mod", "sprites/blocks/block-my-mod-x.png"),
            "a category-prefixed name must not gain a second prefix");
    }

    @Test
    void placeholderRegionsNameTheSpritesTheGridMustShow() {
        ContentRecord block = ContentRecord.create("b1", ContentType.block, "kiln");
        ContentRecord unit = ContentRecord.create("u1", ContentType.unit, "rover");
        assertEquals(List.of("my-mod-kiln", "my-mod-rover"),
            SpritePane.placeholderRegions(List.of(block, unit), "my-mod"));
        assertEquals(List.of(), SpritePane.placeholderRegions(List.of(), "my-mod"));
    }

    @Test
    void clipboardTextPutsOneRegionPerLine() {
        assertEquals("a\nb", SpritePane.clipboardText(List.of("a", "b")));
        assertEquals("", SpritePane.clipboardText(List.of()));
    }

    /** The grid appends a placeholder exactly where the shared bookkeeping says a sprite is absent. */
    @Test
    void missingDetectionDrivesThePlaceholderCells() {
        ModProject project = new ModProject();
        project.meta.name = "my-mod";
        ContentRecord covered = ContentRecord.create("b1", ContentType.block, "kiln");
        ContentRecord absent = ContentRecord.create("b2", ContentType.block, "router");
        ContentRecord patched = ContentRecord.create("b3", ContentType.block, "duo");
        patched.patch = true;
        project.contents.addAll(List.of(covered, absent, patched));
        project.assets.add(new AssetRef("sprites/blocks/kiln.png", 1L, "sprite"));

        List<ContentRecord> missing = SpriteRegions.withoutSprite(project, project.internalName());
        assertEquals(List.of("router"), missing.stream().map(record -> record.name).toList(),
            "covered records and patches never become placeholder cells");
        assertEquals(List.of("my-mod-router"),
            SpritePane.placeholderRegions(missing, project.internalName()));
    }

    @Test
    void topColorsRanksByFrequencyAndCapsAtLimit() {
        int red = 0xFFFF0000;
        int blue = 0xFF0000FF;
        int green = 0xFF00FF00;
        int[] pixels = {blue, red, red, red, green, green};
        assertEquals(List.of("#ff0000", "#00ff00", "#0000ff"), SpritePalette.topColors(pixels, 12),
            "most-used first");
        assertEquals(2, SpritePalette.topColors(pixels, 2).size(), "the strip honours the limit");
        assertEquals(List.of(), SpritePalette.topColors(pixels, 0));
    }

    @Test
    void topColorsSkipsTransparentPixelsAndBreaksTiesByFirstSeen() {
        int[] pixels = {0x00FFFFFF, 0x80102030, 0xFF102030, 0xFF0A0A0A};
        // The transparent pixel is not a palette entry; the semi-transparent one counts toward
        // its own RGB, so anti-aliased edges never split a colour into two swatches.
        assertEquals(List.of("#102030", "#0a0a0a"), SpritePalette.topColors(pixels, 12));
        assertEquals(List.of(), SpritePalette.topColors(new int[]{0x00FFFFFF}, 12));

        int green = 0xFF00FF00;
        int blue = 0xFF0000FF;
        assertEquals(List.of("#00ff00", "#0000ff"), SpritePalette.topColors(new int[]{green, blue}, 12),
            "equal counts keep first-seen order, which pins the strip between refreshes");
        assertTrue(SpritePalette.LIMIT > 0, "the strip shows at least one colour");
    }
}

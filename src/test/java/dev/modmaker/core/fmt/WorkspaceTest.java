package dev.modmaker.core.fmt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which workspace edits which content type. */
class WorkspaceTest {

    @Test
    void routesContentTypesToWorkspaces() {
        assertEquals(Workspace.assets, Workspace.of(ContentType.item));
        assertEquals(Workspace.assets, Workspace.of(ContentType.liquid));
        assertEquals(Workspace.cosmos, Workspace.of(ContentType.planet));
        assertEquals(Workspace.cosmos, Workspace.of(ContentType.sector));
        assertEquals(Workspace.canvas, Workspace.of(ContentType.block));
        // Units get their own tab next to the block builder (参考 (1)/(2).svg).
        assertEquals(Workspace.units, Workspace.of(ContentType.unit));
        // Statuses, weather and teams are simple assets again: their home is the resources grid,
        // which the 2026-10-17 UI audit made the single listing for everything without its own tab.
        assertEquals(Workspace.assets, Workspace.of(ContentType.status));
        assertEquals(Workspace.assets, Workspace.of(ContentType.weather));
        assertEquals(Workspace.assets, Workspace.of(ContentType.team));
        // Inline-only types stay on the canvas: they are flow participants inside a host record.
        assertEquals(Workspace.canvas, Workspace.of(ContentType.bullet));
    }

    @Test
    void everyFileBackedTypeHasExactlyOneCanonicalHome() {
        assertEquals(java.util.List.of(ContentType.item, ContentType.liquid, ContentType.status,
            ContentType.weather, ContentType.team), Workspace.assetTypes(),
            "the resources grid lists exactly the types without their own tab");
        for (ContentType type : ContentType.values()) {
            if (!type.fileBacked()) {
                continue;
            }
            boolean listedInAssets = Workspace.assetTypes().contains(type);
            assertEquals(listedInAssets, Workspace.of(type) == Workspace.assets,
                type + " must either be listed in the assets grid or route to its own tab, not both");
        }
    }

    @Test
    void inlineTypesAreNotListedInTheAssetsGrid() {
        for (ContentType type : ContentType.values()) {
            if (!type.fileBacked()) {
                assertFalse(Workspace.assetTypes().contains(type),
                    "inline-only " + type + " is reached through its host record");
                assertTrue(Workspace.of(type) == Workspace.canvas,
                    "inline-only " + type + " routes to the canvas");
            }
        }
    }
}

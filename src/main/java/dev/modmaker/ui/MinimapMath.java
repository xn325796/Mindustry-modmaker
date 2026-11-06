package dev.modmaker.ui;

/**
 * Pure projection maths behind the canvas minimaps and the zoom LOD switch (report P1-6/7):
 * world bounds to minimap box, viewport rect to minimap rect, minimap point back to world.
 * No JavaFX state, so the maths is unit-testable on a plain JVM.
 *
 * <p>Both canvases share one view model: screen = world * zoom + pan, so the same formulas map
 * the tech tree canvas and the content canvas alike.
 */
final class MinimapMath {

    private MinimapMath() {
    }

    /** Scale + offset mapping world coordinates into a minimap box (see {@link #fit}). */
    record Projection(double scale, double offsetX, double offsetY) {
        double x(double worldX) {
            return offsetX + worldX * scale;
        }

        double y(double worldY) {
            return offsetY + worldY * scale;
        }

        double size(double worldSize) {
            return worldSize * scale;
        }
    }

    /**
     * Fits the world content box into the minimap box (margin on every side), preserving aspect:
     * one scale for both axes, content centred in the box. Degenerate content (an empty canvas)
     * falls back to scale 1 so the viewport rect still lands somewhere sane instead of dividing
     * by zero.
     */
    static Projection fit(double contentMinX, double contentMinY, double contentMaxX,
        double contentMaxY, double mapWidth, double mapHeight, double margin) {
        double contentWidth = Math.max(0, contentMaxX - contentMinX);
        double contentHeight = Math.max(0, contentMaxY - contentMinY);
        double boxWidth = Math.max(1, mapWidth - 2 * margin);
        double boxHeight = Math.max(1, mapHeight - 2 * margin);
        double scale = contentWidth <= 0 || contentHeight <= 0
            ? 1
            : Math.min(boxWidth / contentWidth, boxHeight / contentHeight);
        double offsetX = margin + (boxWidth - contentWidth * scale) / 2 - contentMinX * scale;
        double offsetY = margin + (boxHeight - contentHeight * scale) / 2 - contentMinY * scale;
        return new Projection(scale, offsetX, offsetY);
    }

    /** The world rectangle currently visible in a viewport of viewWidth x viewHeight. */
    static double[] viewportWorldRect(double panX, double panY, double zoom,
        double viewWidth, double viewHeight) {
        double scale = zoom == 0 ? 1 : zoom;
        return new double[] {
            -panX / scale, -panY / scale,
            (viewWidth - panX) / scale, (viewHeight - panY) / scale};
    }

    /** The viewport rect projected into minimap coordinates, clamped into the minimap box. */
    static double[] viewportMapRect(double panX, double panY, double zoom,
        double viewWidth, double viewHeight, Projection projection,
        double mapWidth, double mapHeight) {
        double[] world = viewportWorldRect(panX, panY, zoom, viewWidth, viewHeight);
        double left = Math.max(0, Math.min(mapWidth, projection.x(world[0])));
        double top = Math.max(0, Math.min(mapHeight, projection.y(world[1])));
        double right = Math.max(0, Math.min(mapWidth, projection.x(world[2])));
        double bottom = Math.max(0, Math.min(mapHeight, projection.y(world[3])));
        return new double[] {left, top, right - left, bottom - top};
    }

    /** Inverse of {@link Projection#x}: a minimap point back to world, clamped into the map box. */
    static double worldXAtMap(double mapX, double mapWidth, Projection projection) {
        return (Math.max(0, Math.min(mapWidth, mapX)) - projection.offsetX()) / projection.scale();
    }

    static double worldYAtMap(double mapY, double mapHeight, Projection projection) {
        return (Math.max(0, Math.min(mapHeight, mapY)) - projection.offsetY()) / projection.scale();
    }

    /** The pan that holds a world point at the viewport centre - the minimap jump's target. */
    static double panToCenterX(double worldX, double zoom, double viewWidth) {
        return viewWidth / 2 - worldX * zoom;
    }

    static double panToCenterY(double worldY, double zoom, double viewHeight) {
        return viewHeight / 2 - worldY * zoom;
    }

    /** LOD engages strictly below the threshold; at or above it the canvas keeps full detail. */
    static boolean lodActive(double zoom, double lodThreshold) {
        return zoom < lodThreshold;
    }
}

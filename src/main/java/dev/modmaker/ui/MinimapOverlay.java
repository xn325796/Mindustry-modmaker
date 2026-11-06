package dev.modmaker.ui;

import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;

import java.util.function.BiConsumer;

/**
 * The corner minimap (report P1-6): colour blocks for every node plus a viewport frame, both
 * projected from world space by {@link MinimapMath}. Clicking or dragging anywhere in the overlay
 * recentres the main viewport on the picked world point - the React Flow MiniMap interaction,
 * kept minimal. The overlay consumes its own mouse gestures so the canvas pan/zoom handlers
 * underneath never see them.
 *
 * <p>Colours: the tech tree's three-state blocks are plain style classes resolved in app.css
 * against the -mm-* palette; content cards pass their {@code ContentType.color()} as the inline
 * fill, the same source their header already uses. No literal colour lives in this class.
 */
final class MinimapOverlay extends Pane {

    static final double WIDTH = 160;
    static final double HEIGHT = 110;
    static final double MARGIN = 8;
    /** Blocks never shrink below this, so a dense forest still reads as colour on the map. */
    private static final double MIN_BLOCK_PX = 2;

    /** Last child: the frame must stay on top of the blocks. */
    private final Rectangle viewport = new Rectangle();
    private MinimapMath.Projection projection = MinimapMath.fit(0, 0, 1, 1, WIDTH, HEIGHT, MARGIN);
    private BiConsumer<Double, Double> centerOn = (x, y) -> { };

    MinimapOverlay() {
        getStyleClass().add("minimap");
        setMinSize(WIDTH, HEIGHT);
        setPrefSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        // An unmanaged child of the canvas is never resized by a layout pass; fix the size now.
        resize(WIDTH, HEIGHT);
        viewport.getStyleClass().add("mm-viewport");
        viewport.setManaged(false);
        getChildren().add(viewport);
        setOnMousePressed(event -> {
            event.consume();
            jumpTo(event.getX(), event.getY());
        });
        setOnMouseDragged(event -> {
            event.consume();
            jumpTo(event.getX(), event.getY());
        });
        setOnMouseReleased(event -> event.consume());
    }

    /** Receives the world point the user picked; the owning canvas re-centres its view on it. */
    void setCenterOn(BiConsumer<Double, Double> centerOn) {
        this.centerOn = centerOn;
    }

    /** Starts a fresh block pass with the projection the blocks are computed against. */
    void beginBlocks(MinimapMath.Projection projection) {
        this.projection = projection;
        clearBlocks();
    }

    /** Drops every block (the viewport frame is not a block). */
    void clearBlocks() {
        while (getChildren().size() > 1) {
            getChildren().remove(0);
        }
    }

    /** One node's world rect, projected into the map. At most one of styleClass / inlineFill. */
    void addBlock(double worldX, double worldY, double worldWidth, double worldHeight,
        String styleClass, String inlineFill) {
        Rectangle block = new Rectangle(
            projection.x(worldX), projection.y(worldY),
            Math.max(MIN_BLOCK_PX, projection.size(worldWidth)),
            Math.max(MIN_BLOCK_PX, projection.size(worldHeight)));
        if (styleClass != null) {
            block.getStyleClass().add(styleClass);
        }
        if (inlineFill != null) {
            block.setStyle("-fx-fill: " + inlineFill + ";");
        }
        block.setManaged(false);
        getChildren().add(getChildren().size() - 1, block);
    }

    /** Moves the viewport frame to the canvas' current pan/zoom/view size. */
    void updateViewport(double panX, double panY, double zoom, double viewWidth,
        double viewHeight) {
        double[] rect = MinimapMath.viewportMapRect(panX, panY, zoom, viewWidth, viewHeight,
            projection, WIDTH, HEIGHT);
        viewport.setX(rect[0]);
        viewport.setY(rect[1]);
        viewport.setWidth(rect[2]);
        viewport.setHeight(rect[3]);
    }

    private void jumpTo(double mapX, double mapY) {
        centerOn.accept(
            MinimapMath.worldXAtMap(mapX, WIDTH, projection),
            MinimapMath.worldYAtMap(mapY, HEIGHT, projection));
    }

    // --- test seams ------------------------------------------------------------------------------

    /** The frame's current map rect: {x, y, width, height}. */
    double[] viewportRect() {
        return new double[] {viewport.getX(), viewport.getY(), viewport.getWidth(),
            viewport.getHeight()};
    }

    /** The active projection as {scale, offsetX, offsetY}. */
    double[] projectionValues() {
        return new double[] {projection.scale(), projection.offsetX(), projection.offsetY()};
    }
}

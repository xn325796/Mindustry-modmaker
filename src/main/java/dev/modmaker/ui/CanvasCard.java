package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import javafx.geometry.Point2D;
import javafx.scene.Node;

import java.util.List;

/**
 * What the canvas needs from a card, regardless of how richly it renders: full content cards show
 * field previews and ports; item/liquid carrier cards are lightweight and only own an ID port.
 */
interface CanvasCard {

    Node node();

    double cardWidth();

    double cardHeight();

    /** The content type this card stands for - its colour block in the minimap (report P1-6). */
    ContentType cardType();

    List<ContentCard.Port> ports();

    /** Card-relative anchor of a port, or null when the card has no such port. */
    Point2D portAnchor(String fieldKey);

    void bindNodeId(String nodeId);

    /** Level-of-detail hook; carriers are already minimal and ignore it. */
    default void setLod(double zoom) {
    }

    /** True for lightweight carrier cards (items, liquids, vanilla references). */
    default boolean isCarrier() {
        return false;
    }
}

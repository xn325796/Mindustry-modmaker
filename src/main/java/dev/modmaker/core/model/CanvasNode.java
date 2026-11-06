package dev.modmaker.core.model;

/**
 * A card on a board.
 *
 * <p>A content node only references a {@link ContentRecord} by id; the record itself lives in the
 * project. That keeps the canvas a view over the mod, so the same content can appear on several
 * boards and imported content that has no natural place is still representable.
 */
public final class CanvasNode {
    public static final String KIND_CONTENT = "content";
    public static final String KIND_NOTE = "note";
    public static final String KIND_GROUP = "group";

    public String id;
    public String kind = KIND_CONTENT;
    public String contentId;
    /**
     * Direct reference name for carrier cards that point at content without a record (vanilla
     * items/liquids). When set, {@code contentId} is null and the node is a lightweight carrier.
     */
    public String refName;
    /** Content type of {@link #refName} ("item"/"liquid"); decides colour and dropdown candidates. */
    public String refType;
    public double x;
    public double y;
    public double w = 260;
    public double h = 130;
    /** Note body or group label. */
    public String text = "";
    public String color = "";

    /** A detached copy for read-only work off the model's thread. */
    public CanvasNode snapshot() {
        CanvasNode copy = new CanvasNode();
        copy.id = id;
        copy.kind = kind;
        copy.contentId = contentId;
        copy.refName = refName;
        copy.refType = refType;
        copy.x = x;
        copy.y = y;
        copy.w = w;
        copy.h = h;
        copy.text = text;
        copy.color = color;
        return copy;
    }
}

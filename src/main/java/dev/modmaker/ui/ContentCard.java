package dev.modmaker.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One content record drawn as a node card that reproduces the SVG mockup ({@code 画板 1.svg})
 * exactly: a full-width type-colour header, a left column of dark port boxes with grey dots, and
 * one dark row panel per field holding a pill-styled control - slider for numbers, switch for
 * booleans, input pill for text, and the wired value for references (those are edited by dragging
 * on the canvas, not typed).
 *
 * <p>Geometry from the mockup: card 192 wide, header 32, rows 32; port box 20 wide; the label sits
 * on the card background between the port box and the row panel.
 *
 * <p>Widgets commit live through {@code ProjectController.setField}, like the inspector's field
 * editors. While a widget owns the focus the card reports {@link #isEditing()}, and the canvas
 * keeps this card instance instead of rebuilding it out from under the caret.
 */
final class ContentCard extends StackPane implements CanvasCard {

    static final double WIDTH = 192;
    static final double HEIGHT = 150;
    static final double HEADER_HEIGHT = 32;
    static final double ROW_HEIGHT = 32;
    static final double PORT_BOX_WIDTH = 20;
    static final double LABEL_WIDTH = 74;
    static final double PORT_RADIUS = 4;
    static final int MAX_INPUT_PORTS = 8;
    static final int MAX_WIDGET_ROWS = 8;
    /** Prototype palette (画板 1.svg). */
    static final String CARD_BG = "#1b1a33";
    static final String ROW_BG = "#1c1b2f";
    static final String PORT_BOX_BG = "#131236";
    static final String PORT_DOT = "#757575";
    static final String INPUT_PILL = "#25243c";
    static final String SLIDER_TRACK = "#d9d9d9";
    static final String SWITCH_ON = "#52d33d";
    static final String ROW_TEXT = "#d9d9d9";
    static final String LABEL_TEXT = "#a0a3c8";

    /** Level of detail: what the card still draws at the current zoom. */
    enum Lod {
        DETAIL, MEDIUM, FAR
    }

    /** One draggable/hittable connection point. {@code fieldKey == null} is the ID output port. */
    static final class Port {
        final String nodeId;
        final String fieldKey;
        /** Anchor relative to the card's top-left corner. */
        final double x;
        final double y;
        final Circle circle;

        Port(String nodeId, String fieldKey, double x, double y, Circle circle) {
            this.nodeId = nodeId;
            this.fieldKey = fieldKey;
            this.x = x;
            this.y = y;
            this.circle = circle;
        }
    }

    private final List<Port> ports = new ArrayList<>();
    private final List<javafx.scene.Node> rowNodes = new ArrayList<>();
    private final List<String> widgetSignature = new ArrayList<>();
    private final List<String> inputSignature = new ArrayList<>();
    private final String displayNameSignature;
    private final String subtitleSignature;
    private final boolean inlineSignature;
    private final boolean patchSignature;
    private final ContentType type;
    private final double height;
    private Lod lod = Lod.DETAIL;

    /** One editable widget binding: commits through the controller like the inspector does. */
    private record FieldBinding(ProjectController controller, ContentRecord record, FieldDef field) {
        void commit(JsonElement value) {
            controller.setField(record, field.name(), value);
        }
    }

    ContentCard(ProjectController controller, ContentRecord record, List<FieldDef> widgetFields,
        List<String> inputFields) {
        this.type = record.type;
        getStyleClass().add("node-card");
        displayNameSignature = controller.displayName(record);
        subtitleSignature = controller.subtitleOf(record);
        inlineSignature = record.isInline();
        patchSignature = record.patch;
        for (FieldDef field : widgetFields) {
            widgetSignature.add(field.name() + ":" + field.type());
        }
        inputSignature.addAll(inputFields);

        int rows = widgetFields.size();
        height = HEADER_HEIGHT + rows * ROW_HEIGHT;

        setMinSize(WIDTH, height);
        setPrefSize(WIDTH, height);
        setMaxSize(WIDTH, height);

        // Header: full-width type colour, white title, inline/patch badge.
        Pane header = new Pane();
        header.getStyleClass().add("node-head");
        header.setStyle("-fx-background-color: " + type.color() + ";");
        header.setPrefSize(WIDTH, HEADER_HEIGHT);
        Label title = new Label(displayNameSignature + (record.patch ? "  ·patch" : ""));
        title.getStyleClass().add("node-title");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMaxWidth(WIDTH - 16);
        title.setAlignment(Pos.CENTER_LEFT);
        title.setPadding(new Insets(0, 0, 0, 10));
        title.setManaged(false);
        title.setLayoutX(8);
        title.setLayoutY((HEADER_HEIGHT - 17) / 2);
        header.getChildren().add(title);
        getChildren().add(header);

        for (int i = 0; i < rows; i++) {
            FieldDef field = widgetFields.get(i);
            double top = HEADER_HEIGHT + i * ROW_HEIGHT;
            Pane portBox = portBox(top);
            Label rowLabel = rowLabel(top, field);
            Pane rowPanel = rowPanel(top,
                new FieldBinding(controller, record, field), field,
                record.fields.get(field.name()));
            rowNodes.addAll(List.of(portBox, rowLabel, rowPanel));
            getChildren().addAll(portBox, rowLabel, rowPanel);
        }

        // Ports: one per row that is a reference field, centred in its port box.
        for (int i = 0; i < rows; i++) {
            FieldDef field = widgetFields.get(i);
            if (!inputFields.contains(field.name())) {
                continue;
            }
            double y = HEADER_HEIGHT + i * ROW_HEIGHT + ROW_HEIGHT / 2;
            Circle dot = new Circle(PORT_RADIUS);
            dot.setStyle("-fx-fill: " + PORT_DOT + ";");
            place(dot, PORT_BOX_WIDTH / 2, y);
            ports.add(new Port(null, field.name(), PORT_BOX_WIDTH / 2 + PORT_RADIUS, y, dot));
        }
        // ID output port on the right edge of the header.
        Circle id = new Circle(PORT_RADIUS);
        id.setStyle("-fx-fill: " + PORT_DOT + ";");
        place(id, WIDTH - 8, HEADER_HEIGHT / 2.0);
        ports.add(new Port(null, null, WIDTH - 8 + PORT_RADIUS, HEADER_HEIGHT / 2.0, id));

        Tooltip.install(this, new Tooltip(subtitleSignature));
    }

    /** The dark port box a row's connection dot sits in. */
    private Pane portBox(double top) {
        Pane box = new Pane();
        box.setStyle("-fx-background-color: " + PORT_BOX_BG + ";");
        box.setPrefSize(PORT_BOX_WIDTH, ROW_HEIGHT);
        box.setLayoutY(top);
        return box;
    }

    /**
     * The field's label chip, on the mockup's white strip between the port box and the row panel:
     * the localised field name in dark text, with the raw JSON key in the tooltip so exports stay
     * traceable.
     */
    private Label rowLabel(double top, FieldDef field) {
        Label label = new Label(Labels.field(field.name()));
        label.getStyleClass().add("node-row-label");
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        label.setAlignment(Pos.CENTER_LEFT);
        label.setPadding(new Insets(0, 0, 0, 6));
        label.setPrefSize(LABEL_WIDTH, ROW_HEIGHT);
        label.setLayoutY(top);
        label.setTooltip(new Tooltip(field.name()));
        return label;
    }

    /** The dark row panel with the field's pill control. */
    private Pane rowPanel(double top, FieldBinding binding, FieldDef field, JsonElement value) {
        double panelWidth = WIDTH - PORT_BOX_WIDTH - LABEL_WIDTH;
        Pane panel = new Pane();
        panel.setStyle("-fx-background-color: " + ROW_BG + ";");
        panel.setPrefSize(panelWidth, ROW_HEIGHT);
        panel.setLayoutX(PORT_BOX_WIDTH + LABEL_WIDTH);
        panel.setLayoutY(top);

        double controlWidth = panelWidth - 14;
        switch (field.type()) {
            case bool -> {
                boolean on = value != null && value.isJsonPrimitive() && value.getAsBoolean();
                Pane toggle = toggleSwitch(on, () -> binding.commit(
                    new com.google.gson.JsonPrimitive(!on)));
                toggle.setLayoutX(controlWidth - 44);
                toggle.setLayoutY((ROW_HEIGHT - 20) / 2);
                panel.getChildren().add(toggle);
            }
            case number -> {
                Slider slider = new Slider(0, sliderMax(field, value), 0);
                slider.getStyleClass().add("node-slider");
                slider.setPrefSize(controlWidth - 60, 12);
                slider.setLayoutX(7);
                slider.setLayoutY((ROW_HEIGHT - 12) / 2);
                double current = value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isNumber()
                    ? value.getAsJsonPrimitive().getAsDouble() : 0;
                slider.setValue(Math.max(0, Math.min(slider.getMax(), current)));
                slider.valueChangingProperty().addListener((observable, was, changing) -> {
                    if (!changing) {
                        binding.commit(numberOf(slider.getValue()));
                    }
                });
                panel.getChildren().add(slider);
                Label amount = new Label(plainText(value));
                amount.getStyleClass().add("node-value");
                amount.setTextOverrun(OverrunStyle.ELLIPSIS);
                amount.setMaxWidth(48);
                amount.setLayoutX(controlWidth - 52);
                amount.setLayoutY((ROW_HEIGHT - 14) / 2);
                panel.getChildren().add(amount);
            }
            case text, sprite -> {
                TextField input = new TextField(plainText(value));
                input.getStyleClass().add("node-input");
                input.setPrefSize(controlWidth - 12, 22);
                input.setLayoutX(6);
                input.setLayoutY((ROW_HEIGHT - 22) / 2);
                input.textProperty().addListener((observable, was, now) ->
                    binding.commit(now.isBlank() ? null : new com.google.gson.JsonPrimitive(now)));
                focusGuard(input);
                panel.getChildren().add(input);
            }
            default -> {
                Label wired = new Label(summary(value));
                wired.getStyleClass().add("node-value");
                wired.setTextOverrun(OverrunStyle.ELLIPSIS);
                wired.setMaxWidth(controlWidth - 12);
                wired.setLayoutX(7);
                wired.setLayoutY((ROW_HEIGHT - 14) / 2);
                if (value != null) {
                    wired.setTooltip(new Tooltip(summary(value)));
                }
                panel.getChildren().add(wired);
            }
        }
        return panel;
    }

    private static double sliderMax(FieldDef field, JsonElement value) {
        double base = 10;
        if (field.defaultValue() != null && field.defaultValue().isJsonPrimitive()
            && field.defaultValue().getAsJsonPrimitive().isNumber()) {
            base = Math.abs(field.defaultValue().getAsDouble()) * 2;
        }
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            base = Math.max(base, Math.abs(value.getAsJsonPrimitive().getAsDouble()));
        }
        return Math.max(10, base);
    }

    /** The mockup's pill switch: green when on, dark when off, white thumb that slides. */
    private Pane toggleSwitch(boolean on, Runnable toggle) {
        StackPane pill = new StackPane();
        pill.getStyleClass().add("node-switch");
        pill.setStyle("-fx-background-color: " + (on ? SWITCH_ON : INPUT_PILL) + ";");
        pill.setPrefSize(36, 20);
        pill.setMaxSize(36, 20);
        Circle thumb = new Circle(8);
        thumb.setStyle("-fx-fill: #ffffff;");
        thumb.setTranslateX(on ? 8 : -8);
        pill.getChildren().add(thumb);
        pill.setOnMouseClicked(event -> {
            toggle.run();
            event.consume();
        });
        return pill;
    }

    private static JsonElement numberOf(double value) {
        return value == Math.rint(value) && Math.abs(value) < Long.MAX_VALUE
            ? new com.google.gson.JsonPrimitive((long) value)
            : new com.google.gson.JsonPrimitive(value);
    }

    private static String plainText(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static String summary(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "-";
        }
        if (value.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            value.getAsJsonArray().forEach(element -> parts.add(summary(element)));
            return String.join(", ", parts);
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : new String[] {"item", "liquid", "payload", "block", "unit", "content"}) {
                JsonElement named = object.get(key);
                if (named != null && named.isJsonPrimitive()) {
                    JsonElement amount = object.get("amount");
                    return named.getAsString() + (amount != null ? "/" + amount.getAsString() : "");
                }
            }
            JsonElement block = object.get("block");
            if (block != null && block.isJsonPrimitive()) {
                return block.getAsString();
            }
            return "{…}";
        }
        return value.getAsString();
    }

    /** A widget owns the focus while its text is being typed; the canvas must not rebuild. */
    private void focusGuard(javafx.scene.Node node) {
        node.focusedProperty().addListener((observable, was, now) -> {
            if (now) {
                editingCards.add(this);
            } else if (editingCards.remove(this) && onEditEnd != null) {
                onEditEnd.run();
            }
        });
    }

    /** Cards whose widget currently owns the focus; the canvas skips rebuilding them. */
    static final List<ContentCard> editingCards = new ArrayList<>();

    /** Runs when a widget releases the focus, so the canvas can catch up on deferred rebuilds. */
    private Runnable onEditEnd;

    void setOnEditEnd(Runnable onEditEnd) {
        this.onEditEnd = onEditEnd;
    }

    boolean isEditing() {
        return editingCards.contains(this);
    }

    /** Whether rebuilding this card from the same inputs would draw something different. */
    boolean matches(String displayName, String subtitle, boolean inline, boolean patch,
        List<String> widgetFields, List<String> inputFields) {
        List<String> signature = new ArrayList<>(widgetFields);
        return displayNameSignature.equals(displayName)
            && subtitleSignature.equals(subtitle)
            && inlineSignature == inline
            && patchSignature == patch
            && widgetSignature.equals(signature)
            && inputSignature.equals(inputFields);
    }

    /**
     * Drops detail as the canvas zooms out: below 0.5 the row panels and labels go, below 0.25
     * only the header remains readable anyway.
     */
    @Override
    public void setLod(double zoom) {
        Lod next = zoom < 0.25 ? Lod.FAR : zoom < 0.5 ? Lod.MEDIUM : Lod.DETAIL;
        if (next == lod) {
            return;
        }
        lod = next;
        boolean detail = lod == Lod.DETAIL;
        rowNodes.forEach(node -> {
            node.setVisible(detail);
            node.setManaged(detail);
        });
        getStyleClass().removeAll("lod-medium", "lod-far");
        if (lod == Lod.MEDIUM) {
            getStyleClass().add("lod-medium");
        } else if (lod == Lod.FAR) {
            getStyleClass().add("lod-far");
        }
    }

    private void place(javafx.scene.Node node, double x, double y) {
        node.setManaged(false);
        node.setLayoutX(x);
        node.setLayoutY(y);
        getChildren().add(node);
    }

    // --- CanvasCard ------------------------------------------------------------------------------

    @Override
    public javafx.scene.Node node() {
        return this;
    }

    @Override
    public double cardWidth() {
        return WIDTH;
    }

    @Override
    public double cardHeight() {
        return height;
    }

    @Override
    public ContentType cardType() {
        return type;
    }

    @Override
    public List<Port> ports() {
        return ports;
    }

    @Override
    public Point2D portAnchor(String fieldKey) {
        for (Port port : ports) {
            if (Objects.equals(port.fieldKey, fieldKey)) {
                return new Point2D(port.x, port.y);
            }
        }
        return null;
    }

    @Override
    public void bindNodeId(String nodeId) {
        ports.replaceAll(port -> new Port(nodeId, port.fieldKey, port.x, port.y, port.circle));
    }
}

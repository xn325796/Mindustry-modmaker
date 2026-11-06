package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Lightweight carrier card for items and liquids: a type-coloured strip, a display name and one
 * dropdown that rebinds the card to any registered record of that type - or to a vanilla name.
 *
 * <p>Carriers exist so a mod with dozens of items does not have to pay for a full content card per
 * item: no field preview, no reference ports, no schema scans. The card still owns one ID output
 * port, so requirement and research edges can be dragged from it exactly like before; the edge
 * writes the bound record's name (or the vanilla name).
 */
final class CarrierCard extends BorderPane implements CanvasCard {

    static final double WIDTH = 260;
    static final double HEIGHT = 64;
    static final double PORT_RADIUS = ContentCard.PORT_RADIUS;

    /** One dropdown entry: a bound record id, or a direct vanilla reference name. */
    record Candidate(String label, String recordId, String refName) {
    }

    private final List<ContentCard.Port> ports = new ArrayList<>();
    private final ComboBox<String> picker = new ComboBox<>();
    private final Label title = new Label();
    private final List<Candidate> candidates;
    private final String selectedRecordId;
    private final String selectedRefName;
    private final ContentType type;

    CarrierCard(ContentType type, String typeLabel, String displayName, List<Candidate> candidates,
        String selectedRecordId, String selectedRefName, BiConsumer<String, String> onRebind) {
        this.type = type;
        this.candidates = candidates;
        this.selectedRecordId = selectedRecordId;
        this.selectedRefName = selectedRefName;
        getStyleClass().add("card");
        getStyleClass().add("carrier-card");
        setMinSize(WIDTH, HEIGHT);
        setPrefSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);

        Region strip = new Region();
        strip.getStyleClass().add("type-strip");
        strip.setStyle("-fx-background-color: " + type.color() + ";");
        strip.setMinWidth(3);
        strip.setPrefWidth(3);
        strip.setMaxWidth(3);

        title.setText(displayName);
        title.getStyleClass().add("card-title");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMaxWidth(WIDTH - 120);
        Label typeBadge = new Label(typeLabel);
        typeBadge.getStyleClass().add("card-badge");

        HBox heading = new HBox(8, title, typeBadge);
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.setStyle("-fx-background-color: " + type.color() + "26;");
        heading.setPadding(new Insets(2, 6, 2, 6));
        HBox.setHgrow(title, Priority.ALWAYS);

        picker.getItems().setAll(candidates.stream().map(Candidate::label).toList());
        picker.getSelectionModel().select(currentIndex());
        picker.setMaxWidth(Double.MAX_VALUE);
        // Selecting a candidate rebinds this card; the controller makes it undoable and the canvas
        // re-resolves every edge that starts here.
        picker.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            if (now == null) {
                return;
            }
            Candidate candidate = candidates.stream()
                .filter(entry -> entry.label().equals(now))
                .findFirst().orElse(null);
            if (candidate != null && !isCurrent(candidate)) {
                onRebind.accept(candidate.recordId(), candidate.refName());
            }
        });
        Tooltip.install(picker, new Tooltip(Messages.t("carrier.pick.hint")));

        VBox body = new VBox(4, heading, picker);
        body.setPadding(new Insets(7, 12, 7, 14));
        setCenter(body);

        // ID output port on the right edge: requirement/research edges are dragged from here.
        Circle id = new Circle(PORT_RADIUS);
        id.getStyleClass().addAll("port", "port-out");
        place(id, WIDTH - 8, HEIGHT / 2.0);
        ports.add(new ContentCard.Port(null, null, WIDTH - 8 + PORT_RADIUS, HEIGHT / 2.0, id));
    }

    private int currentIndex() {
        for (int i = 0; i < candidates.size(); i++) {
            if (isCurrent(candidates.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isCurrent(Candidate candidate) {
        return Objects.equals(candidate.recordId(), selectedRecordId)
            && Objects.equals(candidate.refName(), selectedRefName);
    }

    public List<ContentCard.Port> ports() {
        return ports;
    }

    /** Card-relative anchor of a port, or null when the card has no such port. */
    public Point2D portAnchor(String fieldKey) {
        for (ContentCard.Port port : ports) {
            if (Objects.equals(port.fieldKey, fieldKey)) {
                return new Point2D(port.x, port.y);
            }
        }
        return null;
    }

    /** Called by the canvas so the port knows which node it belongs to. */
    public void bindNodeId(String nodeId) {
        ports.replaceAll(port ->
            new ContentCard.Port(nodeId, port.fieldKey, port.x, port.y, port.circle));
    }

    /** Whether re-rendering this card for the same binding would draw something different. */
    boolean matches(String displayName, String recordId, String refName) {
        String signature = (recordId == null ? "" : recordId)
            + "|" + (refName == null ? "" : refName);
        return title.getText().equals(displayName) && bindingSignature().equals(signature);
    }

    /** Whether this card already renders for the given content type. */
    boolean showsType(ContentType type) {
        return this.type == type;
    }

    private String bindingSignature() {
        return (selectedRecordId == null ? "" : selectedRecordId)
            + "|" + (selectedRefName == null ? "" : selectedRefName);
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
        return HEIGHT;
    }

    @Override
    public ContentType cardType() {
        return type;
    }

    @Override
    public boolean isCarrier() {
        return true;
    }
}

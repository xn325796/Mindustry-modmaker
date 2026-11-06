package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.CanvasNode;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.CubicCurveTo;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The node canvas: pan, zoom, drag, rubber-band selection, reference edges - and drawing new
 * connections by dragging between ports.
 *
 * <p>Coordinate handling: mouse events that bubble up from a card carry coordinates relative to the
 * card's inner node, so handlers always convert {@code event.getSceneX/Y()} to this pane's local
 * space first, then to world space. The interaction core is exposed as {@link #pressAt},
 * {@link #dragTo} and {@link #releaseAt} taking pane-local coordinates, which keeps the whole
 * interaction logic testable without OS-level input.
 */
public final class NodeCanvasPane extends Region {

    public static final DataFormat CONTENT_TYPE_FORMAT = new DataFormat("application/x-mm-ctype");

    private static final double MIN_ZOOM = 0.15;
    private static final double MAX_ZOOM = 2.5;
    private static final double PORT_HIT_RADIUS = 11;
    /** How close a press must land to a reroute waypoint (report P2-9) to grab it. */
    private static final double REROUTE_HIT_RADIUS = 7;
    /** How close a pending connection drag must come to a compatible port to snap onto it. */
    private static final double PORT_SNAP_RADIUS = 20;
    /**
     * Below this zoom a 192px card is under 48 screen px: even its header title is unreadable,
     * so the world drops to LOD colour blocks (report P1-7, Blender nodemap style). The card's
     * own field rows vanish earlier, at the thresholds inside ContentCard.setLod.
     */
    private static final double LOD_ZOOM = 0.25;

    private final ProjectController controller;
    private final Group world = new Group();
    private final Pane edgeLayer = new Pane();
    private final Pane nodeLayer = new Pane();
    /** Above the cards, so a connection's head is never hidden under the target card. */
    private final Pane arrowLayer = new Pane();
    private final Rectangle rubber = new Rectangle();
    /** Corner minimap (report P1-6): hidden until the workspace toolbar toggles it on. */
    private final MinimapOverlay minimap = new MinimapOverlay();
    /** Flat-theme dot grid: drawn in pane coordinates, under every world layer. A world-sized
     * canvas would need a multi-hundred-MB pixel buffer once the zoom drops near MIN_ZOOM. */
    private final Canvas gridCanvas = new Canvas();
    private boolean gridRedrawPending;
    private final CubicCurve pendingCurve = new CubicCurve();
    private final Translate pan = new Translate();
    private final Scale zoom = new Scale(1, 1);

    private final Map<String, CanvasCard> cardsByNode = new LinkedHashMap<>();
    private final Map<String, Path> curvesByEdge = new LinkedHashMap<>();
    private final Map<String, Path> pickCurves = new LinkedHashMap<>();
    private final Map<String, Label> labelsByEdge = new LinkedHashMap<>();
    private final Map<String, javafx.scene.shape.Polygon> arrowsByEdge = new LinkedHashMap<>();
    /** The visible waypoint dots, one list per edge, rebuilt with every refresh (report P2-9). */
    private final Map<String, List<Circle>> rerouteDotsByEdge = new LinkedHashMap<>();
    private final Set<String> selectedNodes = new LinkedHashSet<>();
    private String selectedEdgeId;
    private String selectedRerouteEdgeId;
    private int selectedRerouteIndex = -1;
    private String dragRerouteEdgeId;
    private int dragRerouteIndex = -1;
    private double[] dragRerouteBefore;

    private Point2D pressLocal = Point2D.ZERO;
    private Point2D pressWorld = Point2D.ZERO;
    private boolean panning;
    /** Space held while the canvas owns the focus: a primary drag pans instead of selecting. */
    private boolean spaceDown;
    private boolean rubberBanding;
    private String dragNode;
    private final Map<String, Point2D> dragOrigins = new LinkedHashMap<>();
    private ContentCard.Port pendingPort;
    /** The compatible port the pending drag is currently snapping to (highlighted). */
    private ContentCard.Port pendingSnap;
    private double cascade;

    public NodeCanvasPane(ProjectController controller) {
        this.controller = controller;
        getStyleClass().add("canvas-area");
        setFocusTraversable(true);

        edgeLayer.setManaged(false);
        nodeLayer.setManaged(false);
        arrowLayer.setManaged(false);
        arrowLayer.setMouseTransparent(true);
        gridCanvas.setManaged(false);
        gridCanvas.setMouseTransparent(true);
        world.getChildren().addAll(edgeLayer, nodeLayer, arrowLayer);
        world.getTransforms().addAll(pan, zoom);
        world.getStyleClass().add("canvas-world");
        getChildren().add(world);
        getChildren().add(0, gridCanvas);

        // The grid stays viewport-sized no matter how far the canvas is zoomed out; the pending
        // flag keeps a pan gesture (which moves both axes at once) to one redraw per pulse.
        pan.xProperty().addListener(observable -> scheduleGridRedraw());
        pan.yProperty().addListener(observable -> scheduleGridRedraw());
        zoom.xProperty().addListener(observable -> scheduleGridRedraw());
        widthProperty().addListener(observable -> scheduleGridRedraw());
        heightProperty().addListener(observable -> scheduleGridRedraw());
        // The minimap frame follows every viewport change (report P1-6); one rect update is
        // cheap enough to run inline, no pulse coalescing needed.
        pan.xProperty().addListener(observable -> updateMinimapViewport());
        pan.yProperty().addListener(observable -> updateMinimapViewport());
        zoom.xProperty().addListener(observable -> updateMinimapViewport());
        widthProperty().addListener(observable -> updateMinimapViewport());
        heightProperty().addListener(observable -> updateMinimapViewport());

        // JavaFX regions do not clip children by default: without this, a card dragged outside the
        // canvas (negative world coordinates, say) renders on top of the toolbar and side panels.
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        pendingCurve.getStyleClass().add("edge-pending");
        pendingCurve.setVisible(false);
        pendingCurve.setMouseTransparent(true);
        edgeLayer.getChildren().add(pendingCurve);

        rubber.getStyleClass().add("rubber-band");
        rubber.setVisible(false);
        getChildren().add(rubber);

        // Above everything: the corner minimap, hidden until the toolbar toggles it on.
        getChildren().add(minimap);
        minimap.setManaged(false);
        minimap.setVisible(false);
        minimap.layoutXProperty().bind(
            widthProperty().subtract(MinimapOverlay.WIDTH).subtract(12));
        minimap.layoutYProperty().bind(
            heightProperty().subtract(MinimapOverlay.HEIGHT).subtract(12));
        minimap.setCenterOn((worldX, worldY) -> {
            double scale = zoom.getX() == 0 ? 1 : zoom.getX();
            pan.setX(MinimapMath.panToCenterX(worldX, scale, getWidth()));
            pan.setY(MinimapMath.panToCenterY(worldY, scale, getHeight()));
            rememberViewport();
        });

        controller.onProjectChanged(this::refresh);
        controller.onSelectionChanged(this::highlightRecord);

        addEventHandler(MouseEvent.MOUSE_PRESSED, this::onPressed);
        addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onDragged);
        addEventHandler(MouseEvent.MOUSE_RELEASED, this::onReleased);
        addEventHandler(ScrollEvent.SCROLL, this::onScroll);
        addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            // The view keys act only while the canvas itself owns the focus, so typing into a
            // card's text widget never pans, zooms or jumps the whole board away.
            if (event.getTarget() == NodeCanvasPane.this) {
                if (event.getCode() == KeyCode.SPACE && !event.isControlDown()
                    && !event.isAltDown() && !event.isMetaDown()) {
                    spaceDown = true;
                    return;
                }
                if (event.isControlDown()
                    && (event.getCode() == KeyCode.DIGIT0 || event.getCode() == KeyCode.NUMPAD0)) {
                    resetZoom();
                    event.consume();
                    return;
                }
                if (event.getCode() == KeyCode.F && !event.isControlDown() && !event.isAltDown()
                    && !event.isMetaDown()) {
                    fitToContent();
                    event.consume();
                    return;
                }
            }
            // Both the Delete key and Backspace remove the selection; an edge is removed as its
            // reference, cards as content.
            if (event.getCode() != KeyCode.DELETE && event.getCode() != KeyCode.BACK_SPACE) {
                return;
            }
            if (focusRecord != null) {
                // Per-block link view: the selected edge is a derived reading of the records.
                if (selectedFocusEdge != null) {
                    controller.disconnectReference(selectedFocusEdge.to().record(),
                        selectedFocusEdge.fieldKey(), selectedFocusEdge.sourceName());
                    selectedFocusEdge = null;
                    event.consume();
                }
                return;
            }
            if (selectedRerouteEdgeId != null) {
                // A selected waypoint deletes on its own, before the wire it sits on (P2-9).
                CanvasEdge edge = edgeById(selectedRerouteEdgeId);
                if (edge != null && selectedRerouteIndex >= 0
                    && selectedRerouteIndex < edge.reroutes.size()) {
                    controller.removeReroute(edge, selectedRerouteIndex);
                }
                selectedRerouteEdgeId = null;
                selectedRerouteIndex = -1;
                highlightEdges();
                event.consume();
            } else if (selectedEdgeId != null) {
                CanvasEdge edge = edgeById(selectedEdgeId);
                selectedEdgeId = null;
                if (edge != null) {
                    controller.disconnect(edge);
                }
                event.consume();
            } else if (!selectedNodes.isEmpty()) {
                // Rubber-band selections delete as one batch. Carrier cards (items, liquids,
                // vanilla references) only detach: the record stays registered in the assets
                // workspace. Full content cards delete their record, as before.
                List<ContentRecord> records = new ArrayList<>();
                List<String> detach = new ArrayList<>();
                for (String nodeId : selectedNodes) {
                    if (cardsByNode.get(nodeId) instanceof CarrierCard) {
                        detach.add(nodeId);
                        continue;
                    }
                    ContentRecord record = recordOf(nodeId);
                    if (record != null) {
                        records.add(record);
                    }
                }
                if (!records.isEmpty()) {
                    controller.deleteContents(records);
                }
                if (!detach.isEmpty()) {
                    controller.detachNodes(detach);
                }
            }
        });

        addEventHandler(javafx.scene.input.KeyEvent.KEY_RELEASED, event -> {
            if (event.getCode() == KeyCode.SPACE) {
                spaceDown = false;
            }
        });
        // Losing the focus while space is still held must not leave it "stuck down": the next
        // plain left-drag would pan instead of selecting.
        focusedProperty().addListener((observable, was, now) -> {
            if (!now) {
                spaceDown = false;
            }
        });

        setOnDragOver(this::onDragOver);
        setOnDragDropped(this::onDragDropped);
        refresh();
    }

    // --- content --------------------------------------------------------------------------------

    public void refresh() {
        if (focusRecord != null) {
            refreshFocus();
            // The per-block link view is not the board: the minimap mirrors the board only.
            minimap.clearBlocks();
            return;
        }
        Board board = controller.board();
        // Multi-selection and edge selection must survive a rebuild, or every field edit (which
        // fires a project change) would wipe the user's rubber-band selection out from under them.
        Set<String> restoreNodes = new LinkedHashSet<>(selectedNodes);
        String restoreEdgeId = selectedEdgeId;

        // --- cards, diffed by node id: unchanged cards keep their identity (and hover state) ----
        Map<String, CanvasCard> nextCards = new LinkedHashMap<>();
        if (board != null && controller.project() != null) {
            // one id->record index per refresh: contentById is a linear scan, and the old
            // per-node lookups made every refresh O(nodes x contents)
            Map<String, ContentRecord> recordsById = new HashMap<>();
            for (ContentRecord record : controller.project().contents) {
                recordsById.put(record.id, record);
            }
            for (CanvasNode node : board.nodes) {
                ContentRecord record = node.contentId == null
                    ? null : recordsById.get(node.contentId);
                if (record == null && node.refName == null) {
                    continue;
                }
                CanvasCard card = buildCard(node, record, recordsById);
                if (card == null) {
                    continue;
                }
                card.bindNodeId(node.id);
                card.node().setLayoutX(node.x);
                card.node().setLayoutY(node.y);
                nextCards.put(node.id, card);
            }
        }
        nodeLayer.getChildren().setAll(nextCards.values().stream().map(CanvasCard::node).toList());
        cardsByNode.clear();
        cardsByNode.putAll(nextCards);

        // --- edges, diffed by edge id -----------------------------------------------------------
        Set<String> liveEdgeIds = new LinkedHashSet<>();
        if (board != null && controller.project() != null) {
            for (CanvasEdge edge : board.edges) {
                liveEdgeIds.add(edge.id);
                if (curvesByEdge.containsKey(edge.id)) {
                    Label label = labelsByEdge.get(edge.id);
                    if (label != null) {
                        String text = edge.label == null ? "" : edge.label;
                        if (!text.equals(label.getText())) {
                            label.setText(text);
                            label.setVisible(!text.isEmpty());
                        }
                    }
                    continue;
                }
                Path curve = new Path();
                curve.getStyleClass().add("edge");
                curvesByEdge.put(edge.id, curve);
                edgeLayer.getChildren().add(curve);

                Path pick = new Path();
                pick.getStyleClass().addAll("edge", "edge-pick");
                pick.setMouseTransparent(false);
                pick.setOnMouseClicked(event -> {
                    selectedEdgeId = edge.id;
                    selectedRerouteEdgeId = null;
                    selectedRerouteIndex = -1;
                    highlightEdges();
                    if (event.getClickCount() == 2) {
                        if (event.isControlDown()) {
                            editEdgeAmount(edge);
                        } else {
                            // Double-click the wire to drop a reroute waypoint right under the
                            // cursor (report P2-9); Ctrl+double-click keeps the amount editor.
                            Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
                            Point2D world = toWorld(local.getX(), local.getY());
                            int index = edge.reroutes.size();
                            controller.insertReroute(edge, world.getX(), world.getY());
                            selectedRerouteEdgeId = edge.id;
                            selectedRerouteIndex = index;
                            highlightEdges();
                        }
                    }
                    event.consume();
                });
                pickCurves.put(edge.id, pick);
                edgeLayer.getChildren().add(pick);

                javafx.scene.shape.Polygon arrow = new javafx.scene.shape.Polygon(
                    7, 0, -4, 4.5, -4, -4.5);
                arrow.getStyleClass().add("edge-arrow");
                arrowsByEdge.put(edge.id, arrow);

                Label label = new Label(edge.label == null ? "" : edge.label);
                label.getStyleClass().add("edge-label");
                label.setMouseTransparent(true);
                // The edge layer is unmanaged, so the label would never be sized by a layout pass.
                label.applyCss();
                label.autosize();
                labelsByEdge.put(edge.id, label);
                edgeLayer.getChildren().add(label);
            }
        }
        curvesByEdge.keySet().retainAll(liveEdgeIds);
        pickCurves.keySet().retainAll(liveEdgeIds);
        arrowsByEdge.keySet().retainAll(liveEdgeIds);
        labelsByEdge.keySet().retainAll(liveEdgeIds);
        edgeLayer.getChildren().clear();
        edgeLayer.getChildren().add(pendingCurve);
        arrowLayer.getChildren().clear();
        rerouteDotsByEdge.clear();
        for (String edgeId : liveEdgeIds) {
            edgeLayer.getChildren().addAll(curvesByEdge.get(edgeId), pickCurves.get(edgeId),
                labelsByEdge.get(edgeId));
            arrowLayer.getChildren().add(arrowsByEdge.get(edgeId));
            CanvasEdge edge = edgeById(edgeId);
            if (edge != null && !edge.reroutes.isEmpty()) {
                List<Circle> dots = new ArrayList<>();
                for (int i = 0; i < edge.reroutes.size(); i++) {
                    Circle dot = new Circle(REROUTE_HIT_RADIUS);
                    dot.getStyleClass().add("reroute");
                    // Mouse-transparent: the waypoint lives in the world layer, so the canvas's
                    // own press/drag/release core does the grabbing through rerouteAt instead.
                    dot.setMouseTransparent(true);
                    dots.add(dot);
                    arrowLayer.getChildren().add(dot);
                }
                rerouteDotsByEdge.put(edgeId, dots);
            }
        }
        pendingPort = null;
        pendingCurve.setVisible(false);
        setPendingSnap(null);
        refreshEdges();
        highlightEdges();

        if (board == null) {
            return;
        }
        pan.setX(board.viewport.x);
        pan.setY(board.viewport.y);
        zoom.setX(board.viewport.zoom);
        zoom.setY(board.viewport.zoom);
        world.setVisible(true);
        updateLod();

        // Re-apply the selection that existed before the rebuild (minus deleted nodes/edges).
        highlightRecord(controller.selected());
        for (String nodeId : restoreNodes) {
            CanvasCard card = cardsByNode.get(nodeId);
            if (card != null) {
                selectedNodes.add(nodeId);
                if (!card.node().getStyleClass().contains("selected")) {
                    card.node().getStyleClass().add("selected");
                }
            }
        }
        selectedEdgeId = restoreEdgeId != null
            && board.edges.stream().anyMatch(edge -> edge.id.equals(restoreEdgeId))
            ? restoreEdgeId
            : null;
        highlightEdges();
        rebuildMinimapBlocks();
    }

    /** Builds (or reuses) the card for one node. Items and liquids become lightweight carrier
     * cards - a dropdown over the registered records plus vanilla names - so a mod with dozens of
     * them does not pay for a full content card each. */
    private CanvasCard buildCard(CanvasNode node, ContentRecord record,
        Map<String, ContentRecord> recordsById) {
        if (record != null && (record.type == ContentType.item || record.type == ContentType.liquid)) {
            String displayName = controller.displayName(record);
            if (cardsByNode.get(node.id) instanceof CarrierCard existing
                && existing.matches(displayName, record.id, null)) {
                return existing;
            }
            CarrierCard card = new CarrierCard(record.type, controller.typeName(record.type),
                displayName, carrierCandidates(record.type), record.id, null,
                (newContentId, newRefName) ->
                    controller.rebindCarrier(node.id, newContentId, newRefName));
            card.setLod(zoom.getX());
            return card;
        }
        if (record == null) {
            // a carrier bound straight to a vanilla (or otherwise record-less) name
            ContentType type;
            try {
                type = ContentType.valueOf(node.refType == null ? "item" : node.refType);
            } catch (IllegalArgumentException unknown) {
                type = ContentType.item;
            }
            String refName = node.refName == null ? "" : node.refName;
            if (cardsByNode.get(node.id) instanceof CarrierCard existing
                && existing.matches(refName, null, node.refName)
                && existing.showsType(type)) {
                return existing;
            }
            CarrierCard card = new CarrierCard(type, controller.typeName(type), refName,
                carrierCandidates(type), null, node.refName,
                (newContentId, newRefName) ->
                    controller.rebindCarrier(node.id, newContentId, newRefName));
            return card;
        }
        List<FieldDef> widgets = widgetFields(record);
        List<String> widgetNames = widgets.stream().map(FieldDef::name).toList();
        List<String> ports = inputPorts(record);
        String displayName = controller.displayName(record);
        String subtitle = controller.subtitleOf(record);
        if (cardsByNode.get(node.id) instanceof ContentCard card) {
            // A card whose widget owns the focus is never rebuilt out from under the caret, even
            // when this edit changed its signature (a first value makes a widget row appear).
            if (card.isEditing()) {
                return card;
            }
            if (card.matches(displayName, subtitle, record.isInline(), record.patch,
                widgetNames, ports)) {
                return card;
            }
        }
        ContentCard card = new ContentCard(controller, record, widgets, ports);
        card.setOnEditEnd(this::refresh);
        card.setLod(zoom.getX());
        return card;
    }

    /** Fields a card renders as components: the record's set fields, key fields first. */
    private List<FieldDef> widgetFields(ContentRecord record) {
        List<FieldDef> defs = controller.registry().fieldsOf(controller.classNameOf(record));
        List<FieldDef> ordered = new ArrayList<>();
        for (String name : PREVIEW_PRIORITY) {
            defs.stream().filter(def -> def.name().equals(name)).findFirst().ifPresent(ordered::add);
        }
        for (FieldDef def : defs) {
            if (!ordered.contains(def) && record.fields.get(def.name()) != null
                && (def.type() == FieldType.bool || def.type() == FieldType.number
                || def.type() == FieldType.text || def.type() == FieldType.sprite)) {
                ordered.add(def);
            }
        }
        ordered.removeIf(def -> EXCLUDED_WIDGET_FIELDS.contains(def.name()));
        return ordered.stream().limit(ContentCard.MAX_WIDGET_ROWS).toList();
    }

    private static final List<String> EXCLUDED_WIDGET_FIELDS =
        List.of("type", "name", "description", "details", "credit");

    /** Dropdown candidates for one carrier type: this mod's registered records first, then the
     * vanilla names. Labels must be unique - a mod record shadows a vanilla name of the same
     * label, which matches how the game resolves references. */
    private List<CarrierCard.Candidate> carrierCandidates(ContentType type) {
        List<CarrierCard.Candidate> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (ContentRecord record : controller.project().contents) {
            if (record.isInline() || record.type != type) {
                continue;
            }
            String label = controller.displayName(record);
            if (seen.add(label)) {
                out.add(new CarrierCard.Candidate(label, record.id, null));
            }
        }
        for (String vanilla : controller.registry().vanillaNames(type)) {
            if (seen.add(vanilla)) {
                out.add(new CarrierCard.Candidate(vanilla, null, vanilla));
            }
        }
        return out;
    }

    /** Reference fields of the record's class, shown as input ports (the card caps how many show).
     * Structured multi-reference groups (consumes, ammo maps, unit requirements, plans) come
     * first, so the ports the user asked for are never buried behind the "+n" overflow. */
    private List<String> inputPorts(ContentRecord record) {
        List<String> structPorts = new ArrayList<>();
        List<String> stackPorts = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        String className = controller.classNameOf(record);
        for (FieldDef field : controller.registry().fieldsOf(className)) {
            if (!field.isReference()) {
                continue;
            }
            if (field.type() == FieldType.struct) {
                structPorts.add(field.name());
            } else if (field.type().isStack()) {
                stackPorts.add(field.name());
            } else {
                rest.add(field.name());
            }
        }
        structPorts.addAll(stackPorts);
        structPorts.addAll(rest);
        return structPorts;
    }

    /** Recomputes every edge path from the current card positions, port anchors, reroute
     * waypoints and the board's routing style (report P2-9). */
    public void refreshEdges() {
        Board board = controller.board();
        if (board == null) {
            return;
        }
        EdgePathMath.Style style = EdgePathMath.Style.parse(board.edgeStyle);
        for (CanvasEdge edge : board.edges) {
            Point2D start = portWorld(edge.from, null);
            Point2D end = portWorld(edge.to, edge.fieldKey);
            Path curve = curvesByEdge.get(edge.id);
            if (curve == null) {
                continue;
            }
            applyEdgePath(curve, style, start, end, edge.reroutes);
            Path pickCurve = pickCurves.get(edge.id);
            if (pickCurve != null) {
                applyEdgePath(pickCurve, style, start, end, edge.reroutes);
            }

            Label label = labelsByEdge.get(edge.id);
            if (label != null) {
                double midX = (start.getX() + end.getX()) / 2;
                double midY = (start.getY() + end.getY()) / 2;
                label.setLayoutX(midX - 20);
                label.setLayoutY(midY - 9);
            }

            javafx.scene.shape.Polygon arrow = arrowsByEdge.get(edge.id);
            if (arrow != null) {
                // Head points into the target port; angle from the path's final direction.
                double angle = EdgePathMath.arrowAngle(style, start.getX(), start.getY(),
                    end.getX(), end.getY(), edge.reroutes);
                double tipX = end.getX() - Math.cos(angle) * 7;
                double tipY = end.getY() - Math.sin(angle) * 7;
                arrow.setLayoutX(tipX);
                arrow.setLayoutY(tipY);
                arrow.setRotate(Math.toDegrees(angle));
            }

            List<Circle> dots = rerouteDotsByEdge.get(edge.id);
            if (dots != null) {
                for (int i = 0; i < dots.size() && i < edge.reroutes.size(); i++) {
                    double[] point = edge.reroutes.get(i);
                    if (point != null && point.length >= 2) {
                        dots.get(i).setLayoutX(point[0]);
                        dots.get(i).setLayoutY(point[1]);
                    }
                }
            }
        }
    }

    /** Rebuilds one edge's path elements for the routing style: stacked bezier legs through the
     * waypoints, or the orthogonal / straight polyline from {@link EdgePathMath}. */
    private static void applyEdgePath(Path path, EdgePathMath.Style style, Point2D start,
        Point2D end, List<double[]> reroutes) {
        path.getElements().clear();
        path.getElements().add(new MoveTo(start.getX(), start.getY()));
        if (style == EdgePathMath.Style.BEZIER) {
            double prevX = start.getX();
            double prevY = start.getY();
            for (double[] point : EdgePathMath.viaPoints(reroutes)) {
                appendBezierLeg(path, prevX, prevY, point[0], point[1]);
                prevX = point[0];
                prevY = point[1];
            }
            appendBezierLeg(path, prevX, prevY, end.getX(), end.getY());
            return;
        }
        List<double[]> points = style == EdgePathMath.Style.ORTHOGONAL
            ? EdgePathMath.orthogonalPoints(start.getX(), start.getY(), end.getX(), end.getY(),
                reroutes)
            : EdgePathMath.straightPoints(start.getX(), start.getY(), end.getX(), end.getY(),
                reroutes);
        for (int i = 1; i < points.size(); i++) {
            path.getElements().add(new LineTo(points.get(i)[0], points.get(i)[1]));
        }
    }

    private static void appendBezierLeg(Path path, double x1, double y1, double x2, double y2) {
        double[] segment = EdgePathMath.bezierSegment(x1, y1, x2, y2);
        path.getElements().add(new CubicCurveTo(segment[2], segment[3], segment[4], segment[5],
            segment[6], segment[7]));
    }

    /** Shapes the pending-connection rubber band, which stays a plain bezier in every style. */
    private static void setCurve(CubicCurve curve, Point2D start, Point2D end) {
        double midX = start.getX() + (end.getX() - start.getX()) * 0.5;
        curve.setStartX(start.getX());
        curve.setStartY(start.getY());
        curve.setControlX1(midX);
        curve.setControlY1(start.getY());
        curve.setControlX2(midX);
        curve.setControlY2(end.getY());
        curve.setEndX(end.getX());
        curve.setEndY(end.getY());
    }

    /** The board's current routing style (test seam and toolbar label). */
    public EdgePathMath.Style edgeStyle() {
        Board board = controller.board();
        return EdgePathMath.Style.parse(board == null ? null : board.edgeStyle);
    }

    /**
     * Toolbar cycle bezier -&gt; orthogonal -&gt; straight. Like the viewport, the choice is an
     * authoring preference kept in the sidecar: it repaints the wires but never marks the project
     * dirty.
     */
    public void cycleEdgeStyle() {
        Board board = controller.board();
        if (board == null) {
            return;
        }
        board.edgeStyle = EdgePathMath.Style.parse(board.edgeStyle).next().key();
        refreshEdges();
    }

    /** Highlights the card of a record. */
    public void highlightRecord(ContentRecord record) {
        selectedNodes.clear();
        cardsByNode.values().forEach(card -> card.node().getStyleClass().remove("selected"));
        if (record == null || controller.board() == null) {
            return;
        }
        controller.board().nodes.stream()
            .filter(node -> record.id.equals(node.contentId))
            .findFirst()
            .ifPresent(node -> {
                selectedNodes.add(node.id);
                CanvasCard card = cardsByNode.get(node.id);
                if (card != null) {
                    card.node().getStyleClass().add("selected");
                }
            });
    }

    /** Pans the view so the card of the given record sits in the canvas centre (search jumps). */
    public void revealRecord(ContentRecord record) {
        if (record == null || controller.board() == null) {
            return;
        }
        controller.board().nodes.stream()
            .filter(node -> record.id.equals(node.contentId))
            .findFirst()
            .ifPresent(node -> {
                if (zoom.getX() < 0.75) {
                    zoom.setX(0.75);
                    zoom.setY(0.75);
                }
                double scale = zoom.getX() == 0 ? 1 : zoom.getX();
                CanvasCard card = cardsByNode.get(node.id);
                double cardHeight = card != null ? card.cardHeight() : ContentCard.HEIGHT;
                double cardWidth = card != null ? card.cardWidth() : ContentCard.WIDTH;
                pan.setX(getWidth() / 2 - (node.x + cardWidth / 2) * scale);
                pan.setY(getHeight() / 2 - (node.y + cardHeight / 2) * scale);
                updateLod();
                highlightRecord(record);
                refreshEdges();
                rememberViewport();
            });
    }

    private void highlightEdges() {
        curvesByEdge.forEach((edgeId, curve) -> {
            curve.getStyleClass().remove("edge-selected");
            if (edgeId.equals(selectedEdgeId)) {
                curve.getStyleClass().add("edge-selected");
            }
        });
        arrowsByEdge.forEach((edgeId, arrow) -> {
            arrow.getStyleClass().remove("edge-arrow-selected");
            if (edgeId.equals(selectedEdgeId)) {
                arrow.getStyleClass().add("edge-arrow-selected");
            }
        });
        rerouteDotsByEdge.forEach((edgeId, dots) -> {
            for (int i = 0; i < dots.size(); i++) {
                Circle dot = dots.get(i);
                dot.getStyleClass().remove("reroute-selected");
                if (edgeId.equals(selectedRerouteEdgeId) && i == selectedRerouteIndex) {
                    dot.getStyleClass().add("reroute-selected");
                }
            }
        });
    }

    /**
     * Double-clicking a connection opens a small editor for how many of the referenced item it
     * carries. Only stack references have an amount; a scalar content reference is left alone.
     */
    private void editEdgeAmount(CanvasEdge edge) {
        int current = controller.edgeAmount(edge);
        if (current < 0) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(Integer.toString(current));
        dialog.setTitle(Messages.t("edge.amount.title"));
        dialog.setHeaderText(Messages.t("edge.amount.header",
            edge.label == null || edge.label.isEmpty() ? "?" : edge.label));
        dialog.setContentText(Messages.t("edge.amount.label"));
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.showAndWait().ifPresent(text -> {
            try {
                int amount = Integer.parseInt(text.trim());
                if (amount < 1) {
                    controller.log("amount must be a positive whole number");
                } else {
                    controller.setEdgeAmount(edge, amount);
                }
            } catch (NumberFormatException notANumber) {
                controller.log("'" + text + "' is not a whole number, amount unchanged");
            }
        });
    }

    /** Moves the view so every card is visible, in one step. */
    public void fitToContent() {
        if (cardsByNode.isEmpty() || getWidth() <= 0) {
            return;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (CanvasCard card : cardsByNode.values()) {
            minX = Math.min(minX, card.node().getLayoutX());
            minY = Math.min(minY, card.node().getLayoutY());
            maxX = Math.max(maxX, card.node().getLayoutX() + card.cardWidth());
            maxY = Math.max(maxY, card.node().getLayoutY() + card.cardHeight());
        }
        double contentWidth = Math.max(1, maxX - minX);
        double contentHeight = Math.max(1, maxY - minY);
        double scale = Math.min(getWidth() / (contentWidth + 120), getHeight() / (contentHeight + 120));
        scale = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, scale));
        zoom.setX(scale);
        zoom.setY(scale);
        pan.setX((getWidth() - contentWidth * scale) / 2 - minX * scale);
        pan.setY((getHeight() - contentHeight * scale) / 2 - minY * scale);
        updateLod();
        rememberViewport();
    }

    /** The grid buffer's pixel size (test hook): it must track the viewport, never the world. */
    public double gridBufferWidth() {
        return gridCanvas.getWidth();
    }

    public double gridBufferHeight() {
        return gridCanvas.getHeight();
    }

    /** Ctrl+0: back to 100%, keeping the world point at the viewport centre under the centre. */
    public void resetZoom() {
        double scale = zoom.getX();
        if (scale <= 0 || scale == 1) {
            return;
        }
        double centerX = getWidth() / 2;
        double centerY = getHeight() / 2;
        Point2D anchor = toWorld(centerX, centerY);
        zoom.setX(1);
        zoom.setY(1);
        pan.setX(centerX - anchor.getX());
        pan.setY(centerY - anchor.getY());
        updateLod();
        rememberViewport();
    }

    /** Current zoom / pan (test hooks for the view keys). */
    double zoomValue() {
        return zoom.getX();
    }

    double panXValue() {
        return pan.getX();
    }

    double panYValue() {
        return pan.getY();
    }

    /** Applies the level of detail that matches the current zoom to every card. */
    private void updateLod() {
        double scale = zoom.getX();
        cardsByNode.values().forEach(card -> card.setLod(scale));
        // LOD (report P1-7): below the threshold the world root carries the .lod class and CSS
        // drops the header titles; colour blocks and the selection border stay. Hit-testing,
        // ports and the view keys are untouched - this is purely a render-time style switch.
        if (MinimapMath.lodActive(scale, LOD_ZOOM)) {
            if (!world.getStyleClass().contains("lod")) {
                world.getStyleClass().add("lod");
            }
        } else {
            world.getStyleClass().remove("lod");
        }
    }

    // --- minimap ---------------------------------------------------------------------------------

    /** Reprojects every card into the minimap; a no-op while the map is hidden. */
    private void rebuildMinimapBlocks() {
        if (!minimap.isVisible()) {
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (CanvasCard card : cardsByNode.values()) {
            minX = Math.min(minX, card.node().getLayoutX());
            minY = Math.min(minY, card.node().getLayoutY());
            maxX = Math.max(maxX, card.node().getLayoutX() + card.cardWidth());
            maxY = Math.max(maxY, card.node().getLayoutY() + card.cardHeight());
        }
        if (cardsByNode.isEmpty()) {
            minX = minY = 0;
            maxX = maxY = 1;
        }
        minimap.beginBlocks(MinimapMath.fit(minX, minY, maxX, maxY,
            MinimapOverlay.WIDTH, MinimapOverlay.HEIGHT, MinimapOverlay.MARGIN));
        for (CanvasCard card : cardsByNode.values()) {
            minimap.addBlock(card.node().getLayoutX(), card.node().getLayoutY(),
                card.cardWidth(), card.cardHeight(), null, card.cardType().color());
        }
        updateMinimapViewport();
    }

    /** Keeps the viewport frame in step with pan, zoom and view size. */
    private void updateMinimapViewport() {
        if (!minimap.isVisible()) {
            return;
        }
        minimap.updateViewport(pan.getX(), pan.getY(), zoom.getX() == 0 ? 1 : zoom.getX(),
            getWidth(), getHeight());
    }

    /** The workspace toolbar's toggle action: show or hide the corner minimap. */
    public void setMinimapVisible(boolean visible) {
        minimap.setVisible(visible);
        if (visible) {
            rebuildMinimapBlocks();
        }
    }

    /** Minimap seams (report P1-6): visibility, frame rect and the active projection. */
    boolean minimapVisible() {
        return minimap.isVisible();
    }

    double[] minimapViewportRect() {
        return minimap.viewportRect();
    }

    double[] minimapProjection() {
        return minimap.projectionValues();
    }

    /** World coordinates of the pane centre, for placing new cards where the user can see them. */
    public Point2D viewportCenterWorld() {
        double scale = zoom.getX() == 0 ? 1 : zoom.getX();
        return new Point2D((getWidth() / 2 - pan.getX()) / scale,
            (getHeight() / 2 - pan.getY()) / scale);
    }

    /** Growing offset so several "Add" clicks do not stack cards exactly on top of each other. */
    public double nextCascade() {
        cascade = (cascade + 44) % 220;
        return cascade;
    }

    /** Coalesces grid redraws to at most one per pulse: pans fire several transform updates. */
    private void scheduleGridRedraw() {
        if (gridRedrawPending) {
            return;
        }
        gridRedrawPending = true;
        Platform.runLater(() -> {
            gridRedrawPending = false;
            redrawGrid();
        });
    }

    /** Repaints the dot grid (24px minor, 120px major world spacing) as a viewport-sized overlay. */
    private void redrawGrid() {
        double viewW = getWidth();
        double viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) {
            return;
        }
        if (gridCanvas.getWidth() != viewW) {
            gridCanvas.setWidth(viewW);
        }
        if (gridCanvas.getHeight() != viewH) {
            gridCanvas.setHeight(viewH);
        }
        GraphicsContext context = gridCanvas.getGraphicsContext2D();
        context.clearRect(0, 0, viewW, viewH);
        if (!world.isVisible()) {
            return;
        }
        double scale = zoom.getX() == 0 ? 1 : zoom.getX();
        drawLattice(context, viewW, viewH, 24, scale, 13, 0.06);
        drawLattice(context, viewW, viewH, 120, scale, 9, 0.13);
    }

    /** Dots sit wherever a world lattice multiple projects onto the screen; a lattice that
     * shrinks below its minimum pixel spacing is skipped, so the cost depends only on the
     * viewport, never on how far the canvas is zoomed out. */
    private void drawLattice(GraphicsContext context, double viewW, double viewH,
        double spacingWorld, double scale, double minSpacingPx, double alpha) {
        double spacing = spacingWorld * scale;
        if (spacing < minSpacingPx) {
            return;
        }
        context.setFill(Color.web("#ffffff", alpha));
        double startX = ((pan.getX() % spacing) + spacing) % spacing;
        double startY = ((pan.getY() % spacing) + spacing) % spacing;
        for (double x = startX; x < viewW; x += spacing) {
            for (double y = startY; y < viewH; y += spacing) {
                context.fillRect(x - 0.75, y - 0.75, 1.5, 1.5);
            }
        }
    }

    // --- input layer (coordinates only) ---------------------------------------------------------

    private void onPressed(MouseEvent event) {
        // Clicking an edge's pick path must not clear the selection or start a rubber band.
        if (event.getTarget() instanceof javafx.scene.Node node
            && node.getStyleClass().contains("edge-pick")) {
            return;
        }
        Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
        pressAt(local.getX(), local.getY(), event.getButton());
    }

    private void onDragged(MouseEvent event) {
        Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
        dragTo(local.getX(), local.getY());
    }

    private void onReleased(MouseEvent event) {
        Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
        releaseAt(local.getX(), local.getY());
    }

    private void onScroll(ScrollEvent event) {
        Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
        if (event.isControlDown()) {
            double factor = event.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
            double current = zoom.getX();
            double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, current * factor));
            if (next == current) {
                return;
            }
            Point2D worldPoint = toWorld(local.getX(), local.getY());
            zoom.setX(next);
            zoom.setY(next);
            pan.setX(local.getX() - worldPoint.getX() * next);
            pan.setY(local.getY() - worldPoint.getY() * next);
            updateLod();
        } else {
            pan.setX(pan.getX() + event.getDeltaX());
            pan.setY(pan.getY() + event.getDeltaY());
        }
        rememberViewport();
        event.consume();
    }

    // --- interaction core (pane-local coordinates; unit-testable) -------------------------------

    /** Mouse press at pane-local coordinates. */
    public void pressAt(double localX, double localY, MouseButton button) {
        requestFocus();
        pressLocal = new Point2D(localX, localY);
        pressWorld = toWorld(localX, localY);

        if (button == MouseButton.MIDDLE || button == MouseButton.SECONDARY
            || (button == MouseButton.PRIMARY && spaceDown)) {
            panning = true;
            return;
        }
        // A primary press always ends a pan gesture, whatever else it starts.
        panning = false;

        ContentCard.Port port = portAt(pressWorld.getX(), pressWorld.getY());
        if (port != null) {
            pendingPort = port;
            Point2D anchor = portWorld(port.nodeId, port.fieldKey);
            pendingCurve.setVisible(true);
            setCurve(pendingCurve, anchor, anchor);
            return;
        }

        // A reroute waypoint (report P2-9) grabs next: press on the dot, drag to reshape the wire.
        RerouteHit hit = rerouteAt(pressWorld.getX(), pressWorld.getY());
        if (hit != null) {
            CanvasEdge edge = edgeById(hit.edgeId());
            if (edge != null && hit.index() < edge.reroutes.size()) {
                double[] point = edge.reroutes.get(hit.index());
                dragRerouteEdgeId = hit.edgeId();
                dragRerouteIndex = hit.index();
                dragRerouteBefore = new double[] {point[0], point[1]};
                selectedEdgeId = null;
                selectedRerouteEdgeId = hit.edgeId();
                selectedRerouteIndex = hit.index();
                highlightEdges();
            }
            return;
        }
        if (selectedRerouteEdgeId != null) {
            selectedRerouteEdgeId = null;
            selectedRerouteIndex = -1;
            highlightEdges();
        }

        String nodeId = nodeAt(pressWorld.getX(), pressWorld.getY());
        if (nodeId == null) {
            cancelPendingConnection();
            selectedEdgeId = null;
            highlightEdges();
            selectedNodes.clear();
            cardsByNode.values().forEach(card -> card.node().getStyleClass().remove("selected"));
            rubberBanding = true;
            rubber.setX(localX);
            rubber.setY(localY);
            rubber.setWidth(0);
            rubber.setHeight(0);
            rubber.setVisible(true);
            // Nothing is selected any more: the inspector and the preview panes must hear about it,
            // or they keep showing the record the user just deselected.
            if (controller.selected() != null) {
                controller.select(null);
            }
            return;
        }

        dragNode = nodeId;
        dragOrigins.clear();
        if (!selectedNodes.contains(nodeId)) {
            selectedNodes.clear();
            cardsByNode.values().forEach(card -> card.node().getStyleClass().remove("selected"));
            selectedNodes.add(nodeId);
            cardsByNode.get(nodeId).node().getStyleClass().add("selected");
            // Selecting fires the highlight callback, which rebuilds the multi-selection from
            // the record - so only select when the pressed card was not already part of a
            // rubber-band multi-selection, or the whole selection would collapse to one card.
            ContentRecord record = recordOf(nodeId);
            if (record != null) {
                controller.select(record);
            }
        }
        for (String selected : selectedNodes) {
            CanvasCard card = cardsByNode.get(selected);
            if (card != null) {
                dragOrigins.put(selected, new Point2D(card.node().getLayoutX(),
                    card.node().getLayoutY()));
            }
        }
    }

    /** Aborts an in-progress connection drag, if any. */
    private void cancelPendingConnection() {
        if (pendingPort != null) {
            pendingPort = null;
            pendingCurve.setVisible(false);
        }
        setPendingSnap(null);
    }

    /** Mouse drag to pane-local coordinates. */
    public void dragTo(double localX, double localY) {
        Point2D world = toWorld(localX, localY);
        if (panning) {
            pan.setX(pan.getX() + (localX - pressLocal.getX()));
            pan.setY(pan.getY() + (localY - pressLocal.getY()));
            pressLocal = new Point2D(localX, localY);
            rememberViewport();
            return;
        }
        if (dragRerouteEdgeId != null) {
            CanvasEdge edge = edgeById(dragRerouteEdgeId);
            if (edge != null && dragRerouteIndex >= 0
                && dragRerouteIndex < edge.reroutes.size()) {
                edge.reroutes.set(dragRerouteIndex, new double[] {world.getX(), world.getY()});
                refreshEdges();
            }
            return;
        }
        if (pendingPort != null) {
            Point2D anchor = portWorld(pendingPort.nodeId, pendingPort.fieldKey);
            ContentCard.Port snap = nearestCompatiblePort(world.getX(), world.getY(), pendingPort);
            setPendingSnap(snap);
            Point2D end = snap == null ? world : portWorld(snap.nodeId, snap.fieldKey);
            setCurve(pendingCurve, anchor, end);
            return;
        }
        if (rubberBanding) {
            rubber.setX(Math.min(pressLocal.getX(), localX));
            rubber.setY(Math.min(pressLocal.getY(), localY));
            rubber.setWidth(Math.abs(localX - pressLocal.getX()));
            rubber.setHeight(Math.abs(localY - pressLocal.getY()));
            return;
        }
        if (dragNode != null) {
            double deltaX = world.getX() - pressWorld.getX();
            double deltaY = world.getY() - pressWorld.getY();
            dragOrigins.forEach((nodeId, origin) -> {
                CanvasCard card = cardsByNode.get(nodeId);
                CanvasNode node = nodeById(nodeId);
                if (card == null || node == null) {
                    return;
                }
                node.x = origin.getX() + deltaX;
                node.y = origin.getY() + deltaY;
                card.node().setLayoutX(node.x);
                card.node().setLayoutY(node.y);
            });
            refreshEdges();
        }
    }

    /** Mouse release at pane-local coordinates. */
    public void releaseAt(double localX, double localY) {
        Point2D world = toWorld(localX, localY);

        if (dragRerouteEdgeId != null) {
            CanvasEdge edge = edgeById(dragRerouteEdgeId);
            if (edge != null && dragRerouteIndex >= 0
                && dragRerouteIndex < edge.reroutes.size()) {
                controller.commitRerouteMove(edge, dragRerouteIndex, dragRerouteBefore,
                    edge.reroutes.get(dragRerouteIndex));
            }
            dragRerouteEdgeId = null;
            dragRerouteIndex = -1;
            dragRerouteBefore = null;
            return;
        }

        if (pendingPort != null) {
            ContentCard.Port target = portAt(world.getX(), world.getY());
            if (target == null) {
                target = nearestCompatiblePort(world.getX(), world.getY(), pendingPort);
            }
            if (target != null && target != pendingPort) {
                ContentCard.Port out = pendingPort.fieldKey == null ? pendingPort : target;
                ContentCard.Port in = pendingPort.fieldKey == null ? target : pendingPort;
                if (out.fieldKey == null && in.fieldKey != null
                    && !out.nodeId.equals(in.nodeId)) {
                    controller.connect(out.nodeId, in.nodeId, in.fieldKey);
                } else {
                    controller.log("a connection needs one ID port and one reference port");
                }
            }
            pendingPort = null;
            setPendingSnap(null);
            pendingCurve.setVisible(false);
            return;
        }

        if (rubberBanding) {
            rubberBanding = false;
            rubber.setVisible(false);
            selectInRubber();
        }
        if (dragNode != null && !dragOrigins.isEmpty()) {
            commitMove();
        }
        panning = false;
        dragNode = null;
        dragOrigins.clear();
    }

    private void commitMove() {
        Map<String, double[]> after = new LinkedHashMap<>();
        for (String nodeId : dragOrigins.keySet()) {
            CanvasNode node = nodeById(nodeId);
            if (node != null) {
                after.put(nodeId, new double[] {node.x, node.y});
            }
        }
        // A click on a card is a press and release without a drag: pushing that as a move would put
        // a no-op step on the undo stack and mark the project dirty.
        if (!movedSince(dragOrigins, after)) {
            return;
        }
        controller.commitMoves(dragOrigins, after);
    }

    /** True when at least one dragged card ended up somewhere else than where it was pressed. */
    private static boolean movedSince(Map<String, Point2D> origins, Map<String, double[]> after) {
        for (Map.Entry<String, double[]> entry : after.entrySet()) {
            Point2D origin = origins.get(entry.getKey());
            double[] position = entry.getValue();
            if (origin != null && position != null
                && (origin.getX() != position[0] || origin.getY() != position[1])) {
                return true;
            }
        }
        return false;
    }

    private void selectInRubber() {
        if (rubber.getWidth() < 3 && rubber.getHeight() < 3) {
            return;
        }
        Point2D topLeft = toWorld(rubber.getX(), rubber.getY());
        Point2D bottomRight = toWorld(rubber.getX() + rubber.getWidth(),
            rubber.getY() + rubber.getHeight());
        for (Map.Entry<String, CanvasCard> entry : cardsByNode.entrySet()) {
            CanvasCard card = entry.getValue();
            boolean inside = card.node().getLayoutX() + card.cardWidth() >= topLeft.getX()
                && card.node().getLayoutX() <= bottomRight.getX()
                && card.node().getLayoutY() + card.cardHeight() >= topLeft.getY()
                && card.node().getLayoutY() <= bottomRight.getY();
            if (inside) {
                selectedNodes.add(entry.getKey());
                card.node().getStyleClass().add("selected");
            }
        }
        // The panes on the other side must follow the rubber band too: exactly one card selects its
        // record, anything else (none, or a multi-selection) means no single record is selected.
        ContentRecord single = selectedNodes.size() == 1
            ? recordOf(selectedNodes.iterator().next())
            : null;
        if (single != controller.selected()) {
            controller.select(single);
        }
    }

    // --- palette drag & drop --------------------------------------------------------------------

    private void onDragOver(DragEvent event) {
        if (event.getDragboard().hasContent(CONTENT_TYPE_FORMAT) && controller.project() != null) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void onDragDropped(DragEvent event) {
        Dragboard board = event.getDragboard();
        if (board.hasContent(CONTENT_TYPE_FORMAT) && controller.project() != null) {
            try {
                ContentType type = ContentType.valueOf(
                    String.valueOf(board.getContent(CONTENT_TYPE_FORMAT)));
                Point2D local = sceneToLocal(event.getSceneX(), event.getSceneY());
                Point2D world = toWorld(local.getX(), local.getY());
                controller.addContent(type, world.getX() - ContentCard.WIDTH / 2,
                    world.getY() - ContentCard.HEIGHT / 2);
                event.setDropCompleted(true);
            } catch (IllegalArgumentException badClipboard) {
                // Foreign or stale clipboard data: ignore the drop instead of crashing.
            }
        }
        event.consume();
    }

    // --- geometry helpers -----------------------------------------------------------------------

    /** Converts pane-local coordinates to world (canvas) coordinates. */
    private Point2D toWorld(double localX, double localY) {
        double scale = zoom.getX() == 0 ? 1 : zoom.getX();
        return new Point2D((localX - pan.getX()) / scale, (localY - pan.getY()) / scale);
    }

    /** World position of a node's port. {@code fieldKey == null} is the ID port. */
    public Point2D portWorld(String nodeId, String fieldKey) {
        CanvasCard card = cardsByNode.get(nodeId);
        if (card == null) {
            return Point2D.ZERO;
        }
        Point2D anchor = card.portAnchor(fieldKey);
        if (anchor == null) {
            anchor = fieldKey == null
                ? new Point2D(ContentCard.WIDTH, ContentCard.HEIGHT / 2)
                : new Point2D(0, ContentCard.HEIGHT / 2);
        }
        return new Point2D(card.node().getLayoutX() + anchor.getX(),
            card.node().getLayoutY() + anchor.getY());
    }

    private ContentCard.Port portAt(double worldX, double worldY) {
        ContentCard.Port best = null;
        double bestDistance = PORT_HIT_RADIUS * PORT_HIT_RADIUS;
        for (CanvasCard card : cardsByNode.values()) {
            for (ContentCard.Port port : card.ports()) {
                double dx = card.node().getLayoutX() + port.x - worldX;
                double dy = card.node().getLayoutY() + port.y - worldY;
                double distance = dx * dx + dy * dy;
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    best = port;
                }
            }
        }
        return best;
    }

    /**
     * The nearest port the pending drag may legally connect to: opposite kind (ID port versus
     * reference port), on another card, within {@link #PORT_SNAP_RADIUS} world pixels. Null when
     * nothing compatible is close - the drag then follows the cursor bare, exactly as before.
     */
    ContentCard.Port nearestCompatiblePort(double worldX, double worldY, ContentCard.Port from) {
        ContentCard.Port best = null;
        double bestDistance = PORT_SNAP_RADIUS;
        for (CanvasCard card : cardsByNode.values()) {
            for (ContentCard.Port port : card.ports()) {
                if (port.nodeId.equals(from.nodeId)
                    || (port.fieldKey == null) == (from.fieldKey == null)) {
                    continue;
                }
                double distance = Math.hypot(card.node().getLayoutX() + port.x - worldX,
                    card.node().getLayoutY() + port.y - worldY);
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    best = port;
                }
            }
        }
        return best;
    }

    /** Moves the snap highlight to another port circle. */
    private void setPendingSnap(ContentCard.Port snap) {
        if (snap == pendingSnap) {
            return;
        }
        if (pendingSnap != null) {
            pendingSnap.circle.getStyleClass().remove("port-snap");
        }
        pendingSnap = snap;
        if (snap != null) {
            snap.circle.getStyleClass().add("port-snap");
        }
    }

    /** The port the pending drag currently snaps to (test seam). */
    ContentCard.Port pendingSnapPort() {
        return pendingSnap;
    }

    /** The port the pending drag started from, or null (test seam). */
    ContentCard.Port pendingSourcePort() {
        return pendingPort;
    }

    private String nodeAt(double worldX, double worldY) {
        for (Map.Entry<String, CanvasCard> entry : cardsByNode.entrySet()) {
            CanvasCard card = entry.getValue();
            if (worldX >= card.node().getLayoutX()
                && worldX <= card.node().getLayoutX() + card.cardWidth()
                && worldY >= card.node().getLayoutY()
                && worldY <= card.node().getLayoutY() + card.cardHeight()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** One reroute waypoint under a world point (report P2-9). */
    private record RerouteHit(String edgeId, int index) {
    }

    /** The waypoint the press grabs, checked after the ports so a dot near a port never wins. */
    private RerouteHit rerouteAt(double worldX, double worldY) {
        Board board = controller.board();
        if (board == null) {
            return null;
        }
        for (CanvasEdge edge : board.edges) {
            int index = EdgePathMath.hitReroute(worldX, worldY, edge.reroutes, REROUTE_HIT_RADIUS);
            if (index >= 0) {
                return new RerouteHit(edge.id, index);
            }
        }
        return null;
    }

    private CanvasNode nodeById(String nodeId) {
        if (controller.board() == null) {
            return null;
        }
        return controller.board().nodes.stream()
            .filter(node -> node.id.equals(nodeId))
            .findFirst().orElse(null);
    }

    private CanvasEdge edgeById(String edgeId) {
        if (controller.board() == null) {
            return null;
        }
        return controller.board().edges.stream()
            .filter(edge -> edge.id.equals(edgeId))
            .findFirst().orElse(null);
    }

    private ContentRecord recordOf(String nodeId) {
        CanvasNode node = nodeById(nodeId);
        return node == null ? null : controller.project().contentById(node.contentId);
    }

    private void rememberViewport() {
        Board board = controller.board();
        if (board != null) {
            board.viewport.x = pan.getX();
            board.viewport.y = pan.getY();
            board.viewport.zoom = zoom.getX();
        }
    }

    /** Fields shown before everything else on a card: what you reach for when reading a build. */
    private static final List<String> PREVIEW_PRIORITY = List.of(
        "color", "health", "damage", "requirements", "research", "reload", "range", "speed",
        "powerCapacity", "itemCapacity", "liquidCapacity", "size", "category");

    /** Field values to show on the card, informative without opening the inspector. Key fields
     * come first regardless of schema order; the card itself caps the row count and ellipsizes. */
    private List<String> preview(ContentRecord record) {
        List<FieldDef> defs = controller.registry().fieldsOf(controller.classNameOf(record));
        List<FieldDef> ordered = new ArrayList<>();
        for (String name : PREVIEW_PRIORITY) {
            defs.stream().filter(def -> def.name().equals(name)).findFirst().ifPresent(ordered::add);
        }
        for (FieldDef def : defs) {
            if (!ordered.contains(def)) {
                ordered.add(def);
            }
        }
        List<String> lines = new ArrayList<>();
        for (FieldDef field : ordered) {
            var value = record.fields.get(field.name());
            if (value == null || value.isJsonNull()) {
                continue;
            }
            // Strip JSON quoting noise; the label ellipsizes and the tooltip can show it all.
            String text = value.isJsonPrimitive()
                ? value.getAsString()
                : value.toString().replace("\"", "");
            lines.add(field.name() + ": " + text);
            if (lines.size() >= 8) {
                break;
            }
        }
        return lines;
    }

    // --- per-block link view ---------------------------------------------------------------------

    /** The record the per-block link view is focused on, or null for the whole board. */
    private ContentRecord focusRecord;
    private final Map<String, FocusSlot> focusSlots = new LinkedHashMap<>();
    private final List<FocusEdge> focusEdges = new ArrayList<>();
    private final Map<String, CanvasCard> focusCardCache = new LinkedHashMap<>();
    private FocusEdge selectedFocusEdge;
    private FocusPending focusPending;

    /** One placed card in the per-block link view. */
    private record FocusSlot(ContentRecord record, CanvasCard card, List<String> inputFields,
        double left, double top, double width, double height) {
        double centerX() {
            return left + width / 2;
        }

        double centerY() {
            return top + height / 2;
        }
    }

    private record FocusEdge(FocusSlot from, FocusSlot to, String fieldKey, String sourceName,
        Line visible, Line pick) {
    }

    private record FocusPending(FocusSlot slot, String fieldKey, Line curve) {
    }

    /** One neighbour of the focused record, with the field that links the two. */
    private record Neighbor(ContentRecord record, String fieldKey, boolean outgoing) {
    }

    /** Switches the canvas to the per-block link view (null returns to the whole board). */
    public void setFocusRecord(ContentRecord record) {
        focusRecord = record;
        selectedFocusEdge = null;
        focusPending = null;
        refresh();
    }

    ContentRecord focusRecord() {
        return focusRecord;
    }

    /**
     * The focused record's card, the cards of everything it references and everything referencing
     * it, arranged in three columns and wired port to port. Writes go through
     * {@code connectReference}/{@code disconnectReference} - the view is derived, the records are
     * the one truth.
     */
    private void refreshFocus() {
        nodeLayer.getChildren().clear();
        edgeLayer.getChildren().clear();
        arrowLayer.getChildren().clear();
        cardsByNode.clear();
        pickCurves.clear();
        curvesByEdge.clear();
        arrowsByEdge.clear();
        labelsByEdge.clear();
        rerouteDotsByEdge.clear();
        selectedRerouteEdgeId = null;
        selectedRerouteIndex = -1;
        dragRerouteEdgeId = null;
        dragRerouteIndex = -1;
        dragRerouteBefore = null;
        focusSlots.clear();
        focusEdges.clear();
        selectedFocusEdge = null;
        focusPending = null;

        ContentRecord focus = focusRecord;
        List<Neighbor> neighbors = neighborsOf(focus);

        FocusSlot focusSlot = focusSlot(focus, 340, 280);
        int outRow = 0;
        int inRow = 0;
        for (Neighbor neighbor : neighbors) {
            FocusSlot slot = focusSlot(neighbor.record(),
                neighbor.outgoing() ? 700 : -40 - ContentCard.WIDTH, 40 + 150 * (neighbor.outgoing() ? outRow++ : inRow++));
        }

        // Outgoing: the focus card's ID port to the neighbour's port for that field. Incoming:
        // the neighbour's ID port to the focus card's port for the field that names the focus.
        for (Neighbor neighbor : neighbors) {
            FocusSlot from = neighbor.outgoing() ? focusSlots.get(focusRecord.id)
                : focusSlots.get(neighbor.record().id);
            FocusSlot to = neighbor.outgoing() ? focusSlots.get(neighbor.record().id)
                : focusSlots.get(focusRecord.id);
            if (from == null || to == null) {
                continue;
            }
            Point2D out = portWorldOn(from, null);
            Point2D in = portWorldOn(to, neighbor.fieldKey());
            Line visible = new Line(out.getX(), out.getY(), in.getX(), in.getY());
            visible.getStyleClass().add("edge");
            Line pick = new Line(out.getX(), out.getY(), in.getX(), in.getY());
            pick.getStyleClass().addAll("edge", "edge-pick");
            FocusEdge focusEdge = new FocusEdge(from, to, neighbor.fieldKey(),
                from.record().name, visible, pick);
            pick.setOnMouseClicked(event -> {
                event.consume();
                selectedFocusEdge = focusEdge;
                refreshFocusEdgeSelection();
            });
            focusEdges.add(focusEdge);
            edgeLayer.getChildren().addAll(visible, pick);
        }
    }

    private FocusSlot focusSlot(ContentRecord record, double left, double top) {
        CanvasCard cached = focusCardCache.get(record.id);
        CanvasCard card;
        List<String> inputFields;
        if (cached != null) {
            card = cached;
            inputFields = focusInputFields.get(record.id);
        } else {
            inputFields = inputPorts(record);
            ContentCard built = new ContentCard(controller, record, widgetFields(record),
                inputFields);
            built.setOnEditEnd(this::refresh);
            built.setLod(zoom.getX());
            card = built;
            attachFocusInteractions(record, built);
            focusCardCache.put(record.id, built);
            focusInputFields.put(record.id, inputFields);
        }
        FocusSlot slot = new FocusSlot(record, card, inputFields, left, top,
            card.cardWidth(), card.cardHeight());
        focusSlots.put(record.id, slot);
        card.node().setLayoutX(left);
        card.node().setLayoutY(top);
        nodeLayer.getChildren().add(card.node());
        return slot;
    }

    private final Map<String, List<String>> focusInputFields = new LinkedHashMap<>();

    private List<Neighbor> neighborsOf(ContentRecord focus) {
        List<Neighbor> neighbors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String internal = controller.project().internalName();
        String prefix = internal + "-";
        for (FieldDef field : controller.registry().fieldsOf(controller.classNameOf(focus))) {
            if (!field.isReference()) {
                continue;
            }
            for (String name : dev.modmaker.core.io.LinkGraph.referencedNames(
                focus.fields.get(field.name()), field)) {
                String bare = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
                ContentRecord target = controller.project().contents.stream()
                    .filter(r -> !r.isInline() && r.name.equals(bare)).findFirst().orElse(null);
                if (target != null && target != focus && seen.add("out:" + target.id + ":" + field.name())) {
                    neighbors.add(new Neighbor(target, field.name(), true));
                }
            }
        }
        for (ContentRecord record : controller.project().contents) {
            if (record.isInline() || record == focus) {
                continue;
            }
            for (FieldDef field : controller.registry().fieldsOf(controller.classNameOf(record))) {
                if (!field.isReference()) {
                    continue;
                }
                for (String name : dev.modmaker.core.io.LinkGraph.referencedNames(
                    record.fields.get(field.name()), field)) {
                    String bare = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
                    if (bare.equals(focus.name)
                        && seen.add("in:" + record.id + ":" + field.name())) {
                        neighbors.add(new Neighbor(record, field.name(), false));
                        break;
                    }
                }
            }
        }
        return neighbors;
    }

    /** Wires the per-block link view's port drags and record selection on one focus card. */
    private void attachFocusInteractions(ContentRecord record, ContentCard card) {
        card.node().addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            for (ContentCard.Port port : card.ports()) {
                if (Math.hypot(event.getX() - port.x, event.getY() - port.y) <= 12) {
                    event.consume();
                    FocusSlot slot = focusSlots.get(record.id);
                    if (slot != null) {
                        Line curve = new Line(
                            slot.left() + port.x, slot.top() + port.y,
                            slot.left() + event.getX(), slot.top() + event.getY());
                        curve.getStyleClass().addAll("edge", "edge-pending");
                        edgeLayer.getChildren().add(curve);
                        focusPending = new FocusPending(slot, port.fieldKey, curve);
                    }
                    return;
                }
            }
        });
        card.node().addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (focusPending == null) {
                return;
            }
            event.consume();
            FocusSlot slot = focusSlots.get(record.id);
            if (slot != null) {
                focusPending.curve().setEndX(slot.left() + event.getX());
                focusPending.curve().setEndY(slot.top() + event.getY());
            }
        });
        card.node().setOnMouseReleased(event -> {
            if (focusPending == null) {
                return;
            }
            event.consume();
            FocusPending attempt = focusPending;
            focusPending = null;
            edgeLayer.getChildren().remove(attempt.curve());
            FocusSlot slot = focusSlots.get(record.id);
            if (slot == null) {
                return;
            }
            double wx = slot.left() + event.getX();
            double wy = slot.top() + event.getY();
            FocusSlot target = null;
            String targetField = null;
            double best = 12;
            for (FocusSlot candidate : focusSlots.values()) {
                for (ContentCard.Port port : candidate.card().ports()) {
                    double distance = Math.hypot(wx - (candidate.left() + port.x),
                        wy - (candidate.top() + port.y));
                    if (distance <= best) {
                        best = distance;
                        target = candidate;
                        targetField = port.fieldKey;
                    }
                }
            }
            if (target == null) {
                return;
            }
            FocusSlot out = attempt.fieldKey() == null ? attempt.slot() : target;
            FocusSlot in = attempt.fieldKey() == null ? target : attempt.slot();
            String fieldKey = attempt.fieldKey() == null ? targetField : attempt.fieldKey();
            if (out == in || fieldKey == null) {
                return;
            }
            controller.connectReference(out.record(), null, in.record(), fieldKey);
        });
        card.node().setOnMouseClicked(event -> {
            event.consume();
            if (focusPending != null) {
                return;
            }
            controller.select(record);
        });
    }

    private Point2D portWorldOn(FocusSlot slot, String fieldKey) {
        Point2D anchor = slot.card().portAnchor(fieldKey);
        if (anchor == null) {
            return new Point2D(slot.left(), slot.centerY());
        }
        return new Point2D(slot.left() + anchor.getX(), slot.top() + anchor.getY());
    }

    private void refreshFocusEdgeSelection() {
        for (FocusEdge edge : focusEdges) {
            edge.visible().getStyleClass().remove("selected");
            if (edge == selectedFocusEdge) {
                edge.visible().getStyleClass().add("selected");
            }
        }
    }
}

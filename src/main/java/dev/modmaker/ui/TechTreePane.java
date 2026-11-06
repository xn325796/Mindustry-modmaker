package dev.modmaker.ui;

import com.google.gson.JsonElement;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.TechTreeRules;
import dev.modmaker.core.io.TechTreeIndex;
import dev.modmaker.core.layout.TechTreeCanvasLayout;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tech tree workspace as a canvas, drawn the way the game's research dialog draws it
 * (ResearchDialog, v160.5): 60px nodes, 20px gaps, the root's children split into an upper and a
 * lower half by the BranchTreeLayout port ({@link TechTreeCanvasLayout}), and L-shaped parent-child
 * edges that collapse into diagonals at 45°. The format carries no node positions (ContentParser
 * reads none), so the layout is always computed, never stored - exactly like the game, which also
 * overwrites every node.x/y on each {@code treeLayout()}.
 *
 * <p>Read: the canvas is the derived view {@link TechTreeIndex} renders, one tree per root,
 * vanilla-parented groups included. Write: the toolbar adds a record to the tree (as a root, or as
 * a child of the picked node) and removes the picked node's research - all through the same
 * {@code research} field the inspector's {@link ResearchEditor} edits, so there is still only one
 * copy of the data.
 */
public final class TechTreePane extends BorderPane {

    static final String PROBLEM_STYLE = "techtree-problem";

    private final ProjectController controller;
    private final SpriteStore sprites;

    /** Radius around an input port that grabs it for a re-parent drag (world px). */
    private static final double PORT_GRAB_RADIUS = 16;
    /** How close a dropped drag must come to a compatible node to snap onto it (world px). */
    private static final double SNAP_RADIUS = 24;
    /** The drawn port dot's radius. */
    private static final double PORT_DOT_RADIUS = 5;
    /**
     * Below this zoom a 60px node is under 24 screen px: its name label is unreadable, so the
     * canvas drops to LOD colour blocks (report P1-7, Blender nodemap style).
     */
    private static final double LOD_ZOOM = 0.4;
    /** Padding around a planet lane's node box (world px), so the caption and border clear cards. */
    private static final double LANE_PAD = 26;
    /** Scene px a node-body press must travel before it becomes a lane (planet) re-assign drag. */
    private static final double LANE_DRAG_THRESHOLD = 6;

    private final Pane world = new Pane();
    private final Pane canvas = new Pane(world);
    /** Corner minimap (report P1-6), hidden until the toolbar toggles it on. Declared before
     * the toolbar field: its toggle button wires into it during field initialisation. */
    private final MinimapOverlay minimap = new MinimapOverlay();
    private final Map<String, NodeVisual> visuals = new LinkedHashMap<>();
    /** The unlock tri-state of every record node, re-derived on each rebuild (report P1-8). */
    private Map<String, TechUnlockState.Unlock> unlockStates = Map.of();
    /** Every placed node, vanilla group nodes included: the hit surface for re-parent drags. */
    private final List<NodeVisual> placed = new ArrayList<>();
    private NodeVisual reconnectSource;
    private NodeVisual reconnectTarget;
    private Line reconnectRubber;
    /** The planet lanes drawn behind the nodes this rebuild (report P2-10). */
    private final List<PlanetLanes.Lane> lanes = new ArrayList<>();
    /** The drop-highlight overlay of each lane, keyed by planet. */
    private final Map<String, Region> laneOverlays = new LinkedHashMap<>();
    /** The record node a lane drag started from; null when the press hit nothing draggable. */
    private NodeVisual laneDragSource;
    private boolean laneDragActive;
    private double lanePressSceneX;
    private double lanePressSceneY;
    /** The lane the in-progress drag hovers: the prospective new planet. */
    private PlanetLanes.Lane laneTarget;
    private Line laneRubber;
    private final ComboBox<String> childPicker = new ComboBox<>();
    private final ComboBox<String> rootPicker = new ComboBox<>();
    private final ComboBox<String> treePicker = new ComboBox<>();
    private boolean syncingTrees;
    /** Which root tree the canvas shows: 0 = the whole forest, i+1 = the i-th root. */
    private int shownTree;
    private final List<ContentRecord> unassigned = new ArrayList<>();
    private final Label hint = new Label();

    /** The node the user last clicked; a vanilla node has no record but is still a parent target. */
    private TechTreeIndex.Entry picked;

    private double panX;
    private double panY;
    private double zoom = 1;

    TechTreePane(ProjectController controller) {
        this.controller = controller;
        this.sprites = new SpriteStore(controller);

        canvas.getStyleClass().add("tech-canvas");
        world.getStyleClass().add("tech-world");
        setCenter(canvas);
        installCanvasEvents();
        installMinimap();
        // A rebuild before the pane has its size cannot fit; refit whenever the viewport appears
        // or the window is resized, so the forest stays fully visible.
        canvas.widthProperty().addListener((observable, was, now) -> fitView());
        canvas.heightProperty().addListener((observable, was, now) -> fitView());

        controller.onSelectionChanged(selected -> {
            // Two-way sync: selecting a record elsewhere highlights its node here, and clicking a
            // node here selects the record for the inspector.
            if (selected != null) {
                NodeVisual visual = visuals.get(selected.id);
                picked = visual == null ? null : visual.entry;
            } else {
                picked = null;
            }
            refreshHighlight();
        });
        controller.onProjectChanged(this::rebuild);
        rebuild();
    }

    // --- toolbar ---------------------------------------------------------------------------------

    private final Region toolbar = buildToolbar();

    /** The workspace's sub-header row, hosted by AppShell above this pane. */
    Region toolbar() {
        return toolbar;
    }

    private Region buildToolbar() {
        childPicker.getStyleClass().add("tech-picker");
        childPicker.setPromptText(Messages.t("techtree.pick.prompt"));
        childPicker.setPrefWidth(180);

        Button addChild = toolButton("techtree.addChildTitle", this::attachPickedChild);
        addChild.disableProperty().bind(childPicker.valueProperty().isNull());

        rootPicker.getStyleClass().add("tech-picker");
        rootPicker.setPromptText(Messages.t("techtree.pick.prompt"));
        rootPicker.setPrefWidth(180);

        Button addRoot = toolButton("techtree.addRootTitle", this::promotePickedToRoot);
        addRoot.disableProperty().bind(rootPicker.valueProperty().isNull());

        Button remove = toolButton("techtree.removeChildTitle", this::removePickedResearch);
        Button fit = toolButton("techtree.fit", this::fitView);

        ToggleButton minimapToggle = new ToggleButton(Messages.t("techtree.minimap"));
        minimapToggle.getStyleClass().add("tool-button");
        minimapToggle.selectedProperty().addListener((observable, was, now) ->
            setMinimapVisible(now));

        // The game shows one tree at a time (ResearchDialog's root picker): with hundreds of cards
        // a whole forest only fits at unreadable zoom, so the view switches per root tree.
        treePicker.setPrefWidth(180);
        treePicker.valueProperty().addListener((observable, was, now) -> {
            if (syncingTrees) {
                return;
            }
            shownTree = Math.max(0, treePicker.getSelectionModel().getSelectedIndex());
            rebuild();
        });

        hint.getStyleClass().add("muted");
        hint.setText(Messages.t("techtree.canvas.hint"));
        HBox.setHgrow(hint, Priority.ALWAYS);
        hint.setMaxWidth(Double.MAX_VALUE);

        HBox bar = new HBox(8, treePicker, new javafx.scene.control.Separator(
                javafx.geometry.Orientation.VERTICAL),
            childPicker, addChild, rootPicker, addRoot, remove, fit, minimapToggle, hint);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("sub-header");
        return bar;
    }

    private Button toolButton(String titleKey, Runnable action) {
        Button button = new Button(Messages.t(titleKey));
        button.getStyleClass().add("tool-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    /** Puts the picked record into the tree as a child of the picked node. */
    private void attachPickedChild() {
        ContentRecord child = pickerChoice(childPicker);
        if (child == null) {
            return;
        }
        if (picked == null) {
            hint.setText(Messages.t("techtree.needsNode"));
            return;
        }
        // The prefixed spelling is the one the game resolves first (ContentParser.java:1401) and
        // the one that cannot silently bind to a same-named vanilla node; a vanilla parent keeps
        // its bare name, which is the only thing the game can resolve it by.
        String parentName = picked.contentId() == null
            ? picked.label()
            : pickedRecord().fullName(controller.project().internalName());
        writeResearch(child, TechTreeRules.Node.empty().withParent(parentName));
    }

    /** Puts the picked record into the tree as its own root, on the project's only planet if any. */
    private void promotePickedToRoot() {
        ContentRecord record = pickerChoice(rootPicker);
        if (record == null) {
            return;
        }
        TechTreeRules.Node node = TechTreeRules.Node.empty().withRoot(true);
        // A root without a planet is never auto-detected by the game (Planet.init finds
        // roots.find(n -> n.planet == this)), so prefill when the project has exactly one.
        List<ContentRecord> planets = planetsOf();
        if (planets.size() == 1) {
            node = node.withPlanet(planets.get(0).fullName(controller.project().internalName()));
        }
        writeResearch(record, node);
    }

    /** Clears the picked node's research, taking it out of the tree. */
    private void removePickedResearch() {
        ContentRecord record = pickedRecord();
        if (record == null) {
            hint.setText(Messages.t("techtree.needsNode"));
            return;
        }
        writeResearch(record, TechTreeRules.Node.empty());
    }

    /** The record behind a picker's current value, resolved against the shared unassigned pool. */
    private ContentRecord pickerChoice(ComboBox<String> picker) {
        String value = picker.getValue();
        if (value == null) {
            return null;
        }
        int index = picker.getItems().indexOf(value);
        return index >= 0 && index < unassigned.size() ? unassigned.get(index) : null;
    }

    private void writeResearch(ContentRecord record, TechTreeRules.Node node) {
        JsonElement value = TechTreeRules.write(node);
        controller.setField(record, TechTreeRules.FIELD, value);
    }

    private List<ContentRecord> planetsOf() {
        ModProject project = controller.project();
        List<ContentRecord> planets = new ArrayList<>();
        if (project != null) {
            for (ContentRecord record : project.contents) {
                if (!record.isInline() && record.type == ContentType.planet) {
                    planets.add(record);
                }
            }
        }
        return planets;
    }

    private ContentRecord pickedRecord() {
        return picked == null || picked.contentId() == null
            ? null : controller.project().contentById(picked.contentId());
    }

    // --- canvas ----------------------------------------------------------------------------------

    /** Rebuilds the whole canvas from the project; pan and zoom survive. */
    public void rebuild() {
        cancelReconnect();
        cancelLaneDrag();
        sprites.invalidate();
        world.getChildren().clear();
        visuals.clear();
        placed.clear();
        unlockStates = Map.of();
        refreshPickers();

        ModProject project = controller.project();
        if (project == null) {
            return;
        }
        TechTreeIndex.Forest forest = TechTreeIndex.build(project, controller.registry());
        // Report P1-8: the tri-state is derived once per rebuild from the parsed forest plus the
        // true root records; the node visuals below only consume the map.
        unlockStates = TechUnlockState.derive(forest, TechUnlockState.rootRecordIds(project.contents));
        syncTreePicker(forest);

        double yOffset = 0;
        int index = 0;
        for (TechTreeIndex.Entry root : forest.roots()) {
            index++;
            if (shownTree > 0 && shownTree != index) {
                continue;
            }
            TechTreeCanvasLayout.Box box = boxOf(root);
            TechTreeCanvasLayout.layout(box);
            Rect bounds = boundsOf(box);
            place(box, -bounds.minX(), yOffset - bounds.minY());
            yOffset += bounds.height() + 120;
        }

        drawLanes();
        refreshHighlight();
        fitView();
        rebuildMinimapBlocks();
        if (forest.roots().isEmpty()) {
            Label empty = new Label(Messages.t("techtree.canvas.empty"));
            empty.getStyleClass().add("muted");
            empty.setLayoutX(40);
            empty.setLayoutY(40);
            world.getChildren().add(empty);
        }
    }

    private static TechTreeCanvasLayout.Box boxOf(TechTreeIndex.Entry entry) {
        TechTreeCanvasLayout.Box box = new TechTreeCanvasLayout.Box(entry);
        for (TechTreeIndex.Entry child : entry.children()) {
            box.children.add(boxOf(child));
        }
        return box;
    }

    private record Rect(double minX, double minY, double width, double height) { }

    /** One node in the placement pass, with its parent's world Y (null for a tree root). */
    private record NodePlacement(TechTreeCanvasLayout.Box box, Double parentY) { }

    private static Rect boundsOf(TechTreeCanvasLayout.Box box) {
        double minX = box.x - box.width / 2, minY = box.y - box.height / 2;
        double maxX = box.x + box.width / 2, maxY = box.y + box.height / 2;
        List<TechTreeCanvasLayout.Box> stack = new ArrayList<>(box.children);
        while (!stack.isEmpty()) {
            TechTreeCanvasLayout.Box node = stack.remove(stack.size() - 1);
            minX = Math.min(minX, node.x - node.width / 2);
            minY = Math.min(minY, node.y - node.height / 2);
            maxX = Math.max(maxX, node.x + node.width / 2);
            maxY = Math.max(maxY, node.y + node.height / 2);
            stack.addAll(node.children);
        }
        return new Rect(minX, minY, maxX - minX, maxY - minY);
    }

    /** Draws a laid-out subtree at an offset: edges first, then node visuals on top. */
    private void place(TechTreeCanvasLayout.Box box, double dx, double dy) {
        List<TechTreeCanvasLayout.Box> stack = new ArrayList<>();
        stack.add(box);
        while (!stack.isEmpty()) {
            TechTreeCanvasLayout.Box node = stack.remove(stack.size() - 1);
            for (TechTreeCanvasLayout.Box child : node.children) {
                world.getChildren().add(edge(node, child, dx, dy));
                stack.add(child);
            }
        }
        List<NodePlacement> nodeStack = new ArrayList<>();
        nodeStack.add(new NodePlacement(box, null));
        while (!nodeStack.isEmpty()) {
            NodePlacement placement = nodeStack.remove(nodeStack.size() - 1);
            TechTreeCanvasLayout.Box node = placement.box();
            TechTreeIndex.Entry entry = (TechTreeIndex.Entry) node.payload;
            NodeVisual visual = new NodeVisual(entry, node.x + dx, node.y + dy,
                placement.parentY());
            if (entry.contentId() != null) {
                visuals.put(entry.contentId(), visual);
            }
            placed.add(visual);
            world.getChildren().addAll(visual.body(), visual.label());
            if (visual.port != null) {
                world.getChildren().add(visual.port);
            }
            for (TechTreeCanvasLayout.Box child : node.children) {
                nodeStack.add(new NodePlacement(child, node.y + dy));
            }
        }
    }

    /** Parent-child edge, drawn like ResearchDialog.drawChildren (ResearchDialog.java:786-813). */
    private Pane edge(TechTreeCanvasLayout.Box parent, TechTreeCanvasLayout.Box child,
        double dx, double dy) {
        TechTreeIndex.Entry from = (TechTreeIndex.Entry) parent.payload;
        TechTreeIndex.Entry to = (TechTreeIndex.Entry) child.payload;
        double px = parent.x + dx;
        double py = parent.y + dy;
        double cx = child.x + dx;
        double cy = child.y + dy;

        Pane group = new Pane();
        group.getStyleClass().add(edgeStyle(from, to));
        group.setMouseTransparent(true);
        // Diagonal at ~45° within three node widths, otherwise an L: horizontal from the parent,
        // then vertical down to the child - the game's exact rule.
        double distX = Math.abs(cx - px);
        double distY = Math.abs(cy - py);
        if (Math.abs(distX - distY) <= 1f && Math.hypot(distX, distY) <= parent.width * 3) {
            group.getChildren().add(line(px, py, cx, cy));
        } else {
            group.getChildren().add(line(px, py, cx, py));
            group.getChildren().add(line(cx, py, cx, cy));
        }
        return group;
    }

    private static Line line(double x1, double y1, double x2, double y2) {
        Line edge = new Line(x1, y1, x2, y2);
        edge.getStyleClass().add("tech-edge");
        return edge;
    }

    private static String edgeStyle(TechTreeIndex.Entry from, TechTreeIndex.Entry to) {
        if (from.problem() != TechTreeIndex.Problem.none || to.problem() != TechTreeIndex.Problem.none) {
            return "tech-edge-problem";
        }
        return from.vanilla() ? "tech-edge-vanilla" : "tech-edge-mod";
    }

    // --- planet lanes ----------------------------------------------------------------------------

    /**
     * Planet lanes (report P2-10): one coloured, padded box per distinct {@code research.planet}
     * among the placed record nodes, drawn behind everything, with the planet's name as caption.
     * Node positions stay the layout's business - the lanes only annotate it, the Mindustry
     * research-dialog grouping feel. Lane colour is inline (planet count is unbounded; same call
     * as the batch-B card head with {@code ContentType.color()}); caption and drop highlight are
     * CSS classes. All lane nodes are mouse-transparent, so panning and hit-testing are unchanged.
     */
    private void drawLanes() {
        lanes.clear();
        laneOverlays.clear();
        ModProject project = controller.project();
        List<PlanetLanes.Member> members = new ArrayList<>();
        if (project != null) {
            for (NodeVisual visual : placed) {
                if (visual.entry.contentId() == null) {
                    continue;
                }
                ContentRecord record = project.contentById(visual.entry.contentId());
                if (record == null) {
                    continue;
                }
                String planet = TechTreeRules.read(record.fields.get(TechTreeRules.FIELD)).planet();
                if (planet != null && !planet.isBlank()) {
                    members.add(new PlanetLanes.Member(planet, visual.centerX, visual.centerY));
                }
            }
        }
        lanes.addAll(PlanetLanes.build(members, TechTreeCanvasLayout.NODE_SIZE / 2, LANE_PAD));
        List<Node> laneNodes = new ArrayList<>();
        for (PlanetLanes.Lane lane : lanes) {
            String color = PlanetLanes.colorFor(lane.planet());
            Region box = new Region();
            box.getStyleClass().add("tech-lane");
            box.setStyle("-fx-background-color: " + color + "14; -fx-border-color: " + color + "66;");
            box.setLayoutX(lane.minX());
            box.setLayoutY(lane.minY());
            box.setPrefSize(lane.width(), lane.height());
            box.setMouseTransparent(true);
            Label name = new Label(laneLabel(lane.planet()));
            name.getStyleClass().add("tech-lane-label");
            name.setLayoutX(lane.minX() + 8);
            name.setLayoutY(lane.minY() + 3);
            name.setMouseTransparent(true);
            Region overlay = new Region();
            overlay.getStyleClass().add("tech-lane-target");
            overlay.setLayoutX(lane.minX());
            overlay.setLayoutY(lane.minY());
            overlay.setPrefSize(lane.width(), lane.height());
            overlay.setMouseTransparent(true);
            overlay.setVisible(false);
            laneOverlays.put(lane.planet(), overlay);
            laneNodes.add(box);
            laneNodes.add(name);
            laneNodes.add(overlay);
        }
        world.getChildren().addAll(0, laneNodes);
    }

    /** The lane caption: the mod planet record's display name when the key resolves to one, else
     * the raw {@code research.planet} string (vanilla planets have no record in this project). */
    private String laneLabel(String planet) {
        ModProject project = controller.project();
        if (project != null) {
            String internal = project.internalName();
            for (ContentRecord record : project.contents) {
                if (!record.isInline() && record.type == ContentType.planet
                    && (record.name.equals(planet) || record.fullName(internal).equals(planet))) {
                    return controller.displayName(record);
                }
            }
        }
        return planet;
    }

    // --- node visuals ----------------------------------------------------------------------------

    private final class NodeVisual {
        private final TechTreeIndex.Entry entry;
        private final StackPane body = new StackPane();
        private final Label label = new Label();
        private final double centerX;
        private final double centerY;
        /** The input port dot (world-space, drawn over the edges); null on vanilla nodes. */
        private final Circle port;
        private final double portX;
        private final double portY;
        /** The research-cost chip row inside the body, or null when the node has no requirements. */
        private final HBox cost;

        NodeVisual(TechTreeIndex.Entry entry, double x, double y, Double parentY) {
            this.entry = entry;
            centerX = x;
            centerY = y;
            if (entry.contentId() != null) {
                // The port sits on the node's parent-facing edge: the layout split puts the root's
                // bottom-half children above their parent, so "which side" is per node, not global.
                boolean parentAbove = parentY == null || parentY <= y;
                portX = x;
                portY = parentAbove ? y - TechTreeCanvasLayout.NODE_SIZE / 2
                    : y + TechTreeCanvasLayout.NODE_SIZE / 2;
                port = new Circle(PORT_DOT_RADIUS);
                port.getStyleClass().add("tech-port");
                port.setCenterX(portX);
                port.setCenterY(portY);
                port.setMouseTransparent(true);
            } else {
                port = null;
                portX = x;
                portY = y;
            }
            body.getStyleClass().addAll("tech-node", styleOf(entry));
            body.setPrefSize(TechTreeCanvasLayout.NODE_SIZE, TechTreeCanvasLayout.NODE_SIZE);
            body.setLayoutX(x - TechTreeCanvasLayout.NODE_SIZE / 2);
            body.setLayoutY(y - TechTreeCanvasLayout.NODE_SIZE / 2);
            body.getChildren().add(content());
            if (entry.contentId() == null) {
                // A vanilla parent the mod only hangs nodes off: visible, but it has no record.
                body.setOpacity(0.75);
            }
            // Report P1-8: the unlock state rides on the node as a corner badge (plus a fade when
            // locked) - it never replaces the vanilla/mod/problem type colouring, because "what it
            // is" and "can it be researched" are two independent channels.
            TechUnlockState.Unlock unlock = unlockOf(entry);
            body.getStyleClass().add(unlock.name());
            Circle badge = new Circle(4.5);
            badge.getStyleClass().addAll("tech-lock-badge", unlock.name());
            badge.setMouseTransparent(true);
            StackPane.setAlignment(badge, Pos.TOP_RIGHT);
            StackPane.setMargin(badge, new Insets(3));
            body.getChildren().add(badge);
            cost = costRowOf(entry);
            if (cost != null) {
                StackPane.setAlignment(cost, Pos.BOTTOM_CENTER);
                StackPane.setMargin(cost, new Insets(0, 0, 2, 0));
                body.getChildren().add(cost);
            }
            body.addEventFilter(MouseEvent.MOUSE_PRESSED, MouseEvent::consume);
            body.addEventFilter(MouseEvent.MOUSE_DRAGGED, MouseEvent::consume);
            body.setOnMouseClicked(event -> {
                event.consume();
                picked = entry;
                ContentRecord record = pickedRecord();
                if (record != null && record != controller.selected()) {
                    controller.select(record);
                } else {
                    refreshHighlight();
                }
            });
            Tooltip.install(body, new Tooltip(tooltipOf(entry)));

            label.setText(entry.label());
            label.getStyleClass().add("tech-node-label");
            if (entry.problem() != TechTreeIndex.Problem.none) {
                label.getStyleClass().add(PROBLEM_STYLE);
            }
            label.setFont(Font.font(11));
            label.setLayoutX(x - 90);
            label.setLayoutY(y + TechTreeCanvasLayout.NODE_SIZE / 2 + 2);
            label.setPrefWidth(180);
            label.setAlignment(Pos.CENTER);
        }

        private Node content() {
            ContentRecord record = pickedRecordOf(entry);
            if (record != null) {
                String region = sprites.body(record.name);
                var image = region == null ? null : sprites.image(region);
                if (image != null) {
                    javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(image);
                    view.setFitWidth(40);
                    view.setFitHeight(40);
                    view.setPreserveRatio(true);
                    return view;
                }
            }
            Label initial = new Label(entry.label().isEmpty() ? "?" : entry.label().substring(0, 1));
            initial.getStyleClass().add("tech-node-icon");
            return initial;
        }

        private ContentRecord pickedRecordOf(TechTreeIndex.Entry of) {
            ModProject project = controller.project();
            return of.contentId() == null || project == null
                ? null : project.contentById(of.contentId());
        }

        StackPane body() {
            return body;
        }

        Label label() {
            return label;
        }

        /** Selection is a border, not a rebuild: panning must survive clicks. */
        void applyHighlight(boolean selected) {
            body.getStyleClass().remove("selected");
            label.getStyleClass().remove("selected");
            if (selected) {
                body.getStyleClass().add("selected");
                label.getStyleClass().add("selected");
            }
        }
    }

    private void refreshHighlight() {
        String pickedId = picked == null ? null : picked.contentId();
        for (Map.Entry<String, NodeVisual> visual : visuals.entrySet()) {
            visual.getValue().applyHighlight(visual.getKey().equals(pickedId));
        }
    }

    private static String styleOf(TechTreeIndex.Entry entry) {
        if (entry.problem() != TechTreeIndex.Problem.none) {
            return "problem";
        }
        return entry.vanilla() ? "vanilla" : "mod";
    }

    /** The node's unlock state (report P1-8); a vanilla anchor is an open parent by definition. */
    private TechUnlockState.Unlock unlockOf(TechTreeIndex.Entry entry) {
        if (entry.contentId() == null) {
            return TechUnlockState.Unlock.unlocked;
        }
        return unlockStates.getOrDefault(entry.contentId(), TechUnlockState.Unlock.locked);
    }

    /**
     * The research-cost chips (report P1-8): one per requirement. A mod sprite for the item shows
     * as a small icon plus the amount; anything the atlas does not hold (vanilla items live in the
     * game, not the mod) degrades to the readable "item amount" text form. Null when the node
     * carries no requirements at all.
     */
    private HBox costRowOf(TechTreeIndex.Entry entry) {
        ContentRecord record = recordOf(entry);
        if (record == null) {
            return null;
        }
        List<String> requirements = TechTreeRules
            .read(record.fields.get(TechTreeRules.FIELD)).requirements();
        if (requirements.isEmpty()) {
            return null;
        }
        HBox row = new HBox(2);
        row.getStyleClass().add("tech-cost");
        for (String requirement : requirements) {
            row.getChildren().add(costChip(requirement));
        }
        return row;
    }

    /** One cost chip: the {@code "item/amount"} form the game parses, icon-first when available. */
    private HBox costChip(String requirement) {
        int slash = requirement.lastIndexOf('/');
        String item = slash > 0 ? requirement.substring(0, slash) : requirement;
        String amount = slash > 0 ? requirement.substring(slash + 1) : "";
        HBox chip = new HBox(2);
        chip.getStyleClass().add("tech-cost-chip");
        String region = sprites.body(item);
        javafx.scene.image.Image image = region == null ? null : sprites.image(region);
        if (image != null) {
            javafx.scene.image.ImageView icon = new javafx.scene.image.ImageView(image);
            icon.setFitWidth(10);
            icon.setFitHeight(10);
            icon.setPreserveRatio(true);
            chip.getChildren().add(icon);
        }
        Label text = new Label(image != null ? amount
            : (amount.isEmpty() ? item : item + " " + amount));
        text.getStyleClass().add("tech-cost-text");
        chip.getChildren().add(text);
        return chip;
    }

    private static String tooltipOf(TechTreeIndex.Entry entry) {
        if (entry.problem() == TechTreeIndex.Problem.none) {
            return entry.vanilla() ? Messages.t("techtree.vanilla") + ": " + entry.label()
                : entry.label();
        }
        return entry.label() + " - " + Messages.t("techtree.problem." + entry.problem().name());
    }

    // --- pickers ---------------------------------------------------------------------------------

    private void refreshPickers() {
        childPicker.getSelectionModel().clearSelection();
        rootPicker.getSelectionModel().clearSelection();
        unassigned.clear();
        ModProject project = controller.project();
        if (project != null) {
            TechTreeIndex.Forest forest = TechTreeIndex.build(project, controller.registry());
            for (TechTreeIndex.Entry entry : forest.unassigned()) {
                ContentRecord record = project.contentById(entry.contentId());
                if (record != null) {
                    unassigned.add(record);
                }
            }
        }
        List<String> names = unassigned.stream().map(controller::displayName).toList();
        childPicker.getItems().setAll(names);
        rootPicker.getItems().setAll(names);
    }

    /** Keeps the tree switcher's items in step with the forest; selection survives rebuilds. */
    private void syncTreePicker(TechTreeIndex.Forest forest) {
        syncingTrees = true;
        try {
            List<String> names = new ArrayList<>();
            names.add(Messages.t("techtree.allTrees"));
            forest.roots().forEach(root -> names.add(root.label()));
            treePicker.getItems().setAll(names);
            if (shownTree > forest.roots().size()) {
                shownTree = 0;
            }
            treePicker.getSelectionModel().select(shownTree);
        } finally {
            syncingTrees = false;
        }
    }

    // --- pan & zoom ------------------------------------------------------------------------------

    /**
     * One affine holds pan and zoom, so the mapping stays deterministic: a drag pans by the scene
     * delta, a scroll zooms around the cursor. Drags starting on a node do not pan - the node
     * consumes press events, and the canvas ignores targets inside a node anyway.
     */
    private void installCanvasEvents() {
        double[] anchor = new double[2];
        double[] base = new double[2];
        // The view keys (F, Ctrl+0) live on the canvas, so it must be focus-traversable; a press
        // anywhere on the canvas takes the focus, and the keys act only while it owns the focus.
        canvas.setFocusTraversable(true);
        // Re-parent drags start at a node's input port. A canvas-level filter runs before the
        // node body's own press filter, so grabbing a port consumes the press and keeps the
        // gesture from selecting the node; every other press falls through untouched, and the
        // pan/zoom handlers below stay exactly as they were.
        canvas.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || insideMinimap(event)) {
                return;
            }
            Point2D worldPoint = toWorldPoint(event);
            laneDragSource = null;
            laneDragActive = false;
            if (beginReconnectAt(worldPoint.getX(), worldPoint.getY())) {
                event.consume();
                return;
            }
            // No port under the press: a record node's body may start a lane drag (report P2-10),
            // but only once the pointer travels - a press that never moves must stay the plain
            // click-select it always was, so the press is not consumed and the node body sees it.
            laneDragSource = laneDragCandidateAt(worldPoint.getX(), worldPoint.getY());
            lanePressSceneX = event.getSceneX();
            lanePressSceneY = event.getSceneY();
        });
        canvas.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (reconnectSource != null) {
                Point2D worldPoint = toWorldPoint(event);
                dragReconnectTo(worldPoint.getX(), worldPoint.getY());
                event.consume();
                return;
            }
            if (laneDragSource == null) {
                return;
            }
            if (!laneDragActive) {
                if (Math.hypot(event.getSceneX() - lanePressSceneX,
                    event.getSceneY() - lanePressSceneY) < LANE_DRAG_THRESHOLD) {
                    return;
                }
                beginLaneDrag();
            }
            Point2D worldPoint = toWorldPoint(event);
            dragLaneTo(worldPoint.getX(), worldPoint.getY());
            event.consume();
        });
        canvas.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (reconnectSource != null) {
                Point2D local = canvas.sceneToLocal(event.getSceneX(), event.getSceneY());
                if (local.getX() < 0 || local.getY() < 0
                    || local.getX() > canvas.getWidth() || local.getY() > canvas.getHeight()) {
                    // Released outside the viewport (over the toolbar, say): abort, never disconnect.
                    cancelReconnect();
                } else {
                    Point2D worldPoint = toWorldPoint(event);
                    endReconnectAt(worldPoint.getX(), worldPoint.getY());
                }
                event.consume();
                return;
            }
            if (laneDragActive) {
                Point2D worldPoint = toWorldPoint(event);
                endLaneAt(worldPoint.getX(), worldPoint.getY());
                event.consume();
            }
            laneDragSource = null;
            laneDragActive = false;
        });
        canvas.setOnMousePressed(event -> {
            canvas.requestFocus();
            if (insideNode(event)) {
                return;
            }
            anchor[0] = event.getX();
            anchor[1] = event.getY();
            base[0] = panX;
            base[1] = panY;
        });
        canvas.setOnMouseDragged(event -> {
            if (anchor[0] < 0 || insideNode(event)) {
                return;
            }
            panX = base[0] + event.getX() - anchor[0];
            panY = base[1] + event.getY() - anchor[1];
            applyTransform();
        });
        canvas.setOnMouseReleased(event -> anchor[0] = -1);
        canvas.setOnScroll((ScrollEvent event) -> {
            double factor = event.getDeltaY() > 0 ? 1.15 : 1 / 1.15;
            double next = Math.max(0.15, Math.min(2.5, zoom * factor));
            if (next == zoom) {
                return;
            }
            // Zoom towards the cursor: the world point under it stays under it.
            double worldX = (event.getX() - panX) / zoom;
            double worldY = (event.getY() - panY) / zoom;
            zoom = next;
            panX = event.getX() - worldX * zoom;
            panY = event.getY() - worldY * zoom;
            applyTransform();
        });
        canvas.setOnKeyPressed(event -> {
            if (event.getTarget() != canvas) {
                return;
            }
            if (event.isControlDown()
                && (event.getCode() == KeyCode.DIGIT0 || event.getCode() == KeyCode.NUMPAD0)) {
                resetZoom();
                event.consume();
            } else if (event.getCode() == KeyCode.F && !event.isControlDown()
                && !event.isAltDown() && !event.isMetaDown()) {
                fitView();
                event.consume();
            }
        });
    }

    /** Ctrl+0: back to 100%, keeping the world point at the viewport centre under the centre. */
    private void resetZoom() {
        if (zoom == 1) {
            return;
        }
        double centerX = canvas.getWidth() / 2;
        double centerY = canvas.getHeight() / 2;
        double worldX = (centerX - panX) / zoom;
        double worldY = (centerY - panY) / zoom;
        zoom = 1;
        panX = centerX - worldX;
        panY = centerY - worldY;
        applyTransform();
    }

    /** True when the event's target sits inside a node visual, so a drag must not pan. */
    private boolean insideNode(MouseEvent event) {
        Node target = (Node) event.getTarget();
        while (target != null && target != canvas && target != world) {
            if (target.getStyleClass().contains("tech-node")) {
                return true;
            }
            target = target.getParent();
        }
        return false;
    }

    /** An event's scene coordinates mapped into world space (pan and zoom undone). */
    private Point2D toWorldPoint(MouseEvent event) {
        Point2D local = canvas.sceneToLocal(event.getSceneX(), event.getSceneY());
        return new Point2D((local.getX() - panX) / zoom, (local.getY() - panY) / zoom);
    }

    /**
     * Fits the whole rendered forest into the viewport, the way opening the game's research dialog
     * shows the whole tree. Runs after every rebuild (an edit changes the shape) and once the
     * canvas first gains its size; the user's own pan/zoom wins until the next rebuild.
     */
    private void fitView() {
        var content = world.getBoundsInLocal();
        double viewWidth = canvas.getWidth();
        double viewHeight = canvas.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || content.getWidth() <= 0 || content.getHeight() <= 0) {
            return;
        }
        double fit = Math.min((viewWidth - 40) / content.getWidth(),
            (viewHeight - 40) / content.getHeight());
        zoom = Math.max(0.05, Math.min(1, fit));
        panX = (viewWidth - content.getWidth() * zoom) / 2 - content.getMinX() * zoom;
        panY = (viewHeight - content.getHeight() * zoom) / 2 - content.getMinY() * zoom;
        applyTransform();
    }

    private void applyTransform() {
        world.setTranslateX(panX);
        world.setTranslateY(panY);
        // JavaFX scales around the bounds centre by default; a Transform object pivots at 0,0,
        // which is what the cursor-anchored zoom math above assumes.
        world.getTransforms().setAll(new javafx.scene.transform.Scale(zoom, zoom, 0, 0));
        applyLod();
        if (minimap.isVisible()) {
            minimap.updateViewport(panX, panY, zoom, canvas.getWidth(), canvas.getHeight());
        }
    }

    /**
     * LOD (report P1-7): below the threshold the world root carries the .lod class and CSS drops
     * the name labels; colour blocks, icons and the selection border stay. Hit-testing and the
     * view keys are untouched - this is purely a render-time style switch.
     */
    private void applyLod() {
        if (MinimapMath.lodActive(zoom, LOD_ZOOM)) {
            if (!world.getStyleClass().contains("lod")) {
                world.getStyleClass().add("lod");
            }
        } else {
            world.getStyleClass().remove("lod");
        }
    }

    // --- minimap ---------------------------------------------------------------------------------

    /** The corner minimap (report P1-6): hidden until the toolbar toggles it on. */
    private void installMinimap() {
        canvas.getChildren().add(minimap);
        minimap.setManaged(false);
        minimap.setVisible(false);
        minimap.layoutXProperty().bind(
            canvas.widthProperty().subtract(MinimapOverlay.WIDTH).subtract(12));
        minimap.layoutYProperty().bind(
            canvas.heightProperty().subtract(MinimapOverlay.HEIGHT).subtract(12));
        minimap.setCenterOn((worldX, worldY) -> {
            panX = MinimapMath.panToCenterX(worldX, zoom, canvas.getWidth());
            panY = MinimapMath.panToCenterY(worldY, zoom, canvas.getHeight());
            applyTransform();
        });
    }

    /** True when the press landed on the minimap overlay: its own handlers own that gesture. */
    private boolean insideMinimap(MouseEvent event) {
        Node target = (Node) event.getTarget();
        while (target != null && target != canvas) {
            if (target == minimap) {
                return true;
            }
            target = target.getParent();
        }
        return false;
    }

    /** Reprojects every placed node into the minimap; a no-op while the map is hidden. */
    private void rebuildMinimapBlocks() {
        if (!minimap.isVisible()) {
            return;
        }
        double half = TechTreeCanvasLayout.NODE_SIZE / 2;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (NodeVisual visual : placed) {
            minX = Math.min(minX, visual.centerX - half);
            minY = Math.min(minY, visual.centerY - half);
            maxX = Math.max(maxX, visual.centerX + half);
            maxY = Math.max(maxY, visual.centerY + half);
        }
        if (placed.isEmpty()) {
            minX = minY = 0;
            maxX = maxY = 1;
        }
        minimap.beginBlocks(MinimapMath.fit(minX, minY, maxX, maxY,
            MinimapOverlay.WIDTH, MinimapOverlay.HEIGHT, MinimapOverlay.MARGIN));
        for (NodeVisual visual : placed) {
            minimap.addBlock(visual.centerX - half, visual.centerY - half,
                TechTreeCanvasLayout.NODE_SIZE, TechTreeCanvasLayout.NODE_SIZE,
                "mm-node-" + styleOf(visual.entry), null);
        }
        minimap.updateViewport(panX, panY, zoom, canvas.getWidth(), canvas.getHeight());
    }

    /** The toolbar toggle's action: show or hide the corner minimap. */
    void setMinimapVisible(boolean visible) {
        minimap.setVisible(visible);
        if (visible) {
            rebuildMinimapBlocks();
        }
    }

    // --- re-parent drags -------------------------------------------------------------------------

    /**
     * Starts a re-parent drag when the point sits on a node's input port. True when the gesture
     * took the press - the caller consumes the event so the node is not selected underneath.
     */
    boolean beginReconnectAt(double worldX, double worldY) {
        NodeVisual source = portAt(worldX, worldY);
        if (source == null) {
            return false;
        }
        reconnectSource = source;
        reconnectRubber = new Line(source.portX, source.portY, worldX, worldY);
        reconnectRubber.getStyleClass().add("tech-rubber");
        reconnectRubber.setMouseTransparent(true);
        world.getChildren().add(reconnectRubber);
        return true;
    }

    /** Moves the rubber band and highlights the node a release would re-parent onto. */
    void dragReconnectTo(double worldX, double worldY) {
        if (reconnectSource == null) {
            return;
        }
        reconnectRubber.setEndX(worldX);
        reconnectRubber.setEndY(worldY);
        setReconnectTarget(dropTargetAt(worldX, worldY));
    }

    /**
     * Ends the drag: a compatible node under the point - or one within snap distance - becomes
     * the source's parent; empty space drops the parent, which would orphan the node (the game
     * ignores parentless non-roots), so it leaves the tree entirely, like the toolbar's remove
     * button. Every write goes through {@code controller.setField}: undoable, coalescing, the
     * same single copy of the data as the inspector.
     */
    boolean endReconnectAt(double worldX, double worldY) {
        NodeVisual source = reconnectSource;
        if (source == null) {
            return false;
        }
        NodeVisual body = nodeBodyAt(worldX, worldY);
        boolean onSelf = body == source;
        boolean cycle = body != null && !onSelf && !isCompatibleTarget(body);
        NodeVisual target = null;
        if (!cycle && !onSelf) {
            target = body != null ? body : nearestCompatibleTarget(worldX, worldY);
        }
        cancelReconnect();
        if (cycle) {
            hint.setText(Messages.t("techtree.reconnect.cycle"));
            return true;
        }
        if (target != null) {
            reparentTo(source, target);
            return true;
        }
        if (onSelf) {
            return true; // releasing back on the node's own body changes nothing
        }
        removeFromTree(source);
        return true;
    }

    /** Where a release lands: the node under the point, else the nearest compatible snap. */
    private NodeVisual dropTargetAt(double worldX, double worldY) {
        NodeVisual body = nodeBodyAt(worldX, worldY);
        if (body != null) {
            return isCompatibleTarget(body) ? body : null;
        }
        return nearestCompatibleTarget(worldX, worldY);
    }

    private void setReconnectTarget(NodeVisual target) {
        if (target == reconnectTarget) {
            return;
        }
        if (reconnectTarget != null) {
            reconnectTarget.body.getStyleClass().remove("target");
        }
        reconnectTarget = target;
        if (target != null) {
            target.body.getStyleClass().add("target");
        }
    }

    /** Aborts an in-progress drag, leaving the tree untouched. */
    private void cancelReconnect() {
        if (reconnectRubber != null) {
            world.getChildren().remove(reconnectRubber);
            reconnectRubber = null;
        }
        setReconnectTarget(null);
        reconnectSource = null;
    }

    /** A new parent may not be the node itself, nor anything hanging below it (that would loop). */
    private boolean isCompatibleTarget(NodeVisual candidate) {
        return candidate != reconnectSource
            && !isDescendant(candidate.entry, reconnectSource.entry);
    }

    private NodeVisual portAt(double worldX, double worldY) {
        NodeVisual best = null;
        double bestDistance = PORT_GRAB_RADIUS;
        for (NodeVisual visual : placed) {
            if (visual.port == null) {
                continue;
            }
            double distance = Math.hypot(worldX - visual.portX, worldY - visual.portY);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = visual;
            }
        }
        return best;
    }

    private NodeVisual nodeBodyAt(double worldX, double worldY) {
        double half = TechTreeCanvasLayout.NODE_SIZE / 2;
        for (NodeVisual visual : placed) {
            if (Math.abs(worldX - visual.centerX) <= half
                && Math.abs(worldY - visual.centerY) <= half) {
                return visual;
            }
        }
        return null;
    }

    private NodeVisual nearestCompatibleTarget(double worldX, double worldY) {
        NodeVisual best = null;
        double bestDistance = SNAP_RADIUS;
        for (NodeVisual visual : placed) {
            if (!isCompatibleTarget(visual)) {
                continue;
            }
            double distance = distanceToNode(visual, worldX, worldY);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = visual;
            }
        }
        return best;
    }

    /** Distance from the point to the node's 60px square (0 when inside it). */
    private static double distanceToNode(NodeVisual visual, double worldX, double worldY) {
        double half = TechTreeCanvasLayout.NODE_SIZE / 2;
        double dx = Math.max(Math.abs(worldX - visual.centerX) - half, 0);
        double dy = Math.max(Math.abs(worldY - visual.centerY) - half, 0);
        return Math.hypot(dx, dy);
    }

    /** Names the target as the source's parent, keeping the rest of its research node. */
    private void reparentTo(NodeVisual source, NodeVisual target) {
        ContentRecord record = recordOf(source.entry);
        if (record == null) {
            return;
        }
        // Same naming rule as the toolbar: a mod parent keeps the prefixed fullName the game
        // resolves first, a vanilla parent its bare name - the only spelling it can be found by.
        String parentName;
        if (target.entry.contentId() == null) {
            parentName = target.entry.label();
        } else {
            ContentRecord parentRecord = recordOf(target.entry);
            if (parentRecord == null) {
                return;
            }
            parentName = parentRecord.fullName(controller.project().internalName());
        }
        TechTreeRules.Node current = TechTreeRules.read(record.fields.get(TechTreeRules.FIELD));
        writeResearch(record, current.withParent(parentName));
    }

    /** Drops the source's parent; a root has none, so for a root the drop changes nothing. */
    private void removeFromTree(NodeVisual source) {
        ContentRecord record = recordOf(source.entry);
        if (record == null || TechTreeRules.isRoot(record.fields.get(TechTreeRules.FIELD))) {
            return;
        }
        writeResearch(record, TechTreeRules.Node.empty());
    }

    private ContentRecord recordOf(TechTreeIndex.Entry entry) {
        ModProject project = controller.project();
        return entry.contentId() == null || project == null
            ? null : project.contentById(entry.contentId());
    }

    /** True when {@code candidate} sits in {@code ancestor}'s subtree, so naming it a parent loops. */
    static boolean isDescendant(TechTreeIndex.Entry candidate, TechTreeIndex.Entry ancestor) {
        for (TechTreeIndex.Entry child : ancestor.children()) {
            if (sameEntry(candidate, child) || isDescendant(candidate, child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameEntry(TechTreeIndex.Entry a, TechTreeIndex.Entry b) {
        if (a.contentId() != null && b.contentId() != null) {
            return a.contentId().equals(b.contentId());
        }
        return a == b;
    }

    // --- planet-lane drags -----------------------------------------------------------------------

    /** The node a lane drag may start from: a record-backed node body under the press point.
     * Vanilla group nodes have no record, so they never drag into a lane. */
    private NodeVisual laneDragCandidateAt(double worldX, double worldY) {
        NodeVisual body = nodeBodyAt(worldX, worldY);
        return body != null && body.entry.contentId() != null ? body : null;
    }

    /**
     * Starts a lane drag from a node body (the threshold-crossing entry the canvas filter uses,
     * and a test seam). True when a record node sits under the point. Unlike the port gesture this
     * one takes nothing on press - only a moved drag turns the gesture on.
     */
    boolean beginLaneDragAt(double worldX, double worldY) {
        laneDragSource = laneDragCandidateAt(worldX, worldY);
        if (laneDragSource == null) {
            return false;
        }
        laneDragActive = true;
        beginLaneDrag();
        return true;
    }

    /** The ghost line from the dragged card to the cursor, drawn while the drag is active. */
    private void beginLaneDrag() {
        laneRubber = new Line(laneDragSource.centerX, laneDragSource.centerY,
            laneDragSource.centerX, laneDragSource.centerY);
        laneRubber.getStyleClass().add("tech-lane-rubber");
        laneRubber.setMouseTransparent(true);
        world.getChildren().add(laneRubber);
    }

    /** Moves the ghost line and highlights the lane a release would re-assign the node into. */
    void dragLaneTo(double worldX, double worldY) {
        if (!laneDragActive || laneDragSource == null) {
            return;
        }
        laneRubber.setEndX(worldX);
        laneRubber.setEndY(worldY);
        setLaneTarget(PlanetLanes.laneAt(lanes, worldX, worldY));
    }

    /**
     * Ends the drag: dropping into another planet's lane rewrites {@code research.planet} to that
     * lane's key; the node's own lane and blank space write nothing, the planet stays. The write
     * goes through {@code controller.setField}, so it is one undoable field command like every
     * other edit - the same single copy of the data the inspector edits.
     */
    boolean endLaneAt(double worldX, double worldY) {
        if (!laneDragActive || laneDragSource == null) {
            return false;
        }
        NodeVisual source = laneDragSource;
        PlanetLanes.Lane lane = PlanetLanes.laneAt(lanes, worldX, worldY);
        cancelLaneDrag();
        if (lane == null) {
            return true;
        }
        ContentRecord record = recordOf(source.entry);
        if (record == null) {
            return true;
        }
        TechTreeRules.Node current = TechTreeRules.read(record.fields.get(TechTreeRules.FIELD));
        if (lane.planet().equals(current.planet())) {
            return true; // dropped back into its own lane: nothing changes
        }
        writeResearch(record, current.withPlanet(lane.planet()));
        return true;
    }

    private void setLaneTarget(PlanetLanes.Lane target) {
        if (target == laneTarget
            || (target != null && laneTarget != null
                && target.planet().equals(laneTarget.planet()))) {
            return;
        }
        if (laneTarget != null) {
            Region overlay = laneOverlays.get(laneTarget.planet());
            if (overlay != null) {
                overlay.setVisible(false);
            }
        }
        laneTarget = target;
        if (target != null) {
            Region overlay = laneOverlays.get(target.planet());
            if (overlay != null) {
                overlay.setVisible(true);
            }
        }
    }

    /** Aborts an in-progress lane drag, leaving every planet untouched. */
    private void cancelLaneDrag() {
        if (laneRubber != null) {
            world.getChildren().remove(laneRubber);
            laneRubber = null;
        }
        setLaneTarget(null);
        laneDragSource = null;
        laneDragActive = false;
    }

    // --- test seams ------------------------------------------------------------------------------

    /** How many record-backed nodes the canvas currently shows (vanilla group nodes excluded). */
    int canvasNodeCount() {
        return visuals.size();
    }

    /** The style classes of one record's node body, for assertion purposes. */
    List<String> nodeStyles(String contentId) {
        NodeVisual visual = visuals.get(contentId);
        return visual == null ? List.of() : List.copyOf(visual.body.getStyleClass());
    }

    /** The research-cost chip row of one record's node (report P1-8), or null when it has none. */
    HBox costRow(String contentId) {
        NodeVisual visual = visuals.get(contentId);
        return visual == null ? null : visual.cost;
    }

    /** The laid-out centre of one record's node, or null. */
    double[] nodeCenter(String contentId) {
        NodeVisual visual = visuals.get(contentId);
        if (visual == null) {
            return null;
        }
        return new double[] {
            visual.body.getLayoutX() + TechTreeCanvasLayout.NODE_SIZE / 2,
            visual.body.getLayoutY() + TechTreeCanvasLayout.NODE_SIZE / 2};
    }

    ComboBox<String> childPicker() {
        return childPicker;
    }

    ComboBox<String> rootPicker() {
        return rootPicker;
    }

    Label hintLabel() {
        return hint;
    }

    /** Current zoom / pan of the canvas (test hooks for the view keys). */
    double zoomValue() {
        return zoom;
    }

    double panXValue() {
        return panX;
    }

    double panYValue() {
        return panY;
    }

    /** The input-port point of one record's node, or null (vanilla nodes have none). */
    double[] portWorld(String contentId) {
        NodeVisual visual = visuals.get(contentId);
        return visual == null || visual.port == null
            ? null : new double[] {visual.portX, visual.portY};
    }

    /** The laid-out centre of a placed vanilla group node by its bare name, or null. */
    double[] vanillaCenter(String label) {
        for (NodeVisual visual : placed) {
            if (visual.entry.contentId() == null && visual.entry.label().equals(label)) {
                return new double[] {visual.centerX, visual.centerY};
            }
        }
        return null;
    }

    boolean reconnectActive() {
        return reconnectSource != null;
    }

    /** The content id of the node a release would re-parent onto, or null when there is none. */
    String reconnectTargetId() {
        return reconnectTarget == null ? null : reconnectTarget.entry.contentId();
    }

    /** How many planet lanes the canvas currently draws (report P2-10, test hook). */
    int laneCount() {
        return lanes.size();
    }

    /** The lane planets, in draw order (test hook). */
    List<String> lanePlanets() {
        return lanes.stream().map(PlanetLanes.Lane::planet).toList();
    }

    /** True while a lane drag is active (test hook). */
    boolean laneDragActive() {
        return laneDragActive;
    }

    /** The planet of the lane a lane drag would drop into right now, or null (test hook). */
    String laneTargetPlanet() {
        return laneTarget == null ? null : laneTarget.planet();
    }

    /** The body node of one record's card, for firing real mouse events at it (test hook). */
    javafx.scene.Node nodeBody(String contentId) {
        NodeVisual visual = visuals.get(contentId);
        return visual == null ? null : visual.body;
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
}

package dev.modmaker.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.PlanetGrids;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.geometry.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The planets &amp; sectors workspace: an arrangement tree of the mod's planets with their sectors
 * grouped underneath (a sector declares its planet via the {@code planet} field; the game defaults
 * to serpulo). Vanilla planets that mod sectors reference appear as read-only group roots. Selecting
 * a planet or sector shows a live grid preview of that planet.
 *
 * <p>Report P2-10 (handover todo #2): dragging a sector row onto another planet row re-assigns the
 * sector's {@code planet} field through the same undoable {@code setField} command the toolbar uses;
 * the target row highlights while hovered, and a drop onto the sector's own planet is a no-op.
 */
public final class CosmosPane extends BorderPane {

    /** The drag flavour carrying a sector's record id from a source row to a planet row. */
    private static final DataFormat SECTOR_FLAVOUR = new DataFormat("mm/sector-id");

    private final ProjectController controller;
    private final TreeView<String> tree = new TreeView<>();
    private final PlanetPreview preview = new PlanetPreview();
    private ModProject project;
    /** The sector currently on the dragboard, kept on this side because a Dragboard's content is
     * only readable in the drop handlers, not while deciding what may be hovered. */
    private ContentRecord draggingSector;
    /**
     * Mesh models for a divisions-6 planet take a moment to build; while the user types in the
     * inspector (a project change per keystroke), coalesce the rebuilds into one per pause.
     */
    private final javafx.animation.PauseTransition previewDebounce =
        new javafx.animation.PauseTransition(javafx.util.Duration.millis(150));

    public CosmosPane(ProjectController controller) {
        this.controller = controller;
        getStyleClass().addAll("panel", "cosmos");

        previewDebounce.setOnFinished(event -> updatePreview());

        tree.setCellFactory(view -> new javafx.scene.control.TreeCell<>() {
            {
                // Row drag (report P2-10): a sector row is draggable, a planet row accepts the
                // drop. The tree is rebuilt from the record's fields on every edit, so this is the
                // minimal viable gesture - no external drop target, no serialised model on the
                // board, just the record id; the write itself is the undoable setField command.
                setOnDragDetected(event -> {
                    ContentRecord record = rowRecord();
                    if (record == null || record.type != ContentType.sector) {
                        return;
                    }
                    Dragboard board = startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent();
                    content.put(SECTOR_FLAVOUR, record.id);
                    board.setContent(content);
                    draggingSector = record;
                    event.consume();
                });
                setOnDragOver(event -> {
                    if (canDropHere(event)) {
                        event.acceptTransferModes(TransferMode.MOVE);
                    }
                });
                setOnDragEntered(event -> {
                    if (canDropHere(event)) {
                        getStyleClass().add("cosmos-drop-target");
                    }
                });
                setOnDragExited(event -> getStyleClass().remove("cosmos-drop-target"));
                setOnDragDropped(event -> {
                    boolean handled = false;
                    String planet = planetKey();
                    if (event.getDragboard().hasContent(SECTOR_FLAVOUR)
                        && draggingSector != null && planet != null) {
                        handled = reassignSector(draggingSector, planet);
                    }
                    event.setDropCompleted(handled);
                    getStyleClass().remove("cosmos-drop-target");
                    event.consume();
                });
                setOnDragDone(event -> draggingSector = null);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value);
                getStyleClass().remove("cosmos-drop-target");
            }

            private ContentRecord rowRecord() {
                TreeItem<String> item = getTreeItem();
                return item instanceof TaggedItem tagged ? tagged.record : null;
            }

            private String planetKey() {
                TreeItem<String> item = getTreeItem();
                return item instanceof TaggedItem tagged ? tagged.planetKey : null;
            }

            private boolean canDropHere(DragEvent event) {
                if (!event.getDragboard().hasContent(SECTOR_FLAVOUR) || draggingSector == null) {
                    return false;
                }
                String planet = planetKey();
                return planet != null && !alreadyOn(planet, draggingSector);
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((observable, was, item) -> {
            if (item != null && item.getValue() != null) {
                ContentRecord record = recordFor(item);
                if (record != null && record != controller.selected()) {
                    controller.select(record);
                }
            }
        });

        Button addPlanet = new Button(Messages.t("cosmos.add.planet"));
        addPlanet.getStyleClass().add("tool-button");
        addPlanet.setOnAction(event -> controller.addContent(ContentType.planet));
        Button addSector = new Button(Messages.t("cosmos.add.sector"));
        addSector.getStyleClass().add("tool-button");
        addSector.setOnAction(event -> addSectorUnderSelection());

        HBox buttons = new HBox(8, addPlanet, addSector, spacer(), previewTabs());
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.getStyleClass().addAll("sub-header", "cosmos-bar");
        buttons.setMinHeight(64);
        buttons.setPrefHeight(64);
        buttons.setMaxHeight(64);
        setTop(buttons);
        setCenter(tree);
        setRight(preview);
        preview.setPrefWidth(480);
        preview.setMinWidth(480);
        preview.setMaxWidth(480);

        controller.onProjectChanged(this::refresh);
        controller.onSelectionChanged(record -> updatePreview());
        refresh();
    }

    /**
     * The preview's tab selector, moved into the workspace bar and right-aligned above the preview
     * column (P3). PlanetPreview is headerless; these buttons own the selection.
     */
    private Region previewTabs() {
        ToggleGroup group = new ToggleGroup();
        // The strip spans the preview panel exactly (the panel's own padding makes the canvas 8px
        // narrower on each side); binding to the panel width keeps it flush with the panel edges
        // and with the assets chips' right edge at any window size.
        HBox box = new HBox(0);
        box.setMinWidth(0);
        box.prefWidthProperty().bind(preview.widthProperty());
        box.maxWidthProperty().bind(preview.widthProperty());
        for (int i = 0; i < preview.tabTitles().size(); i++) {
            int index = i;
            ToggleButton toggle = new ToggleButton(preview.tabTitles().get(i));
            toggle.getStyleClass().add("preview-tab");
            toggle.setToggleGroup(group);
            toggle.setSelected(preview.selectedTab() == index);
            toggle.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(toggle, Priority.ALWAYS);
            toggle.setOnAction(event -> preview.selectTab(index));
            box.getChildren().add(toggle);
        }
        return box;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** Adds a sector, defaulting its planet field to the currently selected planet. */
    private void addSectorUnderSelection() {
        ContentRecord selected = controller.selected();
        ContentRecord sector = controller.addContent(ContentType.sector);
        if (selected != null && selected.type == ContentType.planet) {
            controller.setField(sector, "planet", new com.google.gson.JsonPrimitive(selected.name));
        }
    }

    public void refresh() {
        project = controller.project();
        ContentRecord selected = controller.selected();
        tree.setRoot(buildTree());
        tree.setShowRoot(false);
        restoreTreeSelection(selected);
        // typing in the inspector fires a project change per keystroke; the preview rebuilds
        // once the keystrokes pause, not on every one of them
        previewDebounce.playFromStart();
    }

    /** Re-highlights the tree row of the selected record after a rebuild, so edits (which rebuild
     * the tree) do not make the selection visual vanish while the user is editing. */
    private void restoreTreeSelection(ContentRecord selected) {
        if (selected == null
            || (selected.type != ContentType.planet && selected.type != ContentType.sector)) {
            return;
        }
        findItem(tree.getRoot(), selected).ifPresent(item -> {
            tree.getSelectionModel().select(item);
        });
    }

    private java.util.Optional<TreeItem<String>> findItem(TreeItem<String> item,
        ContentRecord target) {
        if (item == null) {
            return java.util.Optional.empty();
        }
        if (item.getValue() != null) {
            ContentRecord record = recordFor(item);
            if (record == target) {
                return java.util.Optional.of(item);
            }
        }
        for (TreeItem<String> child : item.getChildren()) {
            var found = findItem(child, target);
            if (found.isPresent()) {
                return found;
            }
        }
        return java.util.Optional.empty();
    }

    /** Feeds the preview workbench from the selected record (planet or sector). */
    private void updatePreview() {
        preview.setSource(controller.selected(), project);
    }

    private TreeItem<String> buildTree() {
        TreeItem<String> root = new TreeItem<>();
        if (project == null) {
            return root;
        }
        String internal = project.internalName();

        Map<String, ContentRecord> planetsByName = new LinkedHashMap<>();
        java.util.LinkedHashSet<ContentRecord> planets = new java.util.LinkedHashSet<>();
        for (ContentRecord record : project.contents) {
            if (record.type == ContentType.planet && !record.isInline()) {
                planetsByName.put(record.name, record);
                planetsByName.put(record.fullName(internal), record);
                planets.add(record);
            }
        }

        Map<String, TreeItem<String>> groupItems = new LinkedHashMap<>();
        for (ContentRecord planet : planets) {
            TaggedItem item = new TaggedItem(nodeLabel(planet), planet, planet.name);
            item.setExpanded(true);
            groupItems.put(planet.name, item);
            root.getChildren().add(item);
        }

        for (ContentRecord sector : project.contents) {
            if (sector.type != ContentType.sector || sector.isInline()) {
                continue;
            }
            String target = sectorPlanet(sector);
            String bare = target.startsWith(internal + "-")
                ? target.substring(internal.length() + 1)
                : target;
            TreeItem<String> group = groupItems.get(bare);
            if (group == null) {
                boolean vanilla = registryVanilla(bare);
                String label = Messages.t("cosmos.vanilla") + " · " + bare;
                group = groupItems.computeIfAbsent(label, key -> {
                    TaggedItem item = new TaggedItem(key, null, bare);
                    item.setExpanded(true);
                    root.getChildren().add(item);
                    return item;
                });
                if (vanilla) {
                    group.setExpanded(true);
                }
            }
            group.getChildren().add(new TaggedItem(nodeLabel(sector), sector, null));
        }
        return root;
    }

    /** A tree row carrying its own payload: the record it shows and/or the planet key a drop onto
     * it would write (TreeItem has no properties map, so the drag handlers need a typed tag). */
    private static final class TaggedItem extends TreeItem<String> {
        private final ContentRecord record;
        private final String planetKey;

        TaggedItem(String value, ContentRecord record, String planetKey) {
            super(value);
            this.record = record;
            this.planetKey = planetKey;
        }
    }

    /** The planet a sector declares, or the game's default (serpulo). */
    private String sectorPlanet(ContentRecord sector) {
        JsonElement value = sector.fields.get("planet");
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || value.getAsString().isBlank()
            ? "serpulo"
            : value.getAsString();
    }

    /** True when the sector already names this planet, bare or mod-prefixed: a no-op drop. */
    private boolean alreadyOn(String planetKey, ContentRecord sector) {
        String current = sectorPlanet(sector);
        if (planetKey.equals(current)) {
            return true;
        }
        String internal = project == null ? "" : project.internalName();
        String bare = current.startsWith(internal + "-")
            ? current.substring(internal.length() + 1)
            : current;
        return planetKey.equals(bare);
    }

    /**
     * Writes a sector's {@code planet} field to the drop target's planet key (report P2-10): the
     * same undoable {@code setField} command the add-sector button uses, so the re-assignment is
     * one reversible step. Returns false, writing nothing, when the sector is already on that
     * planet - a no-op drop never reaches the undo stack. Package-private seam for the tests.
     */
    boolean reassignSector(ContentRecord sector, String planetKey) {
        if (sector == null || planetKey == null || planetKey.isBlank()
            || alreadyOn(planetKey, sector)) {
            return false;
        }
        controller.setField(sector, "planet", new JsonPrimitive(planetKey));
        return true;
    }

    private boolean registryVanilla(String name) {
        return controller.registry().isVanillaName(ContentType.planet, name);
    }

    private String nodeLabel(ContentRecord record) {
        return controller.displayName(record) + (record.patch ? " (" + Messages.t("inspector.patch") + ")" : "");
    }

    private ContentRecord recordFor(TreeItem<String> item) {
        if (project == null || item.getValue() == null) {
            return null;
        }
        for (ContentRecord record : project.contents) {
            if (record.isInline()) {
                continue;
            }
            if (record.type != ContentType.planet && record.type != ContentType.sector) {
                continue;
            }
            if (nodeLabel(record).equals(item.getValue())) {
                return record;
            }
        }
        return null;
    }

    /** Flat record names shown in the tree, prefixed "planet:"/"sector:"/"group:" (test hook). */
    List<String> flattenedTree() {
        List<String> out = new ArrayList<>();
        collect(tree.getRoot(), out);
        return out;
    }

    /** Preview hooks (tests). */
    boolean previewRendered() {
        return preview.rendered();
    }

    int previewCapacity() {
        return preview.previewCapacity();
    }

    int previewOccupiedCount() {
        return preview.previewOccupiedCount();
    }

    boolean previewAppearanceRendered() {
        return preview.appearanceRendered();
    }

    int previewSystemBodies() {
        return preview.systemBodies();
    }

    /** Selects a preview workbench tab (test hook): 0 grid, 1 appearance, 2 system. */
    void selectPreviewTab(int index) {
        preview.selectPreviewTab(index);
    }

    /** Snapshot of one preview tab's canvas (test hook): 0 grid, 1 appearance, 2 system. */
    javafx.scene.image.WritableImage previewSnapshot(int tabIndex) {
        return preview.snapshot(tabIndex);
    }

    /** Counts non-background pixels on a preview tab (test hook). */
    long previewNonBackgroundPixels(int tabIndex) {
        return preview.nonBackgroundPixels(tabIndex);
    }

    /** The summary line of a preview tab (test hook). */
    String previewInfo(int tabIndex) {
        return preview.infoForTab(tabIndex);
    }

    private void collect(TreeItem<String> item, List<String> out) {
        for (TreeItem<String> child : item.getChildren()) {
            ContentRecord record = recordFor(child);
            out.add((record == null ? "group: " : record.type == ContentType.planet ? "planet: " : "sector: ")
                + child.getValue());
            collect(child, out);
        }
    }
}

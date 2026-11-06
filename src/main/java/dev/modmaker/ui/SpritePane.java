package dev.modmaker.ui;

import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.fmt.SpriteRegions;
import dev.modmaker.core.model.AssetRef;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The sprite tab: the working environment for sprite work - every sprite asset of the project as a
 * thumbnail grid (the Aseprite paradigm from report P2-11), the atlas region the game will pack it
 * as, and the content records still missing their sprite. Sprites the mod references but the atlas
 * does not carry appear in the same grid as dashed placeholder cells, so the browse view never shows
 * a hole where a sprite should be.
 *
 * <p>Grid selection is multi-select: click picks one cell, Ctrl toggles a cell, Shift extends the
 * range from the anchor. The only batch action the grid offers is copying the selected atlas regions
 * to the clipboard - the panel is a browser, not an editor, and region names are what the json files
 * reference, so "select the sprites I am about to talk about" is the operation the view is for.
 */
public final class SpritePane extends BorderPane {

    /** Grid cell metrics: a 48px thumbnail over the region label, in the 260px left column. */
    private static final double CELL_WIDTH = 110;
    private static final double CELL_HEIGHT = 76;
    private static final double THUMB_SIZE = 48;
    private static final double GRID_GAP = 10;

    /**
     * Decodes thumbnails one at a time off the FX thread; daemon, so a pending decode never keeps
     * the JVM alive. One thread is enough - the work is a few milliseconds per sprite - and it
     * keeps a mod with hundreds of sprites from turning into hundreds of parallel file reads.
     */
    private static final ExecutorService THUMB_DECODER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modmaker-sprite-thumbs");
        thread.setDaemon(true);
        return thread;
    });

    private final ProjectController controller;
    private final TextField filter = new TextField();
    private final TilePane grid = new TilePane();
    private final Label selectionCount = new Label();
    private final Button copyRegions = new Button(Messages.t("sprite.copyRegions"));
    private final HBox selectionBar = new HBox(8, selectionCount, copyRegions);
    private final ImageView preview = new ImageView();
    private final Label info = new Label();
    private final ListView<ContentRecord> missing = new ListView<>();
    private ModProject project;
    /** Off while the bottom strip is hidden; the pane then waits for its next activation. */
    private boolean live = true;
    private boolean stale;
    private int refreshCount;

    /** The grid's cells in display order (real sprites first, then the missing placeholders). */
    private final List<SpriteCell> cells = new ArrayList<>();
    private final Set<String> selectedIds = new HashSet<>();
    /** The clicked cell Shift ranges extend from; survives refreshes by id, not by index. */
    private String anchorId;
    /** Decoded thumbnails by asset path, kept across refreshes like the SpriteStore images. */
    private final Map<String, Image> thumbs = new LinkedHashMap<>();
    /** Paths whose decode is running, and paths that do not decode at all; both FX-thread only. */
    private final Set<String> thumbsInFlight = new HashSet<>();
    private final Set<String> thumbsFailed = new HashSet<>();
    /** The live ImageView of every cell on screen, so a decode that finishes late can fill it. */
    private final Map<String, ImageView> thumbViews = new HashMap<>();

    /** One grid cell: a real sprite (path set) or the placeholder for a missing one. */
    private record SpriteCell(String id, String path, String region, boolean missing) {
    }

    public SpritePane(ProjectController controller) {
        this.controller = controller;
        getStyleClass().add("panel");

        filter.setPromptText(Messages.t("sprite.filter"));
        filter.textProperty().addListener((observable, was, now) -> refreshFileList());

        grid.getStyleClass().add("sprite-grid");
        grid.setHgap(GRID_GAP);
        grid.setVgap(GRID_GAP);
        grid.setPadding(new Insets(2));
        grid.setAlignment(Pos.TOP_LEFT);
        ScrollPane gridScroll = new ScrollPane(grid);
        gridScroll.setFitToWidth(true);
        gridScroll.getStyleClass().add("panel-scroll");

        selectionCount.getStyleClass().add("muted");
        copyRegions.getStyleClass().add("tool-button");
        copyRegions.setOnAction(event -> copySelection());
        selectionBar.getStyleClass().add("sprite-selection-bar");
        selectionBar.setAlignment(Pos.CENTER_LEFT);
        selectionBar.setVisible(false);
        selectionBar.setManaged(false);

        preview.setPreserveRatio(true);
        preview.setSmooth(false);
        preview.setFitWidth(440);
        preview.setFitHeight(320);
        info.getStyleClass().add("muted");
        info.setWrapText(true);

        missing.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ContentRecord record, boolean empty) {
                super.updateItem(record, empty);
                if (empty || record == null) {
                    setText(null);
                    return;
                }
                setText("[" + Messages.t("ctype." + record.type.name()) + "] " + record.name);
            }
        });
        missing.getSelectionModel().selectedItemProperty().addListener((observable, was, record) -> {
            if (record != null) {
                controller.select(record);
            }
        });

        Label missingTitle = new Label(Messages.t("sprite.missing"));
        missingTitle.getStyleClass().add("section-title");

        VBox left = new VBox(6, filter, gridScroll, selectionBar);
        VBox.setVgrow(gridScroll, Priority.ALWAYS);
        left.setPrefWidth(260);
        setLeft(left);
        setCenter(new VBox(8, preview, info));
        setRight(new VBox(6, missingTitle, missing));

        controller.onProjectChanged(this::onProjectChanged);
        refresh();
    }

    private void onProjectChanged() {
        if (live) {
            refresh();
        } else {
            stale = true;
        }
    }

    /** Disables the asset walk and missing-sprite scan while the pane is off screen. */
    public void setLive(boolean value) {
        live = value;
        if (live && stale) {
            stale = false;
            refresh();
        }
    }

    /** Refresh executions so far (test hook for the hidden-pane behaviour). */
    int refreshCount() {
        return refreshCount;
    }

    public void refresh() {
        refreshCount++;
        ModProject next = controller.project();
        if (next != project) {
            thumbs.clear();
            thumbsInFlight.clear();
            thumbsFailed.clear();
        }
        project = next;
        refreshFileList();
        refreshMissing();
    }

    // --- grid -----------------------------------------------------------------------------------

    private void refreshFileList() {
        // The selection (and with it the preview) survives a refresh: every keystroke in the
        // inspector fires a project change, and losing the preview on each one makes the tab
        // unusable. Only a cell that disappeared clears it.
        String previousAnchor = anchorId;
        Set<String> previousSelection = new HashSet<>(selectedIds);
        cells.clear();
        grid.getChildren().clear();
        thumbViews.clear();
        if (project == null) {
            anchorId = null;
            selectedIds.clear();
            preview.setImage(null);
            info.setText("");
            updateSelectionUi();
            return;
        }
        String query = filter.getText() == null ? "" : filter.getText().toLowerCase(Locale.ROOT);
        List<String> paths = new ArrayList<>();
        for (AssetRef asset : project.assets) {
            if (!asset.kind.equals("sprite") && !asset.kind.equals("spriteOverride")) {
                continue;
            }
            if (query.isEmpty() || asset.path.toLowerCase(Locale.ROOT).contains(query)) {
                paths.add(asset.path);
            }
        }
        paths.sort(String::compareTo);
        String internal = project.internalName();
        for (String path : paths) {
            cells.add(new SpriteCell(path, path, regionOfPath(internal, path), false));
        }
        for (ContentRecord record : SpriteRegions.withoutSprite(project, internal)) {
            String region = SpriteRegions.expectedRegion(record, internal);
            cells.add(new SpriteCell("missing:" + record.id, null, region, true));
        }
        for (int i = 0; i < cells.size(); i++) {
            grid.getChildren().add(cell(i));
        }

        selectedIds.clear();
        for (SpriteCell cell : cells) {
            if (previousSelection.contains(cell.id())) {
                selectedIds.add(cell.id());
            }
        }
        anchorId = previousAnchor != null && selectedIds.contains(previousAnchor)
            ? previousAnchor
            : (selectedIds.isEmpty() ? null : firstSelectedId());
        if (anchorId == null) {
            preview.setImage(null);
        }
        updateSelectionUi();
        previewPrimary();
        if (cells.stream().noneMatch(cell -> !cell.missing())) {
            info.setText(project.assets.isEmpty()
                ? Messages.t("sprite.noAssets")
                : Messages.t("sprite.empty"));
        }
    }

    private String firstSelectedId() {
        for (SpriteCell cell : cells) {
            if (selectedIds.contains(cell.id())) {
                return cell.id();
            }
        }
        return null;
    }

    /** Builds one grid cell; the click handler drives the whole selection model. */
    private Node cell(int index) {
        SpriteCell data = cells.get(index);
        VBox content = new VBox(4);
        content.setAlignment(Pos.CENTER);
        if (!data.missing()) {
            // Built empty and filled in later: the png is decoded on a worker, so the grid itself
            // stays cheap no matter how many sprites the project has.
            ImageView thumb = new ImageView();
            thumb.setPreserveRatio(true);
            thumb.setSmooth(false);
            thumb.setFitWidth(THUMB_SIZE);
            thumb.setFitHeight(THUMB_SIZE);
            content.getChildren().add(thumb);
            thumbViews.put(data.path(), thumb);
            requestThumbnail(data.path());
        }
        Label name = new Label(data.region());
        name.getStyleClass().add("sprite-cell-name");
        name.setMaxWidth(CELL_WIDTH - 16);
        content.getChildren().add(name);

        javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(content);
        stack.getStyleClass().add("sprite-cell");
        stack.setAlignment(Pos.CENTER);
        if (data.missing()) {
            stack.getStyleClass().add("missing");
            Tooltip.install(stack, new Tooltip(Messages.t("sprite.cellMissing", data.region())));
        }
        stack.setPrefSize(CELL_WIDTH, CELL_HEIGHT);
        stack.setMinSize(CELL_WIDTH, CELL_HEIGHT);
        stack.setUserData(data.id());
        stack.setOnMouseClicked(event -> pick(index, event.isControlDown(), event.isShiftDown()));
        return stack;
    }

    /**
     * Click semantics (the Aseprite paradigm): plain click selects only this cell, Ctrl toggles it,
     * Shift unions the anchor..target range onto the current selection.
     */
    private void pick(int index, boolean control, boolean shift) {
        SpriteCell data = cells.get(index);
        if (control) {
            if (!selectedIds.remove(data.id())) {
                selectedIds.add(data.id());
            }
            anchorId = data.id();
        } else if (shift && anchorId != null) {
            int anchor = indexOfId(anchorId);
            if (anchor >= 0) {
                for (int i : rangeIndices(anchor, index)) {
                    selectedIds.add(cells.get(i).id());
                }
            } else {
                selectedIds.clear();
                selectedIds.add(data.id());
                anchorId = data.id();
            }
        } else {
            selectedIds.clear();
            selectedIds.add(data.id());
            anchorId = data.id();
        }
        updateSelectionUi();
        previewPrimary();
    }

    private int indexOfId(String id) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void updateSelectionUi() {
        for (Node child : grid.getChildren()) {
            boolean on = selectedIds.contains(child.getUserData());
            child.getStyleClass().remove("selected");
            if (on) {
                child.getStyleClass().add("selected");
            }
        }
        boolean multi = selectedIds.size() > 1;
        selectionBarVisible(multi);
        if (multi) {
            selectionCount.setText(Messages.t("sprite.selected", selectedIds.size()));
        }
    }

    private void selectionBarVisible(boolean visible) {
        selectionBar.setVisible(visible);
        selectionBar.setManaged(visible);
    }

    /** The anchor cell drives the big preview; a placeholder explains what is missing instead. */
    private void previewPrimary() {
        int index = anchorId == null ? -1 : indexOfId(anchorId);
        SpriteCell anchor = index >= 0 ? cells.get(index) : null;
        if (anchor == null) {
            preview.setImage(null);
            info.setText("");
            return;
        }
        if (anchor.missing()) {
            preview.setImage(null);
            info.setText(Messages.t("sprite.cellMissing", anchor.region()));
            return;
        }
        show(anchor.path());
    }

    /** Batch action: the selected cells' atlas regions, one per line, onto the clipboard. */
    private void copySelection() {
        List<String> regions = selectedRegions();
        if (regions.isEmpty()) {
            return;
        }
        String text = clipboardText(regions);
        try {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        } catch (RuntimeException failure) {
            // A locked clipboard (another app holds it open) must not eat the feedback below.
        }
        controller.log("copied " + regions.size() + " sprite regions to the clipboard");
        info.setText(Messages.t("sprite.copied", regions.size()));
    }

    /**
     * Fills a cell's thumbnail from the cache, or queues its decode on the worker. Decoding every
     * sprite synchronously while the grid was built is what froze the window for seconds the first
     * time a mod with hundreds of sprites was opened: JavaFX's {@code Image(InputStream)} performs
     * the decode on the calling thread, and this grid is built on the FX thread.
     */
    private void requestThumbnail(String path) {
        Image cached = thumbs.get(path);
        if (cached != null) {
            showThumbnail(path, cached);
            return;
        }
        if (thumbsFailed.contains(path) || !thumbsInFlight.add(path)) {
            return;
        }
        ModProject owner = project;
        Path root = project == null ? null : project.root;
        if (root == null) {
            thumbsInFlight.remove(path);
            thumbsFailed.add(path);
            return;
        }
        try {
            THUMB_DECODER.execute(() -> {
                Image decoded = decodeThumbnail(root, path);
                runOnFxThread(() -> publishThumbnail(owner, path, decoded));
            });
        } catch (RejectedExecutionException shuttingDown) {
            // The worker is already gone (teardown): leave the cell empty rather than throw here.
            thumbsInFlight.remove(path);
        }
    }

    /** Runs on the decoder thread: reads and scales the png without touching the scene graph. */
    private static Image decodeThumbnail(Path root, String path) {
        Path file = dev.modmaker.core.io.ProjectIo.safeResolve(root, path);
        if (file == null) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Image image = new Image(in, THUMB_SIZE, THUMB_SIZE, true, false);
            return image.isError() || image.getWidth() <= 0 ? null : image;
        } catch (Exception failure) {
            return null;
        }
    }

    /**
     * Runs on the FX thread once a decode finishes. A result for a project that was replaced is
     * dropped - the new grid has its own cache - and a path that does not decode is remembered, so
     * a broken file is not re-read on every refresh.
     */
    private void publishThumbnail(ModProject owner, String path, Image decoded) {
        if (owner != project) {
            return;
        }
        thumbsInFlight.remove(path);
        if (decoded == null) {
            thumbsFailed.add(path);
            return;
        }
        thumbs.put(path, decoded);
        showThumbnail(path, decoded);
    }

    /** Hands a decoded image to the cell on screen for this path, if the grid still lists it. */
    private void showThumbnail(String path, Image image) {
        ImageView view = thumbViews.get(path);
        if (view != null) {
            view.setImage(image);
        }
    }

    /**
     * Posts a UI update from the decoder thread. Headless tests have no toolkit and no cells to
     * update, so a late decode there is simply dropped instead of throwing.
     */
    private static void runOnFxThread(Runnable action) {
        try {
            Platform.runLater(action);
        } catch (IllegalStateException toolkitNotInitialized) {
            // No toolkit: nothing is on screen, nothing to update.
        }
    }

    // --- pure grid maths (unit-tested without the toolkit) ---------------------------------------

    /** Indices a Shift click covers from the anchor to the target: inclusive, order independent. */
    static List<Integer> rangeIndices(int anchor, int target) {
        List<Integer> out = new ArrayList<>();
        for (int i = Math.min(anchor, target); i <= Math.max(anchor, target); i++) {
            out.add(i);
        }
        return out;
    }

    /** The atlas region a listed asset path previews as (packSprites naming, override unprefixed). */
    static String regionOfPath(String internalName, String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        boolean override = path.startsWith("sprites-override/");
        return NameRules.spriteRegion(internalName, base, !override);
    }

    /** The expected regions behind the missing-sprite placeholders, in the order the grid appends. */
    static List<String> placeholderRegions(List<ContentRecord> missingRecords, String internalName) {
        List<String> out = new ArrayList<>();
        for (ContentRecord record : missingRecords) {
            out.add(SpriteRegions.expectedRegion(record, internalName));
        }
        return out;
    }

    /** Clipboard text for a batch selection: one region per line, in grid order. */
    static String clipboardText(List<String> regions) {
        return String.join("\n", regions);
    }

    // --- preview ---------------------------------------------------------------------------------

    private void show(String path) {
        info.setText("");
        if (path == null || project == null || project.root == null) {
            return;
        }
        String region = regionOfPath(project.internalName(), path);
        boolean override = path.startsWith("sprites-override/");

        try (InputStream in = Files.newInputStream(safeAssetPath(path))) {
            Image image = new Image(in, 0, 0, true, false);
            if (image.isError() || image.getWidth() <= 0) {
                info.setText(Messages.t("sprite.loadFail"));
                preview.setImage(null);
                return;
            }
            preview.setImage(image);
            info.setText(Messages.t("sprite.file") + ": " + path + "\n"
                + Messages.t("sprite.region") + ": " + region
                + (override ? " " + Messages.t("sprite.overrideTag") : "") + "\n"
                + Messages.t("sprite.size") + ": " + (int) image.getWidth() + "×" + (int) image.getHeight());
        } catch (Exception failure) {
            preview.setImage(null);
            info.setText(Messages.t("sprite.loadFail") + ": " + failure.getMessage());
        }
    }

    private void refreshMissing() {
        missing.getItems().clear();
        if (project == null) {
            return;
        }
        missing.setItems(FXCollections.observableArrayList(
            SpriteRegions.withoutSprite(project, project.internalName())));
        missing.setPlaceholder(new Label(Messages.t("sprite.none")));
    }

    // --- test hooks ------------------------------------------------------------------------------

    /** Sprite paths currently listed (test hook, real cells in grid order). */
    List<String> listedFiles() {
        List<String> out = new ArrayList<>();
        for (SpriteCell cell : cells) {
            if (!cell.missing()) {
                out.add(cell.path());
            }
        }
        return List.copyOf(out);
    }

    /** Grid cells on screen, placeholders included (test hook). */
    int cellCount() {
        return cells.size();
    }

    /** Placeholder cells for sprites the mod references but the atlas lacks (test hook). */
    int placeholderCount() {
        return (int) cells.stream().filter(SpriteCell::missing).count();
    }

    /** The cell node at a grid index (test hook, for synthetic clicks). */
    Node cellAt(int index) {
        return grid.getChildren().get(index);
    }

    /** The sprite path currently picked as the anchor cell, or null (test hook). */
    String selectedFile() {
        if (anchorId == null) {
            return null;
        }
        int index = indexOfId(anchorId);
        return index >= 0 && !cells.get(index).missing() ? anchorId : null;
    }

    /** Selected cell ids in grid order (test hook). */
    List<String> selectedIds() {
        List<String> out = new ArrayList<>();
        for (SpriteCell cell : cells) {
            if (selectedIds.contains(cell.id())) {
                out.add(cell.id());
            }
        }
        return out;
    }

    /** The clipboard text the batch copy would produce (test hook). */
    String selectionRegions() {
        return clipboardText(selectedRegions());
    }

    private List<String> selectedRegions() {
        List<String> out = new ArrayList<>();
        for (SpriteCell cell : cells) {
            if (selectedIds.contains(cell.id())) {
                out.add(cell.region());
            }
        }
        return out;
    }

    /** Selects a listed sprite the way a click does (test hook). */
    void selectFile(String path) {
        int index = indexOfId(path);
        if (index < 0) {
            return;
        }
        selectedIds.clear();
        selectedIds.add(path);
        anchorId = path;
        updateSelectionUi();
        previewPrimary();
    }

    /** True while a preview image is shown (test hook). */
    boolean previewLoaded() {
        return preview.getImage() != null;
    }

    /** Names of records listed as missing their sprite (test hook). */
    List<String> missingNames() {
        List<String> names = new ArrayList<>();
        for (ContentRecord record : missing.getItems()) {
            names.add(record.name);
        }
        return names;
    }

    /** Loads the preview image for a path without selecting it (test hook). */
    boolean canLoad(String path) {
        if (project == null || project.root == null) {
            return false;
        }
        try (InputStream in = Files.newInputStream(safeAssetPath(path))) {
            Image image = new Image(in);
            return !image.isError() && image.getWidth() > 0;
        } catch (Exception failure) {
            return false;
        }
    }

    /**
     * Asset paths come from the imported package; refuse anything that would read outside the
     * project folder (defence in depth - the reader already skips such entries).
     */
    private Path safeAssetPath(String path) throws java.io.IOException {
        Path target = dev.modmaker.core.io.ProjectIo.safeResolve(project.root, path);
        if (target == null) {
            throw new java.io.IOException("asset path escapes the project folder: " + path);
        }
        return target;
    }
}

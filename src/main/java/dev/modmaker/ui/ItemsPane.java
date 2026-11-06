package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.validate.Issue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The resource page: a square card grid over one content type at a time, a page-dot strip, and the
 * "preview" panel to its right. Layout follows UI/资源管理-export.html - cards are 128x128 with a
 * 64px type-coloured block, eight to a row, paged when the list is longer than the viewport.
 *
 * <p>Selection drives the shared inspector on the right (the 属性栏), which is where every field is
 * edited, so the grid itself stays read-only. The same class backs both the Resources tab (all
 * browsable types) and the Unit Builder tab (units only).
 */
public final class ItemsPane extends BorderPane {

    /** Card metrics taken from the export: 128x128, 28px column gap, 32px row gap. */
    static final double CARD_WIDTH = 128;
    static final double CARD_HEIGHT = 128;
    /** The type-coloured square at the top of a card. */
    static final double ICON_SIZE = 64;
    private static final double GAP = 28;
    private static final double VGAP = 32;
    private static final double GRID_PADDING = 24;

    private final ProjectController controller;
    private final TilePane grid = new TilePane();
    private final HBox pager = new HBox(10);
    private final Label emptyHint = new Label();

    private ContentType type = ContentType.item;
    private int page;
    private int columns = 4;
    private int perPage = 4;

    public ItemsPane(ProjectController controller) {
        this(controller, ContentType.item);
    }

    public ItemsPane(ProjectController controller, ContentType initialType) {
        this.controller = controller;
        this.type = initialType;
        getStyleClass().add("panel");

        grid.getStyleClass().add("asset-grid");
        grid.setHgap(GAP);
        grid.setVgap(VGAP);
        grid.setPadding(new Insets(GRID_PADDING));
        grid.setAlignment(Pos.TOP_CENTER);

        pager.getStyleClass().add("pager");
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.setPadding(new Insets(0, GRID_PADDING, 0, GRID_PADDING));

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("panel-scroll");
        scroll.viewportBoundsProperty().addListener((observable, was, bounds) -> {
            layoutColumns(bounds.getWidth());
            layoutRows(bounds.getHeight());
        });

        emptyHint.getStyleClass().add("empty-hint");
        emptyHint.setPadding(new Insets(24, GRID_PADDING, 0, GRID_PADDING));
        emptyHint.setVisible(false);
        emptyHint.setManaged(false);

        VBox gridColumn = new VBox(pager, emptyHint, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        setCenter(gridColumn);

        controller.onSelectionChanged(this::syncSelection);
        controller.onProjectChanged(this::refresh);
        // The error dots come from the published validation pass; repaint when a new one lands
        // without stealing the pager back to page 0.
        controller.onValidationRefreshed(this::render);
        refresh();
    }

    /** Shows one content type; the type chips in the tab's sub-header drive this. */
    public void showType(ContentType next) {
        if (next == null || next == type) {
            return;
        }
        type = next;
        page = 0;
        refresh();
    }

    /** The type currently listed (test hook). */
    ContentType listedType() {
        return type;
    }

    private void layoutColumns(double width) {
        if (width <= 0) {
            return;
        }
        columns = Math.max(1,
            (int) Math.floor((width - 2 * GRID_PADDING + GAP) / (CARD_WIDTH + GAP)));
        grid.setPrefColumns(columns);
    }

    private void layoutRows(double height) {
        if (height <= 0) {
            return;
        }
        int rows = Math.max(1,
            (int) Math.floor((height - 2 * GRID_PADDING + VGAP) / (CARD_HEIGHT + VGAP)));
        perPage = Math.max(1, rows * columns);
        render();
    }

    private void syncSelection(ContentRecord record) {
        for (Node tile : grid.getChildren()) {
            tile.getStyleClass().remove("selected");
        }
        if (record == null || record.type != type) {
            return;
        }
        for (Node tile : grid.getChildren()) {
            if (tile.getUserData() == record) {
                tile.getStyleClass().add("selected");
            }
        }
    }

    private Region tile(ContentRecord record, boolean hasError) {
        Region icon = new Region();
        icon.getStyleClass().add("tile-icon");
        // The width is fixed here rather than only in CSS: a plain Region does not grow from a
        // stylesheet width until a later pass, so the card's colour block would otherwise be 0 wide.
        icon.setMinSize(ICON_SIZE, ICON_SIZE);
        icon.setPrefSize(ICON_SIZE, ICON_SIZE);
        icon.setMaxSize(ICON_SIZE, ICON_SIZE);
        icon.setStyle("-fx-background-color: " + record.type.color() + ";");

        Label name = new Label(controller.displayName(record));
        name.getStyleClass().add("tile-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMaxWidth(CARD_WIDTH - 16);
        Label id = new Label(record.name);
        id.getStyleClass().add("tile-id");
        id.setTextOverrun(OverrunStyle.ELLIPSIS);
        id.setMaxWidth(CARD_WIDTH - 16);

        VBox body = new VBox(2, icon, name, id);
        body.setAlignment(Pos.CENTER);

        StackPane tile = new StackPane(body);
        tile.getStyleClass().add("tile");
        tile.setAlignment(Pos.CENTER);
        tile.setUserData(record);
        tile.setPrefSize(CARD_WIDTH, CARD_HEIGHT);
        tile.setMinSize(CARD_WIDTH, CARD_HEIGHT);
        tile.setMaxSize(CARD_WIDTH, CARD_HEIGHT);
        if (hasError) {
            Region bad = new Region();
            bad.getStyleClass().add("tile-bad");
            StackPane.setAlignment(bad, Pos.TOP_RIGHT);
            StackPane.setMargin(bad, new Insets(6, 6, 0, 0));
            tile.getChildren().add(bad);
        }
        tile.setOnMouseClicked(event -> {
            if (record != controller.selected()) {
                controller.select(record);
            }
        });
        return tile;
    }

    private List<ContentRecord> listed() {
        if (controller.project() == null) {
            return List.of();
        }
        return controller.project().contents.stream()
            .filter(record -> record.type == type)
            .sorted(Comparator.comparing((ContentRecord record) -> record.name))
            .toList();
    }

    /** One validation pass for the whole grid: the error dot comes from the same source as the
     * validation pane, instead of a second, drifting notion of "bad record". */
    private Set<String> errorIds() {
        if (controller.project() == null) {
            return Set.of();
        }
        return controller.latestIssues().stream()
            .filter(Issue::isError)
            .map(Issue::contentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    public void refresh() {
        page = 0;
        render();
    }

    private void render() {
        // Labels are refreshed here rather than at construction so a language switch reaches them.
        emptyHint.setText(Messages.t("assets.empty"));
        List<ContentRecord> all = listed();
        if (all.isEmpty()) {
            grid.getChildren().clear();
            pager.getChildren().clear();
            pager.setVisible(false);
            pager.setManaged(false);
            emptyHint.setVisible(true);
            emptyHint.setManaged(true);
            syncSelection(controller.selected());
            return;
        }
        emptyHint.setVisible(false);
        emptyHint.setManaged(false);
        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) perPage));
        page = Math.min(page, pages - 1);
        int from = page * perPage;
        int to = Math.min(all.size(), from + perPage);

        grid.getChildren().clear();
        Set<String> errors = errorIds();
        for (ContentRecord record : all.subList(from, to)) {
            grid.getChildren().add(tile(record, errors.contains(record.id)));
        }
        renderPager(pages);
        syncSelection(controller.selected());
    }

    /** Page dots, the prototype's 60px strip. Hidden while everything fits on one page. */
    private void renderPager(int pages) {
        pager.getChildren().clear();
        pager.setVisible(pages > 1);
        pager.setManaged(pages > 1);
        for (int i = 0; i < pages; i++) {
            Region dot = new Region();
            dot.getStyleClass().add("pager-dot");
            if (i == page) {
                dot.getStyleClass().add("active");
            }
            int target = i;
            dot.setOnMouseClicked(event -> {
                page = target;
                render();
            });
            pager.getChildren().add(dot);
        }
    }

    /** Tiles on the current page (test hook). */
    int tileCount() {
        return grid.getChildren().size();
    }

    /** Columns the grid last fitted into its viewport (test hook). */
    int columnCount() {
        return columns;
    }

    /** Page dots currently shown (test hook). */
    int pageCount() {
        return pager.isVisible() ? pager.getChildren().size() : 0;
    }
}

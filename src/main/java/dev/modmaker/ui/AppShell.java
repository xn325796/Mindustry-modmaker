package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.Workspace;
import dev.modmaker.core.io.Autosave;
import dev.modmaker.core.io.ExportReport;
import dev.modmaker.core.io.ImportCancelledException;
import dev.modmaker.core.io.ImportProgress;
import dev.modmaker.core.io.LinkGraph;
import dev.modmaker.core.io.ProjectIo;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.pipeline.BuildPipeline;
import dev.modmaker.core.validate.Issue;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Dragboard;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** The application window: menus, palette, canvas, inspector, bottom tabs and the log. */
public final class AppShell extends BorderPane {

    /**
     * TEMPORARY: the bottom strip (strings / sprites / validation / log plus the status bar) is
     * hidden while the layout is being worked on. The four panes are still built and stay reachable
     * through {@link #selectTab}, so flipping this back to true is the whole restore.
     */
    private static final boolean BOTTOM_STRIP_VISIBLE = false;

    private final ProjectController controller;
    private final NodeCanvasPane canvas;
    private final RecordInspector inspector;
    private final StringsPane strings;
    private final SpritePane sprites;
    private final IssuesPane issues;
    /** The Resources tab: a card grid over every file-backed type, one type at a time. */
    private final ItemsPane resourcesPane;
    /** The Unit Builder tab: the same grid restricted to units. */
    private final ItemsPane unitPane;
    private final CosmosPane cosmosPane;
    private final TechTreePane techTreePane;
    private javafx.scene.control.TabPane centerTabs;
    private javafx.scene.control.TabPane bottomTabs;
    private final TextArea logArea = new TextArea();
    private final ComboBox<Board> boardBox = new ComboBox<>();
    /** Per-block link view: the record whose links the canvas shows, or null for the whole board. */
    private final ComboBox<String> focusBox = new ComboBox<>();
    private final List<ContentRecord> focusChoices = new ArrayList<>();
    private final javafx.scene.control.TextField searchBox = new javafx.scene.control.TextField();
    private final Label status = new Label();
    /** Import feedback in the status bar: a progress bar plus its cancel, hidden until an import. */
    private final ProgressBar importProgress = new ProgressBar();
    private final Button importCancel = new Button();
    /** Toggle for the collapsible log drawer that sits above the status bar. */
    private final Button logToggle = new Button();
    private final VBox logDrawer = new VBox(logArea);
    /** Set by the cancel button; read by the import's {@link ImportProgress} on the io thread. */
    private final AtomicBoolean importCancelled = new AtomicBoolean();
    private boolean logDrawerVisible;
    /** Import generation: a finished import must not hide the chrome a newer one already armed. */
    private int importSeq;
    /** Per-type record counts shown at the right of every palette row. */
    private final Map<ContentType, Label> paletteCounts = new java.util.EnumMap<>(ContentType.class);
    /** Type the Resources grid is showing. */
    private ContentType resourceType = ContentType.item;
    /** Rail buttons of the assets workspace, kept so a search jump can move the active mark. */
    private final java.util.Map<ContentType, Button> railButtons = new java.util.LinkedHashMap<>();
    /** Window buttons of the frameless title strip; wired by {@link #setWindowActions}. */
    private Runnable minimizeAction = () -> { };
    private Runnable closeAction = () -> { };
    /** True while the window fills the screen's work area (see {@link #maximizeWindow()}). */
    private boolean windowMaximized;
    /** The window's bounds before it was maximized, so restoring puts it back where it was. */
    private javafx.geometry.Rectangle2D windowedBounds;
    /** Where inside the title strip the drag started, for moving the undecorated window. */
    private double dragOffsetX;
    private double dragOffsetY;
    /** One definition per command: menu item, toolbar button and enabled state all derive from it. */
    private final UiAction undoAction;
    private final UiAction redoAction;
    private final UiAction searchAction;
    private final UiAction validateAction;
    private final UiAction buildAction;
    private final UiAction launchAction;
    private final UiAction stopAction;
    private final UiAction openLogAction;
    /** The single preview surface, in the context panel's 预览 tab. */
    private final RecordPreview previewPane;
    /** Rebuilt on a language switch; the four tabs it hosts live in AppShell fields. */
    private ContextPanel contextPanel;
    /** The running test-play process, if any; drives the stop/log buttons. */
    private GameLauncher.Session gameSession;
    /** True while a test session is being built and launched; guards against double F6 clicks. */
    private boolean launching;

    public AppShell(ProjectController controller) {
        this.controller = controller;
        undoAction = new UiAction("menu.edit.undo", controller::undo,
            javafx.scene.input.KeyCombination.keyCombination("Ctrl+Z"), this::appendLog);
        redoAction = new UiAction("menu.edit.redo", controller::redo,
            javafx.scene.input.KeyCombination.keyCombination("Ctrl+Y"), this::appendLog);
        searchAction = new UiAction("menu.project.search", () -> {
            searchBox.requestFocus();
            searchBox.selectAll();
        }, javafx.scene.input.KeyCombination.keyCombination("Ctrl+F"), this::appendLog);
        validateAction = new UiAction("toolbar.validate", this::validate,
            javafx.scene.input.KeyCombination.keyCombination("F5"), this::appendLog);
        buildAction = new UiAction("menu.build.folder", this::buildFolder,
            javafx.scene.input.KeyCombination.keyCombination("Ctrl+B"), this::appendLog);
        launchAction = new UiAction("menu.build.launch", this::launchGame,
            javafx.scene.input.KeyCombination.keyCombination("F6"), this::appendLog);
        stopAction = new UiAction("toolbar.stopGame", this::stopGame, null, this::appendLog);
        openLogAction = new UiAction("toolbar.openLog", this::openGameLog, null, this::appendLog);
        previewPane = new RecordPreview(controller);
        controller.onSelectionChanged(previewPane::show);
        this.canvas = new NodeCanvasPane(controller);
        this.inspector = new RecordInspector(controller);
        this.strings = new StringsPane(controller);
        this.sprites = new SpritePane(controller);
        this.issues = new IssuesPane(controller);
        this.resourcesPane = new ItemsPane(controller, ContentType.item);
        this.unitPane = new ItemsPane(controller, ContentType.unit);
        this.cosmosPane = new CosmosPane(controller);
        this.techTreePane = new TechTreePane(controller);
        // The bottom strip is hidden (see BOTTOM_STRIP_VISIBLE). Keep its three heavy panes idle so
        // a keystroke does not rebuild the string table, re-walk 1500 sprites and re-run validation
        // for views the user cannot see. selectTab() activates the pane on demand.
        strings.setLive(BOTTOM_STRIP_VISIBLE);
        sprites.setLive(BOTTOM_STRIP_VISIBLE);
        issues.setLive(BOTTOM_STRIP_VISIBLE);

        getStyleClass().add("app-root");
        controller.setLogSink(message -> Platform.runLater(() -> appendLog(message)));
        controller.onProjectChanged(this::refreshProjectState);
        controller.setOverwriteApprover(this::approveOverwrite);
        // Validation runs on the file worker over a snapshot, not on the FX thread; panes render
        // the last published pass and are repainted through onValidationRefreshed.
        controller.useValidationWorker(ioExecutor);
        controller.onValidationRefreshed(this::reportRequestedValidation);
        // The context panel's head tracks the selection and the published findings. The wiring
        // lives on the shell, not the panel: a language switch rebuilds the panel, and a rebuilt
        // pane must not re-register controller listeners.
        controller.onSelectionChanged(record -> refreshContextHead());
        controller.onProjectChanged(this::refreshContextHead);
        controller.onValidationRefreshed(this::refreshContextHead);

        boardBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Board board) {
                return board == null ? "" : board.name;
            }

            @Override
            public Board fromString(String text) {
                return null;
            }
        });
        boardBox.valueProperty().addListener((observable, was, board) -> {
            if (board != null && board != controller.board()) {
                controller.setBoard(board);
            }
        });

        logArea.setEditable(false);
        logArea.getStyleClass().add("log-area");
        logDrawer.getStyleClass().add("log-drawer");
        logDrawer.setPrefHeight(170);
        logDrawer.setVisible(false);
        logDrawer.setManaged(false);
        importProgress.setVisible(false);
        importProgress.setManaged(false);
        importCancel.setVisible(false);
        importCancel.setManaged(false);
        importCancel.setOnAction(event -> {
            importCancelled.set(true);
            importCancel.setDisable(true);
        });

        rebuild();
        Messages.onChange(this::rebuild);
        refreshProjectState();
        autosaveTimer.scheduleWithFixedDelay(this::autosaveTick,
            AUTOSAVE_INTERVAL.toMillis(), AUTOSAVE_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    // --- layout ---------------------------------------------------------------------------------

    private void rebuild() {
        // A rebuild (the language menu goes through here) must not throw the user back to the first
        // tab of either strip, nor forget a test run that is still going.
        int centerSelection = centerTabs == null ? 0 : centerTabs.getSelectionModel().getSelectedIndex();
        int bottomSelection = bottomTabs == null ? 0 : bottomTabs.getSelectionModel().getSelectedIndex();

        // The 属性栏 starts at the tab strip's top edge, so it lives in a BorderPane beside the tab
        // strip rather than below the whole header block.
        setTop(new VBox(buildTitleStrip(), buildCommandBar()));
        setLeft(null);
        BorderPane main = new BorderPane();
        main.setCenter(buildCenter());
        previewPane.show(controller.selected());
        contextPanel = new ContextPanel(controller, inspector, previewPane, issues, strings);
        inspector.setOpenStrings(contextPanel::showStrings);
        main.setRight(contextPanel);
        setCenter(main);
        // The strip is rebuilt either way so its panes stay wired and selectable; only the
        // attachment to the window follows the switch. The status bar itself is always attached:
        // it carries the import progress and the log drawer toggle.
        Region bottomStrip = buildBottom();
        VBox bottom = new VBox();
        if (BOTTOM_STRIP_VISIBLE) {
            bottom.getChildren().add(bottomStrip);
        }
        bottom.getChildren().addAll(logDrawer, buildStatusBar());
        setBottom(bottom);

        if (centerSelection > 0 && centerSelection < centerTabs.getTabs().size()) {
            centerTabs.getSelectionModel().select(centerSelection);
        }
        if (bottomSelection > 0 && bottomSelection < bottomTabs.getTabs().size()) {
            bottomTabs.getSelectionModel().select(bottomSelection);
        }
        refreshGameButtons();
        refreshProjectState();
    }

    /** Repaints the context panel's selection head; the panel is rebuilt on a language switch. */
    private void refreshContextHead() {
        if (contextPanel != null) {
            contextPanel.updateHead(controller.selected());
        }
    }

    /**
     * The toolbar is rebuilt from scratch on a language switch: the test-run buttons have to be
     * put back into the state the live session implies, or "stop game" and "open test log" come
     * back disabled while the game is still running.
     */
    private void refreshGameButtons() {
        boolean running = gameSession != null && gameSession.process().isAlive();
        stopAction.enabled.set(running);
        openLogAction.enabled.set(gameSession != null);
    }

    /**
     * The main area: six tabs, matching 参考 (1)/(2).svg. Each tab's content is a 64px sub-header
     * (its own type chips or controls) over the view that does the work. The last tab is a disabled
     * placeholder for the not-yet-implemented scripting workspace.
     */
    private Region buildCenter() {
        centerTabs = new javafx.scene.control.TabPane(
            workspaceTab(Workspace.assets, assetsView()),
            workspaceTab(Workspace.canvas, canvasView()),
            workspaceTab(Workspace.units, unitPane),
            workspaceTab(Workspace.cosmos, cosmosPane),
            workspaceTab(Workspace.techTree, techTreePane),
            workspaceTab(Workspace.script, scriptPlaceholder()));
        centerTabs.getStyleClass().add("center-tabs");
        return centerTabs;
    }

    private javafx.scene.control.Tab workspaceTab(Workspace workspace, javafx.scene.Node view) {
        VBox content = new VBox();
        Region subHeader = buildSubHeader(workspace);
        if (subHeader != null) {
            content.getChildren().add(subHeader);
        }
        content.getChildren().add(view);
        VBox.setVgrow(view, Priority.ALWAYS);
        javafx.scene.control.Tab tab =
            new javafx.scene.control.Tab(Messages.t("workspace." + workspace), content);
        tab.setClosable(false);
        // The scripting workspace has no implementation behind it yet; the tab says so instead of
        // pretending to work.
        tab.setDisable(workspace == Workspace.script);
        return tab;
    }

    /** The assets workspace: the 120px vertical type rail beside the resource grid. */
    private Region assetsView() {
        Region rail = typeRail(Workspace.assetTypes());
        HBox.setHgrow(resourcesPane, Priority.ALWAYS);
        HBox row = new HBox(rail, resourcesPane);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    /** The block builder: the 320px type palette beside the node canvas (参考 (1).svg). */
    private Region canvasView() {
        Region palette = buildPalette();
        HBox.setHgrow(canvas, Priority.ALWAYS);
        HBox row = new HBox(palette, canvas);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    /** The 48px row under the tab strip, or null for workspaces that bring their own controls. */
    private Region buildSubHeader(Workspace workspace) {
        // The tech tree's toolbar is a complete sub-header row of its own (it lives outside the
        // pane, so it sits flush with the other rows).
        if (workspace == Workspace.techTree) {
            return techTreePane.toolbar();
        }
        HBox row = new HBox(10);
        row.getStyleClass().add("sub-header");
        row.setAlignment(Pos.CENTER_LEFT);
        switch (workspace) {
            case assets -> {
                // The assets workspace's type switch is its own 120px rail (see assetsView), so it
                // needs no toolbar row.
                return null;
            }
            case units -> {
                // Units are one kind of content, so a one-chip row would only take space; the
                // add/remove actions are all this workspace needs in the sub-header.
                row.getChildren().add(spacer());
                row.getChildren().add(recordActions(() -> ContentType.unit));
            }
            case canvas -> {
                Region tools = buildToolBar();
                HBox.setHgrow(tools, Priority.ALWAYS);
                row.getChildren().add(tools);
            }
            default -> {
                // Cosmos / script keep their own in-pane controls (or are a placeholder);
                // reserving a blank 48px row there just steals space.
                return null;
            }
        }
        return row;
    }

    /** Shown in place of a workspace that has no implementation behind it yet. */
    private javafx.scene.Node scriptPlaceholder() {
        Label hint = new Label(Messages.t("script.placeholder"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setMaxWidth(520);
        VBox box = new VBox(hint);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** One rail row per content type, plus add/remove pinned to the bottom of the rail. */
    private Region typeRail(java.util.List<ContentType> types) {
        VBox rail = new VBox();
        rail.getStyleClass().add("type-rail");
        railButtons.clear();
        for (ContentType type : types) {
            Button button = new Button(Messages.t("ctype." + type.name()));
            button.getStyleClass().add("rail-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(event -> showAssetsType(type));
            if (type == resourceType) {
                button.getStyleClass().add("active");
            }
            railButtons.put(type, button);
            rail.getChildren().add(button);
        }
        Region slack = new Region();
        VBox.setVgrow(slack, Priority.ALWAYS);
        rail.getChildren().add(slack);

        Button add = new Button(Messages.t("assets.add.new"));
        add.getStyleClass().addAll("rail-button", "rail-action");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(event -> withProject(() -> controller.addContent(resourceType)));

        Button delete = new Button(Messages.t("assets.delete"));
        delete.getStyleClass().addAll("rail-button", "rail-action");
        delete.setMaxWidth(Double.MAX_VALUE);
        delete.setOnAction(event -> {
            if (controller.selected() != null) {
                controller.deleteContent(controller.selected());
            }
        });
        rail.getChildren().addAll(add, delete);
        return rail;
    }

    /** Add / remove for the record list a sub-header shows. */
    private Region recordActions(java.util.function.Supplier<ContentType> type) {
        Button add = toolButton("assets.add.new",
            () -> withProject(() -> controller.addContent(type.get())));
        Button delete = toolButton("assets.delete", () -> {
            if (controller.selected() != null) {
                controller.deleteContent(controller.selected());
            }
        });
        HBox box = new HBox(8, add, delete);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(0, 18, 0, 0));
        // The chips beside these buttons can give up width, they cannot: without pinning the box a
        // narrow window squeezes the two buttons into "..." instead of shrinking the chip row.
        box.setMinWidth(Region.USE_PREF_SIZE);
        return box;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** Brings the workspace tab that edits the given content type to the front. */
    public void showWorkspace(Workspace workspace) {
        if (centerTabs == null) {
            return;
        }
        centerTabs.getTabs().stream()
            .filter(tab -> Messages.t("workspace." + workspace).equals(tab.getText()))
            .findFirst().ifPresent(tab -> centerTabs.getSelectionModel().select(tab));
    }

    /**
     * The 36px title strip of the frameless window: the menu on the left, the window buttons on the
     * right, and the empty space between them as the drag handle. Putting the menu here rather than
     * in a row of its own keeps a whole 64px band for content (参考 (1)/(2).svg reserved 36px for
     * this strip and the buttons sit at its right edge either way).
     */
    private Region buildTitleStrip() {
        HBox strip = new HBox();
        strip.getStyleClass().add("title-strip");
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getChildren().add(buildMenuBar());
        strip.getChildren().add(spacer());
        strip.getChildren().addAll(windowButtons());

        strip.setOnMousePressed(event -> {
            dragOffsetX = event.getScreenX() - windowX();
            dragOffsetY = event.getScreenY() - windowY();
        });
        strip.setOnMouseDragged(event -> {
            if (isWindowMaximized()) {
                return;
            }
            javafx.stage.Window window = strip.getScene() == null ? null : strip.getScene().getWindow();
            if (window != null) {
                window.setX(event.getScreenX() - dragOffsetX);
                window.setY(event.getScreenY() - dragOffsetY);
            }
        });
        strip.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY
                && event.getClickCount() == 2) {
                toggleMaximized();
            }
        });
        return strip;
    }

    /** Minimize / maximize-restore / close, drawn by us because the window has no decorations. */
    private java.util.List<javafx.scene.Node> windowButtons() {
        Button minimize = new Button("\u2013");
        minimize.getStyleClass().addAll("window-button", "minimize");
        minimize.setTooltip(new javafx.scene.control.Tooltip(Messages.t("window.minimize")));
        minimize.setOnAction(event -> minimizeAction.run());

        Button toggle = new Button("\u25a1");
        toggle.getStyleClass().addAll("window-button", "maximize");
        toggle.setTooltip(new javafx.scene.control.Tooltip(Messages.t("window.toggle")));
        toggle.setOnAction(event -> toggleMaximized());

        Button close = new Button("\u2715");
        close.getStyleClass().addAll("window-button", "close");
        close.setTooltip(new javafx.scene.control.Tooltip(Messages.t("window.close")));
        close.setOnAction(event -> closeAction.run());

        return java.util.List.of(minimize, toggle, close);
    }

    /**
     * Wires the title strip's minimize and close buttons to the real window. Called by {@code App}
     * right after the stage exists; the shell itself never touches a stage.
     */
    public void setWindowActions(Runnable minimize, Runnable close) {
        this.minimizeAction = minimize == null ? () -> { } : minimize;
        this.closeAction = close == null ? () -> { } : close;
    }

    /**
     * Fills the screen's work area - the frameless stand-in for maximize.
     *
     * <p>{@code Stage.setMaximized} would be the obvious call, but on Windows a maximized
     * undecorated stage covers the taskbar, so it reads as fullscreen and the taskbar becomes
     * unreachable. Setting the bounds ourselves keeps the taskbar in view, and remembering the
     * windowed bounds makes the restore predictable.
     */
    public void maximizeWindow() {
        if (window() instanceof javafx.stage.Stage stage) {
            windowedBounds = new javafx.geometry.Rectangle2D(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            javafx.geometry.Rectangle2D work = screenOf(stage).getVisualBounds();
            stage.setX(work.getMinX());
            stage.setY(work.getMinY());
            stage.setWidth(work.getWidth());
            stage.setHeight(work.getHeight());
            windowMaximized = true;
        }
    }

    /** Toggles between the work area and the bounds {@link #maximizeWindow()} remembered. */
    private void toggleMaximized() {
        if (!(window() instanceof javafx.stage.Stage stage)) {
            return;
        }
        if (!windowMaximized) {
            maximizeWindow();
            return;
        }
        if (windowedBounds != null) {
            stage.setX(windowedBounds.getMinX());
            stage.setY(windowedBounds.getMinY());
            stage.setWidth(windowedBounds.getWidth());
            stage.setHeight(windowedBounds.getHeight());
        }
        windowMaximized = false;
    }

    /** The screen a window mostly sits on, so maximizing never jumps to another display. */
    private static javafx.stage.Screen screenOf(javafx.stage.Window window) {
        double centerX = window.getX() + window.getWidth() / 2;
        double centerY = window.getY() + window.getHeight() / 2;
        for (javafx.stage.Screen screen : javafx.stage.Screen.getScreens()) {
            if (screen.getBounds().contains(centerX, centerY)) {
                return screen;
            }
        }
        return javafx.stage.Screen.getPrimary();
    }

    /** Current window x, or 0 when the shell is not attached to a window (tests). */
    private double windowX() {
        return window() == null ? 0 : window().getX();
    }

    private double windowY() {
        return window() == null ? 0 : window().getY();
    }

    private boolean isWindowMaximized() {
        return windowMaximized;
    }

    private javafx.stage.Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private MenuBar buildMenuBar() {
        MenuBar bar = new MenuBar();
        // The commands that also live in the command bar come from the shared UiActions, so a menu
        // entry and its button share one enabled state and one accelerator.
        MenuItem undoItem = undoAction.menuItem();
        MenuItem redoItem = redoAction.menuItem();
        MenuItem saveItem = action("menu.project.save", this::save);
        saveItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("Ctrl+S"));
        MenuItem searchItem = searchAction.menuItem();
        MenuItem validateItem = validateAction.menuItem();
        MenuItem buildItem = buildAction.menuItem();
        MenuItem launchItem = launchAction.menuItem();

        bar.getMenus().addAll(
            menu("menu.project",
                action("menu.project.new", this::newProject),
                action("menu.project.open", this::openProject),
                new SeparatorMenuItem(),
                action("menu.import.mod", this::importZip),
                action("menu.import.folder", this::importFolder),
                new SeparatorMenuItem(),
                saveItem,
                action("menu.project.meta", this::showProjectMeta),
                searchItem,
                new SeparatorMenuItem(),
                action("menu.project.exit", this::exitApp)),
            menu("menu.edit", undoItem, redoItem),
            menu("menu.format",
                action("menu.format.json", () -> convertFormat("json")),
                action("menu.format.hjson", () -> convertFormat("hjson"))),
            menu("menu.build",
                validateItem,
                new SeparatorMenuItem(),
                buildItem,
                action("menu.build.zip", this::buildZip),
                new SeparatorMenuItem(),
                launchItem),
            menu("menu.view",
                action("menu.view.tidyLayout", controller::tidyLayout),
                action("menu.view.rebuildLinks", controller::rebuildLinks),
                action("menu.view.fit", canvas::fitToContent),
                action("menu.view.board", this::addBoard)),
            buildLanguageMenu(),
            menu("menu.help", action("menu.help.about", this::about),
                action("menu.help.shortcuts", this::showCanvasShortcuts)));
        return bar;
    }

    private Menu buildLanguageMenu() {
        Menu menu = new Menu(Messages.t("menu.language"));
        MenuItem english = new MenuItem("English");
        english.setOnAction(event -> Messages.setLocale(Locale.ENGLISH));
        MenuItem chinese = new MenuItem("简体中文");
        chinese.setOnAction(event -> Messages.setLocale(Locale.SIMPLIFIED_CHINESE));
        menu.getItems().addAll(english, chinese);
        return menu;
    }

    /** Adds content and brings the workspace that edits it to the front. */
    private void addContentRouted(ContentType type) {
        withProject(() -> {
            Workspace workspace = Workspace.of(type);
            if (workspace == Workspace.canvas) {
                Point2D center = canvas.viewportCenterWorld();
                double cascade = canvas.nextCascade();
                controller.addContent(type,
                    center.getX() - ContentCard.WIDTH / 2 + cascade,
                    center.getY() - ContentCard.HEIGHT / 2 + cascade);
            } else {
                controller.addContent(type);
                if (workspace == Workspace.assets) {
                    showAssetsType(type);
                }
                showWorkspace(workspace);
            }
        });
    }

    /**
     * The global commands, one definition each: the menu items and these buttons share the same
     * {@link UiAction}, so their enabled state can never drift apart. Undo/redo/search/build are
     * reachable from every workspace here, not just from the canvas toolbar.
     */
    private Region buildCommandBar() {
        searchBox.setPromptText(Messages.t("toolbar.search.prompt"));
        searchBox.setPrefWidth(230);
        searchBox.setOnAction(event -> searchNext());

        javafx.scene.control.CheckBox adaptive = new javafx.scene.control.CheckBox(Messages.t("toolbar.adaptive"));
        adaptive.getStyleClass().add("tool-check");
        adaptive.setSelected(controller.adaptiveClasses());
        adaptive.selectedProperty().addListener((observable, was, now) ->
            controller.setAdaptiveClasses(Boolean.TRUE.equals(now)));
        javafx.scene.control.Tooltip.install(adaptive,
            new javafx.scene.control.Tooltip(Messages.t("toolbar.adaptive.tip")));

        HBox bar = new HBox(8,
            undoAction.button(), redoAction.button(), new Separator(Orientation.VERTICAL),
            searchBox, new Separator(Orientation.VERTICAL),
            validateAction.button(), buildAction.button(), openLogAction.button(),
            launchAction.button(), stopAction.button(),
            spacer(), adaptive);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("command-bar");
        return bar;
    }

    /** The canvas's own 48px row: the per-block link view switcher and the board selector. */
    private Region buildToolBar() {
        boardBox.setPrefWidth(140);
        focusBox.setPrefWidth(190);
        focusBox.valueProperty().addListener((observable, was, now) -> {
            int index = focusBox.getSelectionModel().getSelectedIndex();
            canvas.setFocusRecord(index <= 0 ? null
                : index - 1 < focusChoices.size() ? focusChoices.get(index - 1) : null);
        });
        // The content canvas has no toolbar of its own, so its minimap switch lives on this row.
        javafx.scene.control.ToggleButton minimapToggle =
            new javafx.scene.control.ToggleButton(Messages.t("canvas.minimap"));
        minimapToggle.getStyleClass().add("tool-button");
        minimapToggle.selectedProperty().addListener((observable, was, now) ->
            canvas.setMinimapVisible(now));
        // Edge routing style (report P2-9): one button cycles bezier / orthogonal / straight.
        javafx.scene.control.Button edgeStyleButton = new javafx.scene.control.Button();
        edgeStyleButton.getStyleClass().addAll("tool-button", "edge-style-button");
        edgeStyleButton.setTooltip(new javafx.scene.control.Tooltip(
            Messages.t("canvas.edgestyle.tip")));
        edgeStyleButton.setOnAction(event -> {
            canvas.cycleEdgeStyle();
            edgeStyleButton.setText(edgeStyleLabel(canvas.edgeStyle()));
        });
        controller.onProjectChanged(() ->
            edgeStyleButton.setText(edgeStyleLabel(canvas.edgeStyle())));
        edgeStyleButton.setText(edgeStyleLabel(canvas.edgeStyle()));
        HBox bar = new HBox(8, focusBox, new javafx.scene.control.Separator(
            javafx.geometry.Orientation.VERTICAL), boardBox, minimapToggle, edgeStyleButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("tool-bar");
        return bar;
    }

    /** Toolbar label for one edge routing style (report P2-9). */
    private static String edgeStyleLabel(EdgePathMath.Style style) {
        return switch (style) {
            case BEZIER -> Messages.t("canvas.edgestyle.bezier");
            case ORTHOGONAL -> Messages.t("canvas.edgestyle.orthogonal");
            case STRAIGHT -> Messages.t("canvas.edgestyle.straight");
        };
    }

    /** Syncs the per-block link view switcher with the project's records. */
    private void refreshFocusBox() {
        focusChoices.clear();
        if (controller.project() != null) {
            for (ContentRecord record : controller.project().contents) {
                if (!record.isInline() && record.type.fileBacked()) {
                    focusChoices.add(record);
                }
            }
        }
        List<String> names = new ArrayList<>();
        names.add(Messages.t("canvas.focus.all"));
        focusChoices.forEach(record -> names.add(controller.displayName(record)));
        focusBox.getItems().setAll(names);
        if (canvas.focusRecord() == null) {
            focusBox.getSelectionModel().select(0);
        }
    }

    /**
     * Brings a type to the front of the assets grid. {@code resourceType} must move with it: the
     * sub-header's Add button creates {@code resourceType}, and a search jump to a status record
     * must not leave that button still adding an item.
     */
    private void showAssetsType(ContentType type) {
        resourceType = type;
        resourcesPane.showType(type);
        for (java.util.Map.Entry<ContentType, Button> entry : railButtons.entrySet()) {
            entry.getValue().getStyleClass().remove("active");
            if (entry.getKey() == type) {
                entry.getValue().getStyleClass().add("active");
            }
        }
    }

    /** Jumps to the next content whose name contains the search text; Enter repeats. */
    private void searchNext() {
        String query = searchBox.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty() || controller.project() == null) {
            return;
        }
        List<ContentRecord> matches = controller.project().contents.stream()
            .filter(record -> !record.isInline())
            .filter(record -> controller.displayName(record).toLowerCase(Locale.ROOT).contains(query)
                || record.name.toLowerCase(Locale.ROOT).contains(query))
            .toList();
        if (matches.isEmpty()) {
            appendLog("no content matches '" + searchBox.getText().trim() + "'");
            return;
        }
        int index = controller.selected() != null ? matches.indexOf(controller.selected()) : -1;
        ContentRecord match = matches.get((index + 1) % matches.size());
        controller.select(match);
        Workspace target = Workspace.of(match.type);
        // The resource grid lists one type at a time, so bring the match's type to the front.
        if (target == Workspace.assets) {
            showAssetsType(match.type);
        }
        showWorkspace(target);
        if (target == Workspace.canvas) {
            canvas.revealRecord(match);
        }
        appendLog("found '" + controller.displayName(match) + "' (" + match.type + ")"
            + (matches.size() > 1 ? " [" + (matches.indexOf(match) + 1) + "/" + matches.size() + "]" : ""));
    }

    /**
     * The 240px type palette beside the node canvas (参考 (1).svg): click a row to create content of
     * that type, drag it onto the canvas to place the card exactly where it is dropped.
     */
    private Region buildPalette() {
        VBox box = new VBox(4);
        box.getStyleClass().add("panel");
        for (ContentType type : ContentType.creatable()) {
            box.getChildren().add(paletteRow(type));
        }
        // Bullets / unit commands / stances only exist inline inside a host file. They are not
        // creatable here, so listing them as inert rows was a dead affordance.

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefWidth(320);
        scroll.setMinWidth(320);
        scroll.getStyleClass().add("panel-scroll");
        return scroll;
    }

    private Region paletteRow(ContentType type) {
        // 16px category colour bar, the localised name, then "en · count" on the right.
        Region bar = new Region();
        bar.getStyleClass().add("palette-bar");
        bar.setStyle("-fx-background-color: " + type.color() + ";");

        Label name = new Label(controller.typeName(type));
        name.getStyleClass().add("palette-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label meta = new Label();
        meta.getStyleClass().add("palette-meta");
        paletteCounts.put(type, meta);

        HBox row = new HBox(bar, name, spacer, meta);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("palette-row");
        if (type.fileBacked()) {
            row.setOnMouseClicked(event -> addContentRouted(type));
            // Drag a palette row onto the canvas to place the card exactly there.
            row.setOnDragDetected(event -> {
                Dragboard board = row.startDragAndDrop(javafx.scene.input.TransferMode.COPY);
                board.setContent(Map.of(NodeCanvasPane.CONTENT_TYPE_FORMAT, type.name()));
                event.consume();
            });
        }
        return row;
    }

    /** "items · 3" for a category row; the English folder name is the stable identifier. */
    private void refreshPaletteCounts() {
        for (Map.Entry<ContentType, Label> entry : paletteCounts.entrySet()) {
            ContentType type = entry.getKey();
            int count = 0;
            if (controller.project() != null) {
                for (ContentRecord record : controller.project().contents) {
                    if (record.type == type) {
                        count++;
                    }
                }
            }
            entry.getValue().setText(type.folderName() + " · " + count);
        }
    }

    private Region buildBottom() {
        // 属性/校验/文本 now live in the right-hand ContextPanel and the log in the drawer above
        // the status bar; the (hidden) strip keeps only the sprite list.
        bottomTabs = new TabPane(tab("bottom.spritesTab", sprites));
        bottomTabs.setPrefHeight(210);
        bottomTabs.getStyleClass().add("bottom-tabs");
        return bottomTabs;
    }

    private Region buildStatusBar() {
        status.getStyleClass().add("status-text");
        importProgress.getStyleClass().add("import-progress");
        importProgress.setPrefWidth(220);
        importCancel.getStyleClass().addAll("status-button", "import-cancel");
        importCancel.setText(Messages.t("statusbar.cancel"));
        logToggle.getStyleClass().addAll("status-button", "log-toggle");
        logToggle.setText(Messages.t(logDrawerVisible ? "statusbar.logHide" : "statusbar.log"));
        logToggle.setOnAction(event -> setLogDrawerVisible(!logDrawerVisible));
        Region leftSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);
        HBox bar = new HBox(10, status, leftSpacer, importProgress, importCancel, rightSpacer,
            logToggle);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 12, 4, 12));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // --- actions --------------------------------------------------------------------------------

    /** True when the current project may be replaced: clean, or the user confirmed discarding. */
    private boolean confirmDiscardUnsaved() {
        if (controller.project() == null || !controller.isDirty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
            Messages.t("dialog.discard.body"), ButtonType.YES, ButtonType.CANCEL);
        alert.setHeaderText(Messages.t("dialog.discard.header"));
        return alert.showAndWait().filter(answer -> answer == ButtonType.YES).isPresent();
    }

    private void newProject() {
        if (!confirmDiscardUnsaved()) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog("my-mod");
        dialog.setHeaderText(Messages.t("dialog.modName"));
        Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.t("dialog.projectParent"));
        // Without a starting folder the dialog opens wherever Windows was last, which is easily a
        // drive root; the OS then refuses to create the project there and the failure used to leak
        // out as a bare AccessDeniedException. Start at the user's home instead.
        chooser.setInitialDirectory(defaultProjectParent());
        File parent = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (parent == null) {
            return;
        }
        Path root = parent.toPath().resolve(safeFolderName(name.get()));
        if (!approveOverwrite(root)) {
            return;
        }
        // Prepare the folder here, on the FX thread, so a location the OS refuses (a drive root, a
        // protected folder) is reported as such instead of surfacing from inside the save as an
        // opaque "AccessDeniedException: <path>".
        if (!canPrepareProjectFolder(root)) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                Messages.t("dialog.projectDirNotWritable", root));
            alert.setHeaderText(Messages.t("dialog.projectDirNotWritable.header"));
            alert.showAndWait();
            return;
        }
        runIo(() -> controller.createProject(name.get().trim(), root), false);
    }

    /**
     * Creates the project folder and proves it is writable before the controller saves into it.
     * Package-private so the UI smoke test can exercise the probe without a directory chooser.
     */
    static boolean canPrepareProjectFolder(Path root) {
        try {
            Files.createDirectories(root);
            // createDirectories is a no-op when the folder already exists, so it says nothing about
            // write access: probe with a scratch file that is removed again.
            Files.deleteIfExists(Files.createTempFile(root, ".mm-write-probe", ".tmp"));
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    /**
     * The starting folder for the new-project chooser: the user's home directory.
     *
     * <p>Deliberately not the Desktop. Some security products guard it and refuse to let an
     * ordinary program create *new* entries there (writing inside an existing subfolder is fine),
     * so a Desktop default sends the user straight into a refusal. The home directory is not
     * guarded that way. Package-private so the UI smoke test can lock this in.
     */
    static File defaultProjectParent() {
        return Path.of(System.getProperty("user.home", ".")).toFile();
    }

    private void openProject() {
        if (!confirmDiscardUnsaved()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.t("dialog.openProject"));
        File folder = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (folder == null) {
            return;
        }
        runIo(() -> {
            Path root = folder.toPath();
            var loaded = controller.readProject(root);
            // Publishing touches the model every view reads, so it happens on the FX thread.
            onFxThread(() -> {
                controller.publishProject(loaded, root);
                maybeRecoverAutosave(root);
                return null;
            });
        }, true);
    }

    private void importZip() {
        if (!confirmDiscardUnsaved()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.t("dialog.importMod"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Mindustry mod", "*.zip", "*.jar"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) {
            return;
        }
        importInto(file.toPath());
    }

    private void importFolder() {
        if (!confirmDiscardUnsaved()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.t("dialog.importMod"));
        File folder = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (folder == null) {
            return;
        }
        importInto(folder.toPath());
    }

    private void importInto(Path source) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.t("dialog.projectParent"));
        File destination = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (destination == null) {
            return;
        }
        // A large mod reads, builds and writes thousands of files on the io thread; the status
        // bar's progress bar is its window into that work and the cancel button stops it between
        // files. The generation guard keeps a finished import from hiding chrome a newer import
        // has already re-armed.
        importCancelled.set(false);
        int seq = ++importSeq;
        setImportChromeVisible(true);
        status.setText(Messages.t("status.importing"));
        appendLog("import started: " + source);
        ImportProgress progress = new ImportProgress() {
            @Override
            public void stageStarted(ImportProgress.Stage stage, int total) {
                Platform.runLater(() -> {
                    importProgress.setProgress(total > 0 ? 0 : ProgressBar.INDETERMINATE_PROGRESS);
                    status.setText(Messages.t("status.importing") + " · "
                        + Messages.t(importStageKey(stage)));
                });
            }

            @Override
            public void progressed(ImportProgress.Stage stage, int done, int total) {
                Platform.runLater(() -> {
                    importProgress.setProgress(total > 0 ? (double) done / total : 1);
                    status.setText(Messages.t("status.importing") + " · "
                        + Messages.t(importStageKey(stage)) + " " + done + "/" + total);
                });
            }

            @Override
            public boolean isCancelled() {
                return importCancelled.get();
            }
        };
        runIo(() -> {
            try {
                var imported = controller.readImport(source, destination.toPath(), progress);
                onFxThread(() -> {
                    controller.publishImport(imported, source);
                    return null;
                });
            } catch (ImportCancelledException cancelled) {
                // ProjectController already logged what it cleaned up; a deliberate cancel is not
                // an error and must not raise the failure dialog.
            } finally {
                Platform.runLater(() -> {
                    if (seq == importSeq) {
                        setImportChromeVisible(false);
                    }
                });
            }
        }, true);
    }

    /** Message key naming an import stage for the status line. */
    private static String importStageKey(ImportProgress.Stage stage) {
        return switch (stage) {
            case READING -> "import.stage.reading";
            case BUILDING -> "import.stage.building";
            case WRITING -> "import.stage.writing";
        };
    }

    /** Show or hide the progress bar and its cancel button; each import gets a live cancel. */
    private void setImportChromeVisible(boolean visible) {
        importProgress.setVisible(visible);
        importProgress.setManaged(visible);
        importCancel.setVisible(visible);
        importCancel.setManaged(visible);
        importCancel.setDisable(false);
        if (!visible) {
            importProgress.setProgress(0);
        }
    }

    /** Open or collapse the log drawer and relabel its toggle. */
    private void setLogDrawerVisible(boolean visible) {
        logDrawerVisible = visible;
        logDrawer.setVisible(visible);
        logDrawer.setManaged(visible);
        logToggle.setText(Messages.t(visible ? "statusbar.logHide" : "statusbar.log"));
    }

    /**
     * Saves on the worker thread without ever reading the model there: the plan is built on the FX
     * thread, which owns the model, and only the file writing happens in the background.
     */
    private void save() {
        runIo(() -> {
            var plan = onFxThread(controller::planSave);
            writePlan(plan);
            onFxThread(() -> {
                controller.markSaved();
                discardAutosaveQuietly();
                return null;
            });
        }, false);
    }

    // --- autosave -------------------------------------------------------------------------------

    /** Periodic timer entry: only a dirty project is snapshotted, and always back on the FX thread. */
    private void autosaveTick() {
        Platform.runLater(() -> {
            if (controller.project() == null || controller.project().root == null
                || !controller.isDirty()) {
                return;
            }
            autosaveNow();
        });
    }

    /** Writes the current state to {@code modmaker/autosave/} on the file worker. */
    private void autosaveNow() {
        ioExecutor.execute(() -> {
            try {
                var snapshot = onFxThread(controller::snapshotProject);
                Autosave.write(snapshot);
            } catch (Exception failure) {
                errors.reportQuietly("autosave failed", failure);
            }
        });
    }

    /** Offers to recover an autosave newer than the project's last save. Called after an open. */
    public void maybeRecoverAutosave(Path root) {
        if (root == null || !Autosave.isNewerThan(root)) {
            return;
        }
        ButtonType recover = new ButtonType(Messages.t("button.recoverAutosave"),
            javafx.scene.control.ButtonBar.ButtonData.APPLY);
        ButtonType discard = new ButtonType(Messages.t("button.discardAutosave"),
            javafx.scene.control.ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, Messages.t("dialog.autosaveRecover.body"),
            recover, discard);
        alert.setHeaderText(Messages.t("dialog.autosaveRecover.header"));
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isEmpty() || answer.get() == discard) {
            discardAutosaveQuietly(root);
            return;
        }
        Path projectRoot = root;
        runIo(() -> {
            var recovered = Autosave.restore(projectRoot, controller.registry());
            onFxThread(() -> {
                controller.publishProject(recovered, projectRoot);
                return null;
            });
        }, true);
    }

    private void discardAutosaveQuietly() {
        if (controller.project() != null && controller.project().root != null) {
            discardAutosaveQuietly(controller.project().root);
        }
    }

    private void discardAutosaveQuietly(Path root) {
        try {
            Autosave.discard(root);
        } catch (Exception failure) {
            errors.reportQuietly("could not discard the autosave", failure);
        }
    }

    /**
     * Menu「格式」: converts every content file and the meta file to the target extension, then
     * saves so the on-disk folder (and every later build) uses the new format.
     */
    private void convertFormat(String ext) {
        if (controller.project() == null) {
            return;
        }
        int files = dev.modmaker.core.io.FormatConverter.pendingCount(controller.project(), ext);
        boolean meta = dev.modmaker.core.io.FormatConverter.metaPending(controller.project(), ext);
        if (files == 0 && !meta) {
            new Alert(Alert.AlertType.INFORMATION, Messages.t("dialog.convert.none")).showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, Messages.t("dialog.convert.confirm",
            ext.toUpperCase(Locale.ROOT), files, meta ? Messages.t("dialog.convert.metaToo") : ""));
        confirm.setHeaderText(Messages.t("dialog.convert.title"));
        confirm.showAndWait().filter(response -> response == ButtonType.OK).ifPresent(response ->
            runIo(() -> {
                ProjectController.ConvertPlan convert = onFxThread(() -> controller.planConvert(ext));
                writePlan(convert.save());
                onFxThread(() -> {
                    controller.afterConvert(convert);
                    return null;
                });
            }, false));
    }

    /** The project metadata dialog: the fields the game reads to decide whether to load the mod.
     *  Fields commit live (each keystroke/checkbox flip) so the user sees the validator react. */
    private void showProjectMeta() {
        if (controller.project() == null) {
            appendLog(Messages.t("meta.noProject"));
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(Messages.t("meta.dialog.title"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.getStyleClass().add("meta-grid");

        int row = 0;
        // The internal name is structural (folder, bundle prefix, references): shown, not edited.
        grid.addRow(row++, new Label(Messages.t("meta.field.name")),
            new Label(controller.project().meta.name));
        grid.addRow(row++, new Label(Messages.t("meta.field.displayName")), metaTextField("displayName"));
        grid.addRow(row++, new Label(Messages.t("meta.field.author")), metaTextField("author"));
        grid.addRow(row++, new Label(Messages.t("meta.field.subtitle")), metaTextField("subtitle"));
        grid.addRow(row++, new Label(Messages.t("meta.field.version")), metaTextField("version"));
        grid.addRow(row++, new Label(Messages.t("meta.field.minGameVersion")), metaTextField("minGameVersion"));

        CheckBox legacy = new CheckBox(Messages.t("meta.field.legacyCompatible"));
        legacy.setSelected(controller.project().meta.legacyCompatible());
        legacy.selectedProperty().addListener((observable, was, now) ->
            controller.setLegacyCompatible(Boolean.TRUE.equals(now)));
        CheckBox hidden = new CheckBox(Messages.t("meta.field.hidden"));
        hidden.setSelected(controller.project().meta.hidden);
        hidden.selectedProperty().addListener((observable, was, now) ->
            controller.setMetaFlag("hidden", Boolean.TRUE.equals(now)));
        grid.addRow(row++, legacy, hidden);

        Label note = new Label(Messages.t("meta.reserialize.note"));
        note.setWrapText(true);
        note.getStyleClass().add("muted");

        VBox content = new VBox(12, grid, note);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private TextField metaTextField(String field) {
        TextField textField = new TextField(controller.metaString(field));
        textField.textProperty().addListener((observable, was, now) ->
            controller.setMetaString(field, now));
        return textField;
    }

    private void validate() {
        if (controller.project() == null) {
            return;
        }
        // A background pass keeps a large mod's rule set off the FX thread; the summary is logged
        // when the pass publishes, which is also when the issues pane repopulates.
        logNextValidationShow = true;
        appendLog(Messages.t("status.validating"));
        controller.refreshValidationAsync();
        selectTab("bottom.validation");
    }

    /** Set by the F5 action: the next published pass is echoed to the log with its first findings. */
    private boolean logNextValidationShow;

    private void reportRequestedValidation() {
        if (!logNextValidationShow || !Platform.isFxApplicationThread()) {
            return;
        }
        logNextValidationShow = false;
        var issues = controller.latestIssues();
        appendLog("validation: " + issues.size() + " findings");
        issues.stream().limit(10)
            .forEach(issue -> appendLog("  " + Messages.t(issue.key(), issue.args().toArray())));
    }

    private void buildFolder() {
        if (controller.project() == null || !confirmBuildDespiteErrors()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(Messages.t("dialog.buildFolder"));
        File destination = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (destination == null) {
            return;
        }
        Path modDir = destination.toPath().resolve(controller.project().internalName());
        if (!approveOverwrite(modDir)) {
            return;
        }
        runIo(() -> {
            var plan = planBuildOffThread();
            ExportReport report = controller.writeBuildFolder(plan, destination.toPath());
            Platform.runLater(() -> {
                appendLog("built " + report.files().size() + " files (" + report.bytes() + " bytes) into "
                    + report.target());
                if (!report.pruned().isEmpty()) {
                    appendLog("removed " + report.pruned().size()
                        + " stale file(s) from earlier builds");
                }
                showBuildDone(report);
            });
        }, false);
        selectTab("bottom.log");
    }

    private void buildZip() {
        if (controller.project() == null || !confirmBuildDespiteErrors()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.t("dialog.buildZip"));
        String version = controller.project().meta.version == null
            ? "" : controller.project().meta.version.trim();
        chooser.setInitialFileName(controller.project().internalName()
            + (version.isEmpty() ? "" : "-" + version) + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("zip", "*.zip"));
        File file = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) {
            return;
        }
        runIo(() -> {
            var plan = planBuildOffThread();
            ExportReport report = controller.writeBuildZip(plan, file.toPath());
            appendLog("packaged " + report.files().size() + " files into " + report.target());
        }, false);
        selectTab("bottom.log");
    }

    private void launchGame() {
        if (controller.project() == null || !confirmBuildDespiteErrors()) {
            return;
        }
        if (gameSession != null && gameSession.process().isAlive()) {
            appendLog("a test session is already running - stop it first");
            return;
        }
        if (launching) {
            appendLog("a test session is already starting");
            return;
        }
        // The scratch data directory of the previous run goes away before a new one is made, so a
        // long editing session does not leave a temp tree behind per test run.
        discardFinishedSession();
        Path java = GameLauncher.detectJava();
        Path jar = GameLauncher.detectGameJar();
        // Detection covers the common layouts; anything else lets the user point at the files.
        if (jar == null) {
            jar = pickFile(Messages.t("dialog.pickJar"), "*.jar");
        }
        if (java == null) {
            java = pickFile(Messages.t("dialog.pickJava"), "java.exe", "*.exe");
        }
        if (java == null || jar == null) {
            appendLog("cannot launch: java=" + java + " jar=" + jar
                + " (set JAVA_HOME or place Mindustry.jar next to the project)");
            new Alert(Alert.AlertType.WARNING, Messages.t("dialog.launchUnavailable")).showAndWait();
            return;
        }
        Path javaExe = java;
        Path gameJar = jar;
        launching = true;
        runIo(() -> {
            try {
                // Plan (save plan) on the FX thread; validation and the build run on this worker.
                BuildPipeline.Plan plan = planBuildOffThread();
                GameLauncher.Session session = GameLauncher.buildAndLaunch(controller, plan, gameJar, javaExe,
                    this::appendLog);
                Platform.runLater(() -> {
                    gameSession = session;
                    launching = false;
                    appendLog("test data directory: " + session.dataDir());
                    refreshGameButtons();
                });
            } catch (Exception failure) {
                errors.reportQuietly("launch failed", failure);
                Platform.runLater(() -> launching = false);
            }
        }, false);
        selectTab("bottom.log");
    }

    /** Stops a running test-play session. */
    private void stopGame() {
        if (gameSession != null && gameSession.process().isAlive()) {
            appendLog("stopping the game...");
            gameSession.process().destroy();
        } else {
            appendLog("no game is running");
        }
        stopAction.enabled.set(false);
    }

    /** Removes the scratch data directory of a session whose game has already exited. */
    private void discardFinishedSession() {
        if (gameSession == null || gameSession.process().isAlive()) {
            return;
        }
        gameSession.close();
        gameSession = null;
        refreshGameButtons();
    }

    /** Opens the scratch data directory of the current/last test-play session. */
    private void openGameLog() {
        if (gameSession == null) {
            appendLog("no test run yet");
            return;
        }
        openInFileManager(gameSession.dataDir());
    }

    /** File chooser used when automatic detection fails; null when the user cancels. */
    private Path pickFile(String title, String... extensions) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(String.join(", ", extensions), extensions));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        return file == null ? null : file.toPath();
    }

    /**
     * A build/launch with validation errors almost certainly fails inside the game, where the
     * message is far harder to read; make the user confirm instead of shipping silently.
     */
    private boolean confirmBuildDespiteErrors() {
        long errors = controller.validateQuietly().stream().filter(issue -> issue.isError()).count();
        if (errors == 0) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
            Messages.t("dialog.buildErrors.body", String.valueOf(errors)),
            ButtonType.YES, ButtonType.NO);
        alert.setHeaderText(Messages.t("dialog.buildErrors.header"));
        return alert.showAndWait().filter(answer -> answer == ButtonType.YES).isPresent();
    }

    private void addBoard() {
        if (controller.project() == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog("Board " + (controller.project().boards.size() + 1));
        dialog.setHeaderText(Messages.t("dialog.boardName"));
        dialog.showAndWait().filter(name -> !name.isBlank())
            .ifPresent(name -> controller.addBoard(name.trim()));
    }

    private void about() {
        new Alert(Alert.AlertType.INFORMATION,
            "Mindustry ModMaker\n" + Messages.t("about.body")).showAndWait();
    }

    /** Help: the view keys both canvases (content board and tech tree) share. */
    private void showCanvasShortcuts() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, String.join("\n",
            Messages.t("canvas.shortcuts.pan"),
            Messages.t("canvas.shortcuts.zoom"),
            Messages.t("canvas.shortcuts.fit"),
            Messages.t("canvas.shortcuts.reset"),
            Messages.t("canvas.shortcuts.unlock")));
        alert.setTitle(Messages.t("canvas.shortcuts.title"));
        alert.setHeaderText(Messages.t("canvas.shortcuts.header"));
        alert.showAndWait();
    }

    /**
     * Confirms overwriting a non-empty folder (import destination, new project, build target).
     * Empty or missing folders pass silently; anything else asks.
     */
    private boolean approveOverwrite(Path folder) {
        if (!java.nio.file.Files.isDirectory(folder)) {
            return true;
        }
        try (var entries = java.nio.file.Files.list(folder)) {
            if (entries.findAny().isEmpty()) {
                return true;
            }
        } catch (java.io.IOException failure) {
            // The folder exists but could not be inspected (locked, or access denied). It may well
            // hold files, so fall through and ask instead of treating "unreadable" as "empty" and
            // overwriting whatever is inside without a word.
        }
        if (Platform.isFxApplicationThread()) {
            return confirmOverwrite(folder);
        }
        // The import may run on the io thread; block it while the FX thread shows the dialog.
        CountDownLatch answered = new CountDownLatch(1);
        AtomicBoolean approved = new AtomicBoolean();
        Platform.runLater(() -> {
            approved.set(confirmOverwrite(folder));
            answered.countDown();
        });
        try {
            answered.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return approved.get();
    }

    private boolean confirmOverwrite(Path folder) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
            Messages.t("dialog.overwriteExisting.body", folder.toString()),
            ButtonType.YES, ButtonType.NO);
        alert.setHeaderText(Messages.t("dialog.overwriteExisting.header"));
        return alert.showAndWait().filter(answer -> answer == ButtonType.YES).isPresent();
    }

    /** Menu exit: guards unsaved work before quitting. */
    private void exitApp() {
        if (confirmExit()) {
            Platform.exit();
        }
    }

    /** True when the app may close; asks to save first when there are unsaved changes. */
    public boolean confirmExit() {
        if (!controller.isDirty()) {
            // The application is going away: the throwaway game data directory goes with it.
            discardAutosaveQuietly();
            discardFinishedSession();
            return true;
        }
        javafx.scene.control.ButtonType saveAndExit =
            new javafx.scene.control.ButtonType(Messages.t("button.saveAndExit"),
                javafx.scene.control.ButtonBar.ButtonData.APPLY);
        javafx.scene.control.ButtonType exitWithoutSave =
            new javafx.scene.control.ButtonType(Messages.t("button.exitWithoutSave"),
                javafx.scene.control.ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
            Messages.t("dialog.exitDirty.body"),
            saveAndExit, exitWithoutSave, javafx.scene.control.ButtonType.CANCEL);
        alert.setHeaderText(Messages.t("dialog.exitDirty.header"));
        Optional<javafx.scene.control.ButtonType> answer = alert.showAndWait();
        if (answer.isEmpty() || answer.get() == javafx.scene.control.ButtonType.CANCEL) {
            return false;
        }
        if (answer.get() == saveAndExit) {
            try {
                controller.save();
            } catch (Exception failure) {
                errors.report("could not save the project", failure);
                return false;
            }
        }
        discardAutosaveQuietly();
        discardFinishedSession();
        return true;
    }

    /** Tells the user where the build went and offers a shortcut straight to the folder. */
    private void showBuildDone(ExportReport report) {
        javafx.scene.control.ButtonType openFolder =
            new javafx.scene.control.ButtonType(Messages.t("button.openFolder"),
                javafx.scene.control.ButtonBar.ButtonData.APPLY);
        Alert alert = new Alert(Alert.AlertType.INFORMATION,
            Messages.t("dialog.buildDone.body", report.target(),
                String.valueOf(report.files().size())),
            openFolder, javafx.scene.control.ButtonType.OK);
        alert.setHeaderText(Messages.t("dialog.buildDone.header"));
        if (report.hasErrors()) {
            alert.setHeaderText(Messages.t("dialog.buildDone.headerWithIssues",
                report.errorCount()));
        }
        alert.showAndWait().filter(answer -> answer == openFolder)
            .ifPresent(answer -> openInFileManager(Path.of(report.target())));
    }

    private void openInFileManager(Path directory) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                && java.awt.Desktop.getDesktop()
                    .isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(directory.toFile());
                return;
            }
        } catch (Exception ignored) {
            // fall through to the platform commands
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String command = os.contains("win") ? "explorer"
                : os.contains("mac") ? "open" : "xdg-open";
            new ProcessBuilder(command, directory.toString()).start();
        } catch (Exception failure) {
            errors.reportQuietly("could not open the folder " + directory, failure);
        }
    }

    // --- state ----------------------------------------------------------------------------------

    /** The class choices currently shown in the inspector (test hook). */
    List<String> inspectorChoices() {
        return inspector.currentClassChoices();
    }

    /** The sprite pane (test hook). */
    SpritePane spritePane() {
        return sprites;
    }

    /** The cosmos pane (test hook). */
    CosmosPane cosmosPane() {
        return cosmosPane;
    }

    /** The tech tree pane (test hook). */
    TechTreePane techTreePane() {
        return techTreePane;
    }

    /** The content canvas (test hook). */
    NodeCanvasPane canvas() {
        return canvas;
    }

    RecordInspector inspector() {
        return inspector;
    }

    /** The main tab strip (test hook; it now sits inside the centre BorderPane). */
    TabPane centerTabs() {
        return centerTabs;
    }

    /**
     * The bottom strip's tab pane (test hook). It exists whether or not the strip is attached, so
     * tests can check its selection without depending on {@link #BOTTOM_STRIP_VISIBLE}.
     */
    TabPane bottomTabs() {
        return bottomTabs;
    }

    /** Selects a tab by its message key (also used by tests to reach a pane). */
    public void selectTab(String key) {
        // 属性/校验/文本 moved to the right-hand ContextPanel; only sprites and the log stay in
        // the bottom strip, which is hidden by default anyway.
        switch (key) {
            case "bottom.strings" -> {
                if (contextPanel != null) {
                    contextPanel.showStrings();
                }
                return;
            }
            case "bottom.validation" -> {
                if (contextPanel != null) {
                    // Opening the findings is a deliberate action: compute once if nothing is
                    // cached, then show it. Keystrokes never take this path.
                    if (controller.project() != null) {
                        controller.validateQuietly();
                    }
                    contextPanel.showValidation();
                    issues.refresh();
                }
                return;
            }
            case "bottom.spritesTab" -> sprites.setLive(true);
            case "bottom.log" -> {
                setLogDrawerVisible(true);
                return;
            }
            default -> { }
        }
        if (bottomTabs == null) {
            return;
        }
        bottomTabs.getTabs().stream()
            .filter(tab -> Messages.t(key).equals(tab.getText()))
            .findFirst().ifPresent(tab -> bottomTabs.getSelectionModel().select(tab));
    }

    /** Called after a project is loaded from the command line. */
    public void justLoaded() {
        canvas.fitToContent();
        refreshProjectState();
    }

    private void refreshProjectState() {
        if (!boardBox.getItems().isEmpty()) {
            boardBox.getItems().clear();
        }
        refreshFocusBox();
        refreshPaletteCounts();
        if (controller.project() == null) {
            status.setText(Messages.t("status.noProject"));
            boardBox.setDisable(true);
            return;
        }
        boardBox.setDisable(false);
        boardBox.getItems().addAll(controller.project().boards);
        boardBox.setValue(controller.board());
        boolean canUndo = controller.canUndo();
        boolean canRedo = controller.canRedo();
        undoAction.enabled.set(canUndo);
        redoAction.enabled.set(canRedo);
        String state = controller.isDirty() ? Messages.t("status.dirty") + "  ·  " : "";
        // A board that no longer mirrors the records is surfaced here instead of being accepted
        // silently; the View menu's "Rebuild Links" clears it.
        LinkGraph.Diff drift = controller.canvasDrift();
        if (!drift.clean()) {
            state += Messages.t("status.canvasStale", drift.orphanNodes(), drift.missingEdges(),
                drift.staleEdges()) + "  ·  ";
        }
        status.setText(state
            + controller.project().displayName()
            + "  ·  " + controller.project().contents.size() + " " + Messages.t("status.content")
            + "  ·  " + controller.project().assets.size() + " " + Messages.t("status.assets")
            + "  ·  " + controller.project().root);
    }

    private void appendLog(String message) {
        if (Platform.isFxApplicationThread()) {
            logArea.appendText(message + "\n");
        } else {
            Platform.runLater(() -> logArea.appendText(message + "\n"));
        }
    }

    private void withProject(Runnable action) {
        if (controller.project() == null) {
            appendLog("open a project first");
            return;
        }
        action.run();
    }

    private interface IoAction {
        void run() throws Exception;
    }

    /** Serialises file work (import, save, build) onto one background thread so loading a large
     * mod no longer freezes the window; everything UI-touching happens back on the FX thread. */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modmaker-io");
        thread.setDaemon(true);
        return thread;
    });

    /** Crash recovery: every {@link #AUTOSAVE_INTERVAL} the dirty project is snapshotted to
     *  {@code modmaker/autosave/}, so a killed process can be recovered on the next open. */
    private static final Duration AUTOSAVE_INTERVAL = Duration.ofMinutes(2);
    private final ScheduledExecutorService autosaveTimer =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "modmaker-autosave");
            thread.setDaemon(true);
            return thread;
        });

    /** Every user-visible failure goes through this one object (log line, and one dialog). */
    private final ErrorReporter errors = new ErrorReporter(this::appendLog);

    /**
     * Runs a model-touching step on the FX thread from a background task and hands back its result.
     * The model belongs to the FX thread, so a worker may only reach it through such a hop - that is
     * what keeps a save from reading the field trees while the user is editing them.
     */
    private static <T> T onFxThread(java.util.concurrent.Callable<T> work) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return work.call();
        }
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(work);
        Platform.runLater(task);
        return task.get();
    }

    /**
     * Validates a snapshot of the model on the calling worker, then decides the save on the FX
     * thread. This is what keeps a build's validation off the FX thread while the save plan - which
     * reads and updates the live field trees - still runs on the thread that owns the model.
     */
    private BuildPipeline.Plan planBuildOffThread() throws Exception {
        var snapshot = onFxThread(controller::snapshotProject);
        List<Issue> issues = controller.validateSnapshot(snapshot);
        return onFxThread(() -> controller.planBuild(issues));
    }

    /**
     * Runs a plan's write phase and, when it does not finish, restores the model's unsaved flags on
     * the FX thread first - a half-written save must never look persisted.
     */
    private void writePlan(ProjectIo.SavePlan plan) throws Exception {
        try {
            ProjectIo.write(plan);
        } catch (Exception failure) {
            onFxThread(() -> {
                controller.rollbackSave(plan);
                return null;
            });
            throw failure;
        }
    }

    private void runIo(IoAction action, boolean fitAfter) {
        ioExecutor.execute(() -> {
            try {
                action.run();
            } catch (Exception failure) {
                errors.report("the operation failed", failure);
            }
            Platform.runLater(() -> {
                refreshProjectState();
                if (fitAfter) {
                    canvas.fitToContent();
                }
            });
        });
    }

    // --- small helpers --------------------------------------------------------------------------

    private Menu menu(String key, MenuItem... items) {
        Menu menu = new Menu(Messages.t(key));
        menu.getItems().addAll(items);
        return menu;
    }

    private MenuItem action(String key, Runnable runnable) {
        MenuItem item = new MenuItem(Messages.t(key));
        item.setOnAction(event -> {
            try {
                runnable.run();
            } catch (RuntimeException failure) {
                errors.reportQuietly("command failed", failure);
            }
        });
        return item;
    }

    private Button toolButton(String key, Runnable runnable) {
        Button button = new Button(Messages.t(key));
        button.getStyleClass().add("tool-button");
        button.setOnAction(event -> {
            try {
                runnable.run();
            } catch (RuntimeException failure) {
                errors.reportQuietly("command failed", failure);
            }
        });
        return button;
    }

    private Tab tab(String key, Node content) {
        Tab tab = new Tab(Messages.t(key), content);
        tab.setClosable(false);
        return tab;
    }

    private static String safeFolderName(String name) {
        String cleaned = name.trim().toLowerCase(Locale.ROOT).replace(' ', '-')
            .replaceAll("[\\\\/:*?\"<>|]", "");
        return cleaned.isEmpty() ? "my-mod" : cleaned;
    }

    /**
     * Releases what the window owns: the background file worker. Called from
     * {@code Application.stop()} while the toolkit shuts down, so a queued save or build cannot go on
     * writing after the app is gone.
     */
    public void shutdown() {
        autosaveTimer.shutdownNow();
        ioExecutor.shutdownNow();
        GameLauncher.shutdownWatchers();
    }
}

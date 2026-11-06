package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.Workspace;
import dev.modmaker.core.io.TechTreeIndex;
import dev.modmaker.core.layout.TechTreeCanvasLayout;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * UI smoke tests on the real JavaFX toolkit: build the shell, count content cards and input ports in
 * the scene graph, snapshot the window, and drive the canvas interaction core (card drag + port
 * connection + undo/redo) through the same entry points the mouse uses.
 *
 * <p>The toolkit is started once per test JVM and shut down via a hook, so tests can run in sequence.
 */
class UiSmokeTest {

    private static boolean fxStarted;

    /** True when this JVM's tests started the JavaFX toolkit (read by the session listener). */
    static boolean toolkitWasStarted() {
        return fxStarted;
    }

    private static synchronized void ensureFxStarted() {
        if (fxStarted) {
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            ready.countDown();
        }
        fxStarted = true;
        try {
            assumeTrue(ready.await(60, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (InterruptedException failed) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failed);
        }
    }

    private interface FxTask {
        void run() throws Exception;
    }

    /**
     * The resource page is a square card grid, not a table: it lists one content type at a time
     * (the left type rail switches it), cards are 128x128 with a 64px type block, clicking a card
     * drives the shared selection so the properties panel edits it, and the page dots only appear
     * when the list overflows one page. Layout follows UI/资源管理-export.html.
     */
    @Test
    void assetGridListsOneTypeWithCardsAndPageDots() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-assets");
            controller.createProject("assets-test", root);
            ContentRecord item = controller.addContent(ContentType.item, 60d, 60d);
            item.fields.put("hardness", new JsonPrimitive(2));
            controller.addContent(ContentType.liquid, 400d, 60d);

            ItemsPane pane = new ItemsPane(controller, ContentType.item);
            Scene scene = new Scene(pane, 1200, 700);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            pane.applyCss();
            pane.layout();

            // One type at a time: the item type lists the item, the liquid type the liquid.
            assertEquals(1, pane.tileCount(), "only the current type's records are listed");
            assertEquals(ContentType.item, pane.listedType());
            assertFalse(countTableViews(pane) > 0, "the table must be gone");
            assertTrue(pane.lookup(".asset-grid") != null, "the card grid fills the page now");

            Node tile = pane.lookupAll(".tile").iterator().next();
            assertEquals(128, tile.getBoundsInLocal().getWidth(), 2, "cards are 128px wide");
            assertEquals(128, tile.getBoundsInLocal().getHeight(), 2, "cards are 128px tall");
            Node icon = tile.lookup(".tile-icon");
            assertTrue(icon != null, "a card carries its type block");
            assertEquals(64, icon.getBoundsInLocal().getWidth(), 1, "the type block is 64px wide");

            // Clicking a card selects the record, which is what feeds the properties panel.
            tile.getOnMouseClicked().handle(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null));
            assertEquals(item, controller.selected(), "clicking a card selects that record");

            // Few records: no page dots. Many records: one dot per page.
            assertEquals(0, pane.pageCount(), "a single page needs no dots");
            for (int i = 0; i < 40; i++) {
                controller.addContent(ContentType.item, 0d, 0d);
            }
            pane.showType(ContentType.liquid);
            pane.showType(ContentType.item);
            assertTrue(pane.pageCount() > 1, "40 items must spill over more than one page");

            // Wider viewport, more columns: the grid is responsive.
            ItemsPane widePane = new ItemsPane(controller, ContentType.item);
            new Scene(widePane, 1400, 700);
            widePane.applyCss();
            widePane.layout();
            int wide = widePane.columnCount();

            ItemsPane narrowPane = new ItemsPane(controller, ContentType.item);
            new Scene(narrowPane, 620, 700);
            narrowPane.applyCss();
            narrowPane.layout();
            assertTrue(narrowPane.columnCount() < wide,
                "narrowing the viewport must reduce the column count (was " + wide + ")");

            Snapshotter.write(scene, Path.of("snapshots", "ui-assets.png"));
        });
    }

    /**
     * A tab pane's own tab headers, in order. Taken from the header area's children rather than
     * {@code lookupAll(".tab")} so the tab panes nested inside a workspace (the planet previews)
     * are not mixed in.
     */
    private static List<Node> headerTabs(javafx.scene.control.TabPane pane) {
        for (Node child : pane.getChildrenUnmodifiable()) {
            if (child.getStyleClass().contains("tab-header-area")) {
                for (Node inner : ((Parent) child).getChildrenUnmodifiable()) {
                    if (inner.getStyleClass().contains("headers-region")) {
                        return List.copyOf(((Parent) inner).getChildrenUnmodifiable());
                    }
                }
            }
        }
        return List.of();
    }

    /** The chip whose label carries the translation of {@code key} (the lookup set is unordered). */
    private static Node chipByLabel(java.util.Set<Node> chips, String key) {
        String text = Messages.t(key);
        return chips.stream().filter(chip -> {
            Node label = chip.lookup(".chip-label");
            return label instanceof javafx.scene.control.Label chipText
                && text.equals(chipText.getText());
        }).findFirst().orElseThrow();
    }

    /** Counts TableView nodes under a root; the resource page must no longer contain one. */
    private static int countTableViews(Node node) {
        int count = node instanceof TableView ? 1 : 0;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += countTableViews(child);
            }
        }
        return count;
    }

    /**
     * The columns carrying bright pixels inside a rectangle of a snapshot, as {left, right}, or
     * {-1, -1} when nothing is drawn there. This measures what the user sees; node geometry cannot,
     * because a label stretched across its whole button says nothing about where its glyphs sit.
     */
    private static int[] textColumns(java.awt.image.BufferedImage image, int fromX, int toX,
        int fromY, int toY) {
        int left = -1;
        int right = -1;
        for (int x = fromX; x < toX; x++) {
            for (int y = fromY; y <= toY; y++) {
                int rgb = image.getRGB(x, y);
                if ((rgb >> 16 & 0xFF) > 150 && (rgb >> 8 & 0xFF) > 150 && (rgb & 0xFF) > 150) {
                    if (left < 0) {
                        left = x;
                    }
                    right = x;
                    break;
                }
            }
        }
        return new int[] {left, right};
    }

    /**
     * The layout of UI/*-export.html, asserted on real laid-out nodes: the 40px title strip carrying
     * the menu, six 120x64 tabs (the last one disabled), a 64px sub-header, 48px palette rows inside
     * the block builder, and the 480px properties panel. Sizes are only meaningful after
     * applyCss()+layout().
     */
    @Test
    void svgLayoutSizesStripTabsAndPalette() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-theme");
            controller.createProject("theme", root);
            ContentRecord item = controller.addContent(ContentType.item, 60d, 60d);

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1536, 864);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            controller.select(item);
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            // The header is the 40px title strip (menu + window buttons) plus the new 64px global
            // command bar with undo/redo, search and the build actions.
            Node top = shell.getTop();
            assertEquals(104, top.getBoundsInLocal().getHeight(), 2,
                "the header is the 40px title strip + the 64px command bar");
            assertEquals(3, top.lookupAll(".window-button").size(),
                "the frameless window draws its own three buttons");
            List<Node> menuButtons = new ArrayList<>(top.lookupAll(".menu-button"));
            assertEquals(7, menuButtons.size(), "all seven menus moved into the title strip");
            Node firstMenu = menuButtons.get(0);
            assertEquals(60, firstMenu.getBoundsInLocal().getWidth(), 2, "menu buttons are 60px wide");
            assertEquals(40, firstMenu.getBoundsInLocal().getHeight(), 2,
                "menu buttons fill the title strip");
            Node menuLabel = firstMenu.lookup(".label");
            assertTrue(menuLabel instanceof javafx.scene.control.Label label
                    && Math.abs(label.getFont().getSize() - 20) < 0.01,
                "menu labels are 20px");
            // Left aligned: the first menu starts at the strip's left edge.
            javafx.geometry.Bounds menuScene = firstMenu.localToScene(firstMenu.getBoundsInLocal());
            assertEquals(0, menuScene.getMinX(), 1, "the menu is left aligned in the title strip");
            // The label node fills the whole button, so only the rendered glyphs prove where the
            // text sits: they have to land in the middle of the 60px cell, not against its left edge.
            java.awt.image.BufferedImage strip =
                Snapshotter.write(scene, Path.of("snapshots", "ui-title-strip.png"));
            int[] span = textColumns(strip, 0, 60, 4, 36);
            assertTrue(span[0] >= 0, "the first menu draws its text");
            assertEquals(30, (span[0] + span[1]) / 2, 2,
                "the menu text is centred in its 60px button, glyphs at " + span[0] + ".." + span[1]);

            javafx.scene.control.TabPane tabs = shell.centerTabs();
            tabs.applyCss();
            tabs.layout();
            assertEquals(6, tabs.getTabs().size(), "six workspaces, as in the reference");
            assertTrue(tabs.getTabs().get(5).isDisable(), "the scripting tab is a disabled placeholder");
            Node tab = headerTabs(tabs).get(0);
            assertEquals(120, tab.getBoundsInLocal().getWidth(), 2, "tabs are 120px wide");
            assertEquals(64, tab.getBoundsInLocal().getHeight(), 2, "tabs are 64px tall");
            javafx.geometry.Bounds tabScene = tab.localToScene(tab.getBoundsInLocal());
            Node tabLabel = tab.lookup(".tab-label");
            assertTrue(tabLabel instanceof javafx.scene.control.Label tabText
                    && Math.abs(tabText.getFont().getSize() - 24) < 0.01,
                "tab labels are 24px");
            javafx.geometry.Bounds labelScene = tabLabel.localToScene(tabLabel.getBoundsInLocal());
            assertEquals((tabScene.getWidth() - labelScene.getWidth()) / 2,
                labelScene.getMinX() - tabScene.getMinX(), 2, "the tab label is centred in its tab");
            // The tab strip sits below the 104px header, so the 24px glyphs occupy rows 120..152.
            int[] tabSpan = textColumns(strip, 0, 120, 120, 152);
            assertTrue(tabSpan[1] - tabSpan[0] >= 90,
                "the tab label is not squeezed, glyphs at " + tabSpan[0] + ".." + tabSpan[1]);
            assertEquals(60, (tabSpan[0] + tabSpan[1]) / 2, 2,
                "the tab text is centred in its 120px tab");

            // The block builder holds the 320px type palette beside the canvas.
            shell.showWorkspace(Workspace.canvas);
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();
            Node paletteRow = shell.lookupAll(".palette-row").iterator().next();
            assertEquals(48, paletteRow.getBoundsInLocal().getHeight(), 2, "palette rows are 48px tall");
            assertTrue(paletteRow.lookup(".palette-bar") != null, "a palette row carries its colour bar");
            Node meta = paletteRow.lookup(".palette-meta");
            assertTrue(meta instanceof javafx.scene.control.Label label
                    && label.getText().startsWith("items · "),
                "the row shows the english folder name and the record count");
            Node subHeader = shell.lookup(".sub-header");
            assertTrue(subHeader != null, "the block builder keeps its own 64px sub-header row");
            assertEquals(64, subHeader.getBoundsInLocal().getHeight(), 2, "the sub-header is 64px");

            // The resources page switches type through its left rail (128x128 cards behind it).
            shell.showWorkspace(Workspace.assets);
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();
            var selectedTab = shell.centerTabs().getSelectionModel().getSelectedItem();
            var rail = selectedTab.getContent().lookup(".type-rail");
            assertTrue(rail != null, "the assets tab owns the 120px type rail");
            assertEquals(120, rail.getBoundsInLocal().getWidth(), 2, "the type rail is 120px wide");
            var railButtons = selectedTab.getContent().lookupAll(".rail-button");
            assertEquals(Workspace.assetTypes().size() + 2, railButtons.size(),
                "one rail row per type, plus add and remove at the bottom");
            assertEquals(1, railButtons.stream()
                .filter(button -> button.getStyleClass().contains("active")).count(),
                "exactly one type row is active");
            for (Node button : railButtons) {
                assertEquals(64, button.getBoundsInLocal().getHeight(), 2, "rail rows are 64px tall");
            }
            Node itemRow = railButtons.stream()
                .filter(button -> button.getStyleClass().contains("active"))
                .findFirst().orElseThrow();
            assertTrue(itemRow instanceof javafx.scene.control.Button itemButton
                    && Math.abs(itemButton.getFont().getSize() - 24) < 0.01,
                "type labels are 24px");

            assertEquals(480, shell.lookup(".context-panel").getBoundsInLocal().getWidth(), 2,
                "the inspector is 480px wide");
            assertTrue(shell.lookup(".context-tabs") != null, "the right panel is the tabbed context panel");
            assertTrue(shell.lookup(".block") != null, "the properties tab groups content into blocks");
        });
    }

    private static void onFx(FxTask task) throws Exception {
        ensureFxStarted();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(120, TimeUnit.SECONDS)) {
            throw new AssertionError("FX task timed out");
        }
        Throwable thrown = failure.get();
        if (thrown != null) {
            if (thrown instanceof Exception exception) {
                throw exception;
            }
            throw new AssertionError(thrown);
        }
    }

    @Test
    void shellRendersProjectCards() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-smoke");
            ModProject project = controller.createProject("ui-smoke", root);
            controller.addContent(ContentType.item, 60d, 60d);
            controller.addContent(ContentType.block, 460d, 60d);
            assertEquals(2, project.contents.size());

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            // Make the block a crafter so the snapshot shows class-adaptive fields in the inspector.
            controller.setField(project.contents.get(1), "type", new com.google.gson.JsonPrimitive("GenericCrafter"));
            controller.select(project.contents.get(1));
            // The canvas lives in the block builder tab; only the selected tab's content is attached.
            shell.showWorkspace(Workspace.canvas);
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            int cards = countCards(shell);
            int inputPorts = countInputPorts(shell);

            Path png = Path.of("snapshots", "ui-smoke.png");
            Snapshotter.write(scene, png);
            long pngBytes = Files.size(png);

            assertEquals(2, cards, "both content cards must exist in the scene graph");
            assertTrue(inputPorts > 0, "the block card must show reference input ports");
            assertTrue(pngBytes > 5_000, "the snapshot should show something, saw " + pngBytes + " bytes");
            System.out.println("ui smoke: cards=" + cards + " inputPorts=" + inputPorts
                + " snapshot=" + pngBytes + " bytes");
        });
    }

    /**
     * The tech tree workspace: the tab exists, the forest the records describe is rendered as a
     * canvas (one root, parent and child, the child below-right of the root like the game's
     * layout), and an edit is written through the same undoable field command as any other edit.
     */
    @Test
    void techTreeTabShowsTheResearchForestAndEditsANode() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-techtree");
            controller.createProject("techtree", root);
            ContentRecord core = controller.addContent(ContentType.block, 60d, 60d);
            ContentRecord kiln = controller.addContent(ContentType.block, 460d, 60d);
            core.fields.put("research", rootNodeValue());
            kiln.fields.put("research", new JsonPrimitive(core.name));

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());

            javafx.scene.control.TabPane tabs = shell.centerTabs();
            assertEquals(6, tabs.getTabs().size(), "the reference layout has six workspaces");
            assertEquals(Messages.t("workspace.techTree"), tabs.getTabs().get(4).getText());
            tabs.getSelectionModel().select(4);

            TechTreePane pane = shell.techTreePane();
            assertEquals(2, pane.canvasNodeCount(), "parent and child, nothing unassigned");
            assertTrue(pane.nodeStyles(core.id).contains("selected") == false,
                "nothing is highlighted before a selection");
            double[] rootCenter = pane.nodeCenter(core.id);
            double[] childCenter = pane.nodeCenter(kiln.id);
            // Game layout: the root is the only node at depth 0; a lone child sits one 80px level
            // below it and keeps the root's x (single-child chains get the mid-point position).
            assertEquals(rootCenter[0], childCenter[0], 0.5, "single child keeps the root's x");
            assertEquals(TechTreeCanvasLayout.SPACING + TechTreeCanvasLayout.NODE_SIZE,
                childCenter[1] - rootCenter[1], 0.5, "one level of depth is nodeSize + gap");

            // Clicking a node selects its record for the inspector.
            controller.select(kiln);
            assertTrue(pane.nodeStyles(kiln.id).contains("selected"),
                "the selected record's node is highlighted");

            shell.inspector().researchEditor().setRoot(true);
            JsonObject research = kiln.fields.get("research").getAsJsonObject();
            assertTrue(research.get("root").getAsBoolean(), "the node became its own tech tree root");
            assertTrue(kiln.dirty, "an edit here marks the record dirty for saving like any other");
            controller.undo();
            assertTrue(kiln.fields.get("research").isJsonPrimitive(),
                "undo restores the bare parent name");

            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();
            Snapshotter.write(scene, Path.of("snapshots", "ui-techtree.png"));
        });
    }

    private static JsonObject rootNodeValue() {
        JsonObject research = new JsonObject();
        research.addProperty("root", true);
        return research;
    }

    /**
     * Report P1-8: every node wears an unlock badge derived from the tree structure (root:true is
     * unlocked, an intact chain is researchable, a parent that resolves to nothing locks the node)
     * while the vanilla/mod/problem type colouring rides along untouched, and nodes that carry
     * research requirements show cost chips - text chips here, since a fresh temp project holds no
     * sprites to iconise them. LOD drops the chips together with the other detail text.
     */
    @Test
    void techTreeNodesCarryUnlockStatesAndResearchCostChips() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-techtree-unlock");
            controller.createProject("unlock", root);
            ContentRecord core = controller.addContent(ContentType.block, 60d, 60d);
            ContentRecord kiln = controller.addContent(ContentType.block, 460d, 60d);
            ContentRecord orphan = controller.addContent(ContentType.block, 860d, 60d);
            JsonObject coreResearch = rootNodeValue();
            JsonArray costs = new JsonArray();
            costs.add("copper/10");
            coreResearch.add("requirements", costs);
            core.fields.put("research", coreResearch);
            kiln.fields.put("research", new JsonPrimitive(core.name));
            orphan.fields.put("research", new JsonPrimitive("no-such-parent"));

            TechTreePane pane = new TechTreePane(controller);
            Scene scene = new Scene(pane, 900, 600);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            pane.applyCss();
            pane.layout();

            assertTrue(pane.nodeStyles(core.id).contains("unlocked"), "a root:true node is unlocked");
            assertTrue(pane.nodeStyles(kiln.id).contains("researchable"),
                "an intact parent chain is researchable");
            assertTrue(pane.nodeStyles(orphan.id).contains("locked"),
                "a parent that resolves to nothing locks the node");
            assertTrue(pane.nodeStyles(orphan.id).contains("problem"),
                "the unlock state and the type colouring coexist on the same node");
            assertTrue(pane.lookupAll(".tech-lock-badge").size() >= 3,
                "every placed node wears a state badge");

            javafx.scene.layout.HBox chips = pane.costRow(core.id);
            assertTrue(chips != null, "the root carries its requirement as a chip");
            String texts = chips.lookupAll(".tech-cost-text").stream()
                .map(node -> ((javafx.scene.control.Label) node).getText())
                .collect(java.util.stream.Collectors.joining(","));
            assertEquals("copper 10", texts,
                "no sprite in a fresh project: the chip degrades to the text form");
            assertTrue(chips.isVisible(), "cost chips are drawn at full zoom");
            assertNull(pane.costRow(kiln.id), "a node without requirements shows no chips");

            Node world = pane.lookup(".tech-world");
            Node techCanvas = pane.lookup(".tech-canvas");
            for (int i = 0; i < 7; i++) {
                techCanvas.fireEvent(scrollAt(450, 300, -120, false));
            }
            assertTrue(pane.zoomValue() < 0.4, "seven wheel-downs drop under the LOD threshold");
            assertTrue(world.getStyleClass().contains("lod"), "the LOD class rides on the world root");
            pane.applyCss();
            assertFalse(chips.isVisible(), "LOD hides the cost chips with the other detail text");
        });
    }

    /**
     * The canvas toolbar writes through the same {@code research} field as the inspector: a record
     * can be attached under the picked node, promoted to its own root, and removed again - each an
     * ordinary undoable field edit, and each visible in the canvas right away.
     */
    @Test
    void techTreeToolbarAddsAndRemovesResearchNodes() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-techtree-actions");
            controller.createProject("techtree", root);
            ContentRecord core = controller.addContent(ContentType.block, 60d, 60d);
            ContentRecord kiln = controller.addContent(ContentType.block, 460d, 60d);
            ContentRecord gear = controller.addContent(ContentType.item, 60d, 460d);
            core.fields.put("research", rootNodeValue());

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.centerTabs().getSelectionModel().select(4);

            TechTreePane pane = shell.techTreePane();
            assertEquals(1, pane.canvasNodeCount(), "only the root so far");
            // Attach the selected tab's content before looking up its toolbar: TabPane only adds
            // the content node during layout, so an unapplied layout hides the row from lookups.
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            // Attach kiln under the root: pick it, make sure the root is the picked node, fire.
            controller.select(core);
            pane.childPicker().getSelectionModel().select(controller.displayName(kiln));
            toolbarButton(shell, Messages.t("techtree.addChildTitle")).fire();
            assertEquals(new JsonPrimitive(core.fullName("techtree")),
                kiln.fields.get("research"),
                "the child carries the prefixed parent name the game resolves first");
            assertEquals(2, pane.canvasNodeCount(), "the canvas reflects the new node");

            // Promote the item to its own root (no planet in the project, so a bare root).
            pane.rootPicker().getSelectionModel().select(controller.displayName(gear));
            toolbarButton(shell, Messages.t("techtree.addRootTitle")).fire();
            assertTrue(gear.fields.get("research").getAsJsonObject().get("root").getAsBoolean(),
                "the record became a root node");
            assertEquals(3, pane.canvasNodeCount(), "a second tree appears beside the first");

            // Remove kiln's research again; the field is gone, not just empty.
            controller.select(kiln);
            toolbarButton(shell, Messages.t("techtree.removeChildTitle")).fire();
            assertNull(kiln.fields.get("research"), "removing clears the field entirely");
            assertEquals(2, pane.canvasNodeCount());
        });
    }

    /** Finds a tech-tree toolbar button by its translated caption (the row sits above the pane). */
    private static Button toolbarButton(AppShell shell, String caption) {
        for (javafx.scene.Node node : shell.lookupAll(".tool-button")) {
            if (node instanceof Button button && caption.equals(button.getText())) {
                return button;
            }
        }
        throw new AssertionError("no button '" + caption + "' in the tech tree toolbar");
    }

    /**
     * The canvas against the real, large fixture mod: hundreds of nodes render, and the snapshot
     * shows them with sprites and edges. This is the acceptance view for "looks like the game's
     * research screen".
     */
    @Test
    void techTreeCanvasRendersTheRealModForest() throws Exception {
        Path modRoot = dev.modmaker.core.io.TestFixtures.realModRoot();
        assumeTrue(modRoot != null && Files.isDirectory(modRoot), "real mod fixture not present");
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            controller.openProject(modRoot.toAbsolutePath());

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1500, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.centerTabs().getSelectionModel().select(4);

            TechTreePane pane = shell.techTreePane();
            assertTrue(pane.canvasNodeCount() > 100,
                "the real mod's forest must render, saw " + pane.canvasNodeCount());

            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();
            Snapshotter.write(scene, Path.of("snapshots", "ui-techtree-real.png"));
        });
    }

    /**
     * Regression guard for the flat-theme grid: fitting a board that spans far more world than
     * the viewport clamps the zoom to MIN_ZOOM, and the grid buffer must stay viewport-sized
     * instead of exploding into a world-sized multi-hundred-MB canvas that freezes the UI.
     */
    @Test
    void gridBufferStaysViewportSizedAtExtremeZoomOut() throws Exception {
        AtomicReference<NodeCanvasPane> canvas = new AtomicReference<>();
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-grid");
            controller.createProject("grid-test", root);
            controller.addContent(ContentType.item, 60d, 60d);
            controller.addContent(ContentType.block, 60000d, 60d);
            NodeCanvasPane pane = new NodeCanvasPane(controller);
            // A plain resize() does not stick without a layout pass; a real scene layout does.
            javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane(pane);
            Scene scene = new Scene(holder, 1400, 900);
            Snapshotter.write(scene, root.resolve("grid-fit.png"));
            pane.fitToContent(); // clamps to MIN_ZOOM and queues a grid redraw
            canvas.set(pane);
        });
        // Queued after the redraw (runLater is FIFO), so the buffer reflects the new zoom.
        onFx(() -> {
            NodeCanvasPane pane = canvas.get();
            assertEquals(1400, pane.gridBufferWidth(), 0.001,
                "the grid buffer must track the viewport, never the world");
            assertEquals(900, pane.gridBufferHeight(), 0.001);
        });
    }

    /**
     * The shared view keys on the content canvas: F fits the board, Ctrl+0 resets to 100%, and
     * Ctrl+wheel zooms around the cursor - the world point under it must stay under it.
     */
    @Test
    void canvasViewKeysFitResetZoomAndAnchorWheelZoomAtCursor() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-viewkeys");
            controller.createProject("viewkeys", root);
            controller.addContent(ContentType.item, 60d, 60d);
            controller.addContent(ContentType.block, 6000d, 60d);

            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            new Scene(canvas, 1400, 900);
            canvas.resize(1400, 900);

            canvas.fireEvent(keyPressed(javafx.scene.input.KeyCode.F));
            assertTrue(canvas.zoomValue() < 1, "F must fit the wide board below 100%");

            canvas.fireEvent(keyPressedCtrl(javafx.scene.input.KeyCode.DIGIT0));
            assertEquals(1, canvas.zoomValue(), 1e-9, "Ctrl+0 must reset the zoom to 100%");

            double px = 300;
            double py = 200;
            double worldX = (px - canvas.panXValue()) / canvas.zoomValue();
            double worldY = (py - canvas.panYValue()) / canvas.zoomValue();
            canvas.fireEvent(scrollAt(px, py, 120, true));
            assertEquals(1.1, canvas.zoomValue(), 1e-9, "one wheel notch multiplies the zoom");
            assertEquals(worldX, (px - canvas.panXValue()) / canvas.zoomValue(), 1e-6,
                "the cursor must keep holding the same world point");
            assertEquals(worldY, (py - canvas.panYValue()) / canvas.zoomValue(), 1e-6,
                "the cursor must keep holding the same world point");
        });
    }

    /**
     * Space held over the focused canvas turns a left-drag into panning: the card under the
     * cursor does not move and the viewport does. Releasing space restores the plain drag.
     */
    @Test
    void spaceLeftDragPansTheContentCanvas() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-space-pan");
            controller.createProject("spacepan", root);
            ContentRecord item = controller.addContent(ContentType.item, 100d, 100d);
            var itemNode = controller.board().nodes.stream()
                .filter(node -> node.contentId.equals(item.id)).findFirst().orElseThrow();

            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            new Scene(canvas, 1400, 900);
            canvas.resize(1400, 900);

            double panX = canvas.panXValue();
            double panY = canvas.panYValue();
            canvas.fireEvent(keyPressed(javafx.scene.input.KeyCode.SPACE));
            canvas.pressAt(100, 100, javafx.scene.input.MouseButton.PRIMARY);
            canvas.dragTo(150, 130);
            assertEquals(panX + 50, canvas.panXValue(), 1e-6, "space+left drag pans horizontally");
            assertEquals(panY + 30, canvas.panYValue(), 1e-6, "space+left drag pans vertically");
            canvas.releaseAt(150, 130);
            assertEquals(100, itemNode.x, 1e-6, "the card under the cursor must not move");
            canvas.fireEvent(keyReleased(javafx.scene.input.KeyCode.SPACE));

            // With space released the same gesture drags the card again, and the view stays put.
            // The card now sits at screen (100+50, 100+30) after the pan, so press there.
            canvas.pressAt(150, 130, javafx.scene.input.MouseButton.PRIMARY);
            canvas.dragTo(200, 160);
            assertEquals(panX + 50, canvas.panXValue(), 1e-6, "a plain drag must not pan");
            canvas.releaseAt(200, 160);
            assertEquals(150, itemNode.x, 1e-6, "a plain drag moves the card");
        });
    }

    /**
     * The same view keys on the tech tree canvas: the small viewport fits the three-level chain
     * below 100%, Ctrl+0 resets to exactly 100%, F refits, and wheel zoom keeps the world point
     * under the cursor fixed.
     */
    @Test
    void techTreeCanvasViewKeysFitResetZoomAndAnchorWheelZoom() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-tech-viewkeys");
            controller.createProject("techkeys", root);
            ContentRecord core = controller.addContent(ContentType.block, 60d, 60d);
            ContentRecord kiln = controller.addContent(ContentType.block, 460d, 60d);
            ContentRecord gear = controller.addContent(ContentType.block, 460d, 460d);
            core.fields.put("research", rootNodeValue());
            kiln.fields.put("research", new JsonPrimitive(core.name));
            gear.fields.put("research", new JsonPrimitive(kiln.name));

            TechTreePane pane = new TechTreePane(controller);
            new Scene(pane, 240, 160);
            pane.applyCss();
            // The canvas gains its size in this layout pass, which fires the initial fit.
            pane.resize(240, 160);
            pane.layout();

            javafx.scene.Node techCanvas = pane.lookup(".tech-canvas");
            assertTrue(techCanvas != null, "the tech tree canvas is in the scene graph");
            assertTrue(pane.zoomValue() < 1, "a small viewport fits the chain below 100%");

            techCanvas.fireEvent(keyPressedCtrl(javafx.scene.input.KeyCode.DIGIT0));
            assertEquals(1, pane.zoomValue(), 1e-9, "Ctrl+0 must reset the tech tree zoom");

            double px = 100;
            double py = 80;
            double worldX = (px - pane.panXValue()) / pane.zoomValue();
            double worldY = (py - pane.panYValue()) / pane.zoomValue();
            techCanvas.fireEvent(scrollAt(px, py, 120, true));
            assertTrue(pane.zoomValue() > 1, "wheel zooming in from 100% must exceed it");
            assertEquals(worldX, (px - pane.panXValue()) / pane.zoomValue(), 1e-6,
                "the cursor must keep holding the same world point");
            assertEquals(worldY, (py - pane.panYValue()) / pane.zoomValue(), 1e-6,
                "the cursor must keep holding the same world point");

            techCanvas.fireEvent(keyPressed(javafx.scene.input.KeyCode.F));
            assertTrue(pane.zoomValue() < 1, "F must refit the whole tree");
        });
    }

    /**
     * The full interaction core on a real scene graph: drag a card, then drag from one card's ID
     * port to another card's input port and verify the reference was written - without OS-level
     * input, by driving the same {@code pressAt/dragTo/releaseAt} entry points the mouse uses.
     */
    @Test
    void canvasInteractionsDragCardsAndDrawConnections() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-interact");
            controller.createProject("interact", root);
            ContentRecord item = controller.addContent(ContentType.item, 100d, 100d);
            ContentRecord block = controller.addContent(ContentType.block, 500d, 100d);
            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            canvas.resize(1400, 900);
            new Scene(canvas); // attach to a scene so sceneToLocal is well-defined

            String itemId = boardNodeId(controller, item.id);
            String blockId = boardNodeId(controller, block.id);

            // 1. Drag the item card by its body and verify the node moved.
            canvas.pressAt(200, 150, javafx.scene.input.MouseButton.PRIMARY);
            canvas.dragTo(260, 190);
            canvas.releaseAt(260, 190);
            var itemNode = controller.board().nodes.stream()
                .filter(node -> node.contentId.equals(item.id)).findFirst().orElseThrow();
            assertEquals(160, itemNode.x, 0.001, "card drag must move the node");
            assertEquals(140, itemNode.y, 0.001);

            // 2. Drag from the item's ID port to the block's requirements port.
            javafx.geometry.Point2D out = canvas.portWorld(itemId, null);
            javafx.geometry.Point2D in = canvas.portWorld(blockId, "requirements");
            canvas.pressAt(out.getX(), out.getY(), javafx.scene.input.MouseButton.PRIMARY);
            canvas.dragTo(in.getX(), in.getY());
            canvas.releaseAt(in.getX(), in.getY());

            var requirements = block.fields.get("requirements");
            assertTrue(requirements != null && requirements.isJsonArray()
                && requirements.getAsJsonArray().size() == 1,
                "requirements must be written by the connection, saw " + requirements);
            assertEquals(1, controller.board().edges.size(), "one edge after connecting");

            // 3. Undo removes the reference, redo restores it.
            controller.undo();
            assertFalse(block.fields.containsKey("requirements"), "undo removes the reference");
            controller.redo();
            assertTrue(block.fields.containsKey("requirements"), "redo restores the reference");
            System.out.println("interaction smoke: drag + connect + undo/redo all work");
        });
    }

    /**
     * A selected connection is removed by Backspace as well as by Delete: the canvas must accept
     * both keys for the same edge, and removing the edge must also take its reference back out.
     */
    @Test
    void selectedConnectionIsRemovedByDeleteAndBackspace() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-edge-delete");
            controller.createProject("edge-delete", root);
            ContentRecord item = controller.addContent(ContentType.item, 100d, 100d);
            ContentRecord block = controller.addContent(ContentType.block, 500d, 100d);
            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            canvas.resize(1400, 900);
            new Scene(canvas);

            connect(canvas, controller, item, block);
            assertEquals(1, controller.board().edges.size(), "one edge after connecting");

            // Click the connection's pick curve, then press Backspace.
            Node pick = canvas.lookupAll(".edge-pick").iterator().next();
            pick.fireEvent(click());
            canvas.fireEvent(keyPressed(javafx.scene.input.KeyCode.BACK_SPACE));
            assertEquals(0, controller.board().edges.size(), "Backspace must remove the selected edge");
            assertFalse(block.fields.containsKey("requirements"), "the reference goes with the edge");

            // Remove the same edge again, this time with Delete.
            connect(canvas, controller, item, block);
            assertEquals(1, controller.board().edges.size());
            Node pick2 = canvas.lookupAll(".edge-pick").iterator().next();
            pick2.fireEvent(click());
            canvas.fireEvent(keyPressed(javafx.scene.input.KeyCode.DELETE));
            assertEquals(0, controller.board().edges.size(), "Delete must remove the selected edge");
        });
    }

    /**
     * A stack connection carries an amount. The controller reads it back (1 by default), rewrites
     * it in place, and both directions are undoable so the field stays consistent with the canvas.
     */
    @Test
    void connectionAmountIsEditableAndUndoable() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-edge-amount");
            controller.createProject("edge-amount", root);
            ContentRecord item = controller.addContent(ContentType.item, 100d, 100d);
            ContentRecord block = controller.addContent(ContentType.block, 500d, 100d);
            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            canvas.resize(1400, 900);
            new Scene(canvas);

            connect(canvas, controller, item, block);
            CanvasEdge edge = controller.board().edges.get(0);
            assertEquals(1, controller.edgeAmount(edge), "a fresh connection carries one");

            controller.setEdgeAmount(edge, 5);
            assertEquals(5, amountOf(block), "the amount lands in the referenced field");
            assertEquals(5, controller.edgeAmount(edge));

            controller.undo();
            assertEquals(1, amountOf(block), "undo restores the previous amount");
            controller.redo();
            assertEquals(5, amountOf(block), "redo re-applies the amount");
        });
    }

    /**
     * Double-clicking a connection drops a reroute waypoint under the cursor (report P2-9): the
     * waypoint renders as a dot, is undoable, drags to reshape the wire, and Delete on the
     * selection removes just the waypoint while the wire itself survives.
     */
    @Test
    void doubleClickOnConnectionInsertsDraggableRerouteWaypoint() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-reroute");
            controller.createProject("reroute", root);
            ContentRecord item = controller.addContent(ContentType.item, 100d, 100d);
            ContentRecord block = controller.addContent(ContentType.block, 500d, 100d);
            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            canvas.resize(1400, 900);
            new Scene(canvas);

            connect(canvas, controller, item, block);
            CanvasEdge edge = controller.board().edges.get(0);
            assertTrue(edge.reroutes.isEmpty(), "a fresh connection has no waypoints");

            // Double-click the wire's pick path at an empty spot: a waypoint lands under the cursor.
            Node pick = canvas.lookupAll(".edge-pick").iterator().next();
            pick.fireEvent(dblClick(300, 400, false));
            assertEquals(1, edge.reroutes.size(), "double-click inserts one waypoint");
            assertEquals(300, edge.reroutes.get(0)[0], 0.001);
            assertEquals(400, edge.reroutes.get(0)[1], 0.001);
            assertEquals(1, canvas.lookupAll(".reroute").size(), "the waypoint renders as a dot");

            // Undo/redo take the waypoint away and back.
            controller.undo();
            assertTrue(edge.reroutes.isEmpty(), "undo removes the waypoint");
            controller.redo();
            assertEquals(1, edge.reroutes.size(), "redo brings it back");

            // Drag the dot: the live waypoint follows, release records one undoable move.
            canvas.pressAt(300, 400, javafx.scene.input.MouseButton.PRIMARY);
            canvas.dragTo(340, 430);
            canvas.releaseAt(340, 430);
            assertEquals(340, edge.reroutes.get(0)[0], 0.001, "dragging the dot moves the waypoint");
            assertEquals(430, edge.reroutes.get(0)[1], 0.001);
            controller.undo();
            assertEquals(300, edge.reroutes.get(0)[0], 0.001, "undo puts the waypoint back");
            assertEquals(400, edge.reroutes.get(0)[1], 0.001);

            // A press without a drag is not an edit: no extra undo step.
            int depthBefore = controller.undoStackDepth();
            canvas.pressAt(300, 400, javafx.scene.input.MouseButton.PRIMARY);
            canvas.releaseAt(300, 400);
            assertEquals(depthBefore, controller.undoStackDepth(), "a click on the dot pushes nothing");

            // The press left the dot selected: Delete removes just the waypoint, wire intact.
            canvas.fireEvent(keyPressed(javafx.scene.input.KeyCode.DELETE));
            assertTrue(edge.reroutes.isEmpty(), "Delete removes the selected waypoint");
            assertEquals(1, controller.board().edges.size(), "the wire itself survives");
            assertEquals(0, canvas.lookupAll(".reroute").size(), "the dot goes with the waypoint");
        });
    }

    /**
     * The toolbar button cycles the board's wire routing (report P2-9): bezier legs, orthogonal
     * bends and straight runs all rebuild the edge paths, and the choice - like the waypoint list -
     * survives a save and reopen.
     */
    @Test
    void edgeStyleCycleRebuildsWirePathsAndPersists() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-edge-style");
            controller.createProject("edge-style", root);
            ContentRecord item = controller.addContent(ContentType.item, 100d, 100d);
            ContentRecord block = controller.addContent(ContentType.block, 500d, 100d);
            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            canvas.resize(1400, 900);
            new Scene(canvas);

            connect(canvas, controller, item, block);
            CanvasEdge edge = controller.board().edges.get(0);
            controller.insertReroute(edge, 300, 400);
            javafx.scene.shape.Path pickPath =
                (javafx.scene.shape.Path) canvas.lookupAll(".edge-pick").iterator().next();

            assertEquals(EdgePathMath.Style.BEZIER, canvas.edgeStyle());
            assertEquals(2, bezierLegs(pickPath), "one waypoint makes two bezier legs");

            canvas.cycleEdgeStyle();
            assertEquals(EdgePathMath.Style.ORTHOGONAL, canvas.edgeStyle());
            assertEquals(0, bezierLegs(pickPath), "orthogonal wires are polylines");
            assertTrue(pickPath.getElements().size() >= 5,
                "an orthogonal wire through a waypoint bends at least four times");

            canvas.cycleEdgeStyle();
            assertEquals(EdgePathMath.Style.STRAIGHT, canvas.edgeStyle());
            assertEquals(3, pickPath.getElements().size(), "start + waypoint + end");

            // Save with a non-default style and a waypoint; both come back on reopen.
            canvas.cycleEdgeStyle();
            canvas.cycleEdgeStyle();
            assertEquals(EdgePathMath.Style.ORTHOGONAL, canvas.edgeStyle());
            controller.save();
            ModProject reopened = dev.modmaker.core.io.ProjectIo.open(root, registry);
            assertEquals("orthogonal", reopened.boards.get(0).edgeStyle,
                "the style preference lives in the sidecar");
            assertEquals(1, reopened.boards.get(0).edges.get(0).reroutes.size(),
                "the waypoint lives in the canvas file");
            assertEquals(300, reopened.boards.get(0).edges.get(0).reroutes.get(0)[0], 0.001);
            assertEquals(400, reopened.boards.get(0).edges.get(0).reroutes.get(0)[1], 0.001);
        });
    }

    /** The number of cubic legs in an edge's pick path. */
    private static int bezierLegs(javafx.scene.shape.Path path) {
        int legs = 0;
        for (javafx.scene.shape.PathElement element : path.getElements()) {
            if (element instanceof javafx.scene.shape.CubicCurveTo) {
                legs++;
            }
        }
        return legs;
    }

    /** A double-click at scene coordinates, optionally with Ctrl held. */
    private static javafx.scene.input.MouseEvent dblClick(double sceneX, double sceneY,
        boolean controlDown) {
        return new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
            sceneX, sceneY, sceneX, sceneY, javafx.scene.input.MouseButton.PRIMARY, 2,
            false, controlDown, false, false, true, false, false, false, false, false, null);
    }

    /**
     * The new-project flow prepares the target folder before the controller saves into it, so a
     * location the OS refuses (a drive root, a protected folder) becomes a clear message instead of
     * a raw AccessDeniedException leaking out of the save. The probe must accept a fresh folder and
     * refuse a path that is already taken by a file.
     */
    @Test
    void projectFolderProbeRefusesAPathThatIsNotAFolder() throws Exception {
        Path temp = Files.createTempDirectory("mm-folder-probe");
        Path occupied = Files.createFile(temp.resolve("occupied"));
        assertFalse(AppShell.canPrepareProjectFolder(occupied),
            "a path that is an existing file must be refused");
        assertTrue(AppShell.canPrepareProjectFolder(temp.resolve("fresh")),
            "a new folder under a writable directory is fine");
    }

    /**
     * The new-project chooser must not start at the Desktop: on machines where a security product
     * guards it, creating a new folder there is refused. The home directory is not guarded that way.
     */
    @Test
    void newProjectChooserStartsAtTheUserHome() {
        assertEquals(Path.of(System.getProperty("user.home")),
            AppShell.defaultProjectParent().toPath(),
            "the chooser must start at the home directory, never the guarded Desktop");
    }

    /** Drags a connection from the item's ID port to the block's requirements port. */
    private static void connect(NodeCanvasPane canvas, ProjectController controller,
        ContentRecord item, ContentRecord block) {
        String itemId = boardNodeId(controller, item.id);
        String blockId = boardNodeId(controller, block.id);
        javafx.geometry.Point2D out = canvas.portWorld(itemId, null);
        javafx.geometry.Point2D in = canvas.portWorld(blockId, "requirements");
        canvas.pressAt(out.getX(), out.getY(), javafx.scene.input.MouseButton.PRIMARY);
        canvas.dragTo(in.getX(), in.getY());
        canvas.releaseAt(in.getX(), in.getY());
    }

    /** The amount on the first entry of a record's requirements field. */
    private static int amountOf(ContentRecord record) {
        JsonArray array = record.fields.get("requirements").getAsJsonArray();
        return array.get(0).getAsJsonObject().get("amount").getAsInt();
    }

    private static javafx.scene.input.MouseEvent click() {
        return new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
            0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1,
            false, false, false, false, true, false, false, false, false, false, null);
    }

    private static javafx.scene.input.KeyEvent keyPressed(javafx.scene.input.KeyCode code) {
        return new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
            "", "", code, false, false, false, false);
    }

    private static javafx.scene.input.KeyEvent keyPressedCtrl(javafx.scene.input.KeyCode code) {
        return new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
            "", "", code, false, true, false, false);
    }

    private static javafx.scene.input.KeyEvent keyReleased(javafx.scene.input.KeyCode code) {
        return new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_RELEASED,
            "", "", code, false, false, false, false);
    }

    private static javafx.scene.input.ScrollEvent scrollAt(double x, double y, double deltaY,
        boolean controlDown) {
        return new javafx.scene.input.ScrollEvent(javafx.scene.input.ScrollEvent.SCROLL,
            x, y, x, y,
            false, controlDown, false, false, false, false,
            0, deltaY, 0, deltaY,
            javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
            javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0,
            0, null);
    }

    /**
     * A primary-button mouse event at node-local coordinates, for firing through the scene graph.
     * JavaFX recomputes a dispatched mouse event's node-relative x/y from its scene coordinates,
     * so the scene position of the node must travel with the point.
     */
    private static javafx.scene.input.MouseEvent mouseAt(Node node,
        javafx.event.EventType<? extends javafx.scene.input.MouseEvent> type,
        double localX, double localY) {
        return mouseAt(node, type, localX, localY, false, false);
    }

    /** The same click with Shift / Ctrl held, for the sprite grid's multi-select semantics. */
    private static javafx.scene.input.MouseEvent mouseAt(Node node,
        javafx.event.EventType<? extends javafx.scene.input.MouseEvent> type,
        double localX, double localY, boolean shiftDown, boolean controlDown) {
        // MouseEvent's x/y are scene coordinates: dispatch recomputes every handler's
        // getX()/getY() as sceneX - nodeSceneX, so a node-local point has to be converted
        // through localToScene before the event is built (the window is at screen 0,0).
        javafx.geometry.Point2D scene = node.localToScene(localX, localY);
        return new javafx.scene.input.MouseEvent(type, scene.getX(), scene.getY(),
            scene.getX(), scene.getY(),
            javafx.scene.input.MouseButton.PRIMARY, 1,
            shiftDown, controlDown, false, false, true, true, false, false, false, false, null);
    }

    /** Clicks the centre of a node, optionally with Shift / Ctrl, the way the mouse would. */
    private static void clickNode(Node node, boolean shift, boolean control) {
        node.fireEvent(mouseAt(node, javafx.scene.input.MouseEvent.MOUSE_CLICKED,
            node.getBoundsInLocal().getWidth() / 2, node.getBoundsInLocal().getHeight() / 2,
            shift, control));
    }

    /** The first ToggleButton in a container whose label matches, or null. */
    private static javafx.scene.control.ToggleButton toggleByLabel(Parent root, String label) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof javafx.scene.control.ToggleButton button
                && label.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    /**
     * Regression test for the reported bug: a card dragged outside the canvas (negative world
     * coordinates) must be clipped at the canvas bounds, not drawn on top of the toolbar and the
     * side panels. Verified at the pixel level on a rendered snapshot.
     */
    @Test
    void cardsOutsideTheCanvasAreClipped() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-clip");
            controller.createProject("clip", root);
            controller.addContent(ContentType.item, 0d, 0d);

            // Real-app layout: toolbar strip on top, palette strip on the left, canvas in the center.
            javafx.scene.layout.BorderPane rootPane = new javafx.scene.layout.BorderPane();
            javafx.scene.layout.Region topBar = new javafx.scene.layout.Region();
            topBar.setStyle("-fx-background-color: #ff00ff;");
            topBar.setPrefHeight(40);
            javafx.scene.layout.Region leftBar = new javafx.scene.layout.Region();
            leftBar.setStyle("-fx-background-color: #00ff00;");
            leftBar.setPrefWidth(100);
            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            rootPane.setTop(topBar);
            rootPane.setLeft(leftBar);
            rootPane.setCenter(canvas);
            Scene scene = new Scene(rootPane, 800, 600);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());

            // Drag the card so it hangs out over the top bar and the left panel. The press lands
            // inside the item's carrier card, which is only 64px tall.
            canvas.pressAt(130, 30, javafx.scene.input.MouseButton.PRIMARY);
            canvas.dragTo(-100, -20);
            canvas.releaseAt(-100, -20);
            var node = controller.board().nodes.get(0);
            assertEquals(-230, node.x, 0.001, "drag must move the node");
            assertEquals(-50, node.y, 0.001);

            java.awt.image.BufferedImage image =
                Snapshotter.write(scene, root.resolve("clip-check.png"));

            // Points that lie over the bars and would show card pixels without clipping.
            assertMagenta(image, 200, 20, "top bar right of the card");
            assertGreen(image, 50, 300, "left panel below the card");
            // The card is still visible inside the canvas: with the carrier at (-230,-50) the
            // visible sliver is where the card crosses the canvas' top-left corner.
            int inside = image.getRGB(110, 48);
            assertTrue((inside >> 16 & 0xFF) < 100, "the card must still render inside the canvas");
            System.out.println("clip smoke: outside bars clean, card renders clipped inside canvas");
        });
    }

    private static void assertMagenta(java.awt.image.BufferedImage image, int x, int y, String what) {
        int rgb = image.getRGB(x, y);
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        assertTrue(red > 200 && blue > 200 && green < 100,
            what + " must show the bar (magenta), saw rgb(" + red + "," + green + "," + blue + ")");
    }

    private static void assertGreen(java.awt.image.BufferedImage image, int x, int y, String what) {
        int rgb = image.getRGB(x, y);
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        assertTrue(green > 200 && red < 100 && blue < 100,
            what + " must show the panel (green), saw rgb(" + red + "," + green + "," + blue + ")");
    }

    /**
     * UI-level regression for the adaptive class dropdown: with a battery selected, the offered
     * class choices must stay inside the power branch - no item bridges, no ducts.
     */
    @Test
    void batteryRecordHasNoTransportClassesInItsDropdown() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-adaptive");
            ModProject project = controller.createProject("adaptive", root);
            controller.addContent(ContentType.block, 0d, 0d);
            controller.setField(project.contents.get(0), "type",
                new com.google.gson.JsonPrimitive("Battery"));

            AppShell shell = new AppShell(controller);
            new Scene(shell, 1400, 900);
            controller.select(project.contents.get(0));

            List<String> choices = shell.inspectorChoices();
            assertTrue(choices.contains("Battery"), choices.toString());
            assertTrue(choices.contains("PowerNode"), choices.toString());
            assertTrue(choices.contains("Block"), "the base class must stay selectable: " + choices);
            for (String forbidden : new String[] {"Conduit", "Duct", "ItemBridge", "Wall",
                "GenericCrafter", "Drill", "Router"}) {
                assertFalse(choices.contains(forbidden),
                    forbidden + " must not be offered for a battery: " + choices);
            }
            System.out.println("adaptive smoke: battery choices=" + choices.size());
        });
    }

    /**
     * The sprite tab environment: lists the project's sprite assets with a working preview and the
     * content records still missing their sprite.
     */
    @Test
    void spritePaneListsAssetsAndMissing() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-sprites");
            ModProject project = controller.createProject("sprites", root);
            controller.addContent(ContentType.block, 0d, 0d);

            // A real 2x2 png so the preview can actually load it.
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(2, 2,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
            img.setRGB(1, 1, 0xFFFF00FF);
            Path spriteFile = root.resolve("sprites/tiny.png");
            Files.createDirectories(spriteFile.getParent());
            javax.imageio.ImageIO.write(img, "png", spriteFile.toFile());
            project.assets.add(new dev.modmaker.core.model.AssetRef("sprites/tiny.png",
                Files.size(spriteFile), "sprite"));

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.selectTab("bottom.spritesTab");

            var pane = shell.spritePane();
            assertEquals(List.of("sprites/tiny.png"), pane.listedFiles());
            assertTrue(pane.canLoad("sprites/tiny.png"), "the png must be loadable from the project");
            assertEquals(1, pane.missingNames().size(),
                "the block has no sprite: " + pane.missingNames());

            Snapshotter.write(scene, Path.of("snapshots", "ui-sprites.png"));
            System.out.println("sprite smoke: files=" + pane.listedFiles()
                + " missing=" + pane.missingNames());
        });
    }

    /**
     * Report P2-11 (Aseprite paradigm): the sprite tab browses thumbnails in a grid, missing sprites
     * keep a dashed placeholder cell, and the selection is multi-cell via Ctrl toggle / Shift range.
     */
    @Test
    void spriteGridBrowsesThumbnailsAndMultiSelectsWithCtrlAndShift() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-sprite-grid");
            ModProject project = controller.createProject("gridmod", root);
            controller.addContent(ContentType.block, 0d, 0d);

            for (String name : List.of("a", "b")) {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 4,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
                img.setRGB(0, 0, 0xFFFF0000);
                Path file = root.resolve("sprites/" + name + ".png");
                Files.createDirectories(file.getParent());
                javax.imageio.ImageIO.write(img, "png", file.toFile());
                project.assets.add(new dev.modmaker.core.model.AssetRef("sprites/" + name + ".png",
                    Files.size(file), "sprite"));
            }

            SpritePane pane = new SpritePane(controller);
            Scene scene = new Scene(pane, 1200, 700);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            pane.applyCss();
            pane.layout();

            assertEquals(List.of("sprites/a.png", "sprites/b.png"), pane.listedFiles());
            assertEquals(3, pane.cellCount(), "two thumbnails plus the missing-sprite placeholder");
            assertEquals(1, pane.placeholderCount());
            assertTrue(pane.cellAt(2).getStyleClass().contains("missing"),
                "the placeholder is the dashed cell at the end of the grid");

            // Click semantics: plain picks one cell, Ctrl toggles, Shift extends from the anchor.
            clickNode(pane.cellAt(0), false, false);
            assertEquals(List.of("sprites/a.png"), pane.selectedIds());
            assertEquals("sprites/a.png", pane.selectedFile());
            assertTrue(pane.previewLoaded(), "a plain click previews the sprite");
            clickNode(pane.cellAt(1), false, true);
            assertEquals(2, pane.selectedIds().size(), "Ctrl adds the second cell to the selection");
            clickNode(pane.cellAt(2), true, false);
            assertEquals(3, pane.selectedIds().size(),
                "Shift unions the anchor..target range (1..2) onto the selection");
            assertEquals(3, pane.lookupAll(".sprite-cell.selected").size(),
                "every selected cell wears the accent outline");
            assertEquals(3, pane.selectionRegions().lines().count(),
                "the batch copy carries one region per selected cell");
            assertTrue(pane.selectionRegions().contains("gridmod-"),
                "regions are named like the packed atlas: " + pane.selectionRegions());

            // A placeholder previews as an explanation, never as a broken image.
            clickNode(pane.cellAt(2), false, false);
            assertFalse(pane.previewLoaded(), "the missing cell shows no bitmap");

            // The multi-selection survives the refresh every inspector keystroke fires.
            clickNode(pane.cellAt(0), false, false);
            clickNode(pane.cellAt(1), false, true);
            pane.refresh();
            assertEquals(2, pane.selectedIds().size(), "selection survives the refresh");
            Snapshotter.write(scene, Path.of("snapshots", "ui-sprite-grid.png"));
        });
    }

    /**
     * The grid builds without decoding: every png is read on a worker and posted back, never while
     * the cells are created. JavaFX's {@code Image(InputStream)} decodes on the calling thread, so
     * decoding the grid inline froze the window for seconds the first time a mod with hundreds of
     * sprites was opened.
     */
    @Test
    void spriteGridDecodesThumbnailsOffTheFxThread() throws Exception {
        SpritePane[] holder = new SpritePane[1];
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-thumbs-async");
            ModProject project = controller.createProject("thumbs", root);
            controller.addContent(ContentType.block, 0d, 0d);
            for (String name : List.of("a", "b", "c")) {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(8, 8,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
                img.setRGB(0, 0, 0xFFFF0000);
                Path file = root.resolve("sprites/" + name + ".png");
                Files.createDirectories(file.getParent());
                javax.imageio.ImageIO.write(img, "png", file.toFile());
                project.assets.add(new dev.modmaker.core.model.AssetRef("sprites/" + name + ".png",
                    Files.size(file), "sprite"));
            }
            holder[0] = new SpritePane(controller);
            assertEquals(4, holder[0].cellCount(), "three sprites plus the missing-sprite placeholder");
            // Nothing else may run in this task, so a publish cannot have landed yet: any image here
            // would have been decoded synchronously while the cells were built.
            assertEquals(0, loadedThumbnails(holder[0]),
                "building the grid must not decode a png on the FX thread");
        });

        SpritePane pane = holder[0];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int loaded = 0;
        while (System.nanoTime() < deadline && loaded < 3) {
            Thread.sleep(50);
            int[] seen = new int[1];
            onFx(() -> seen[0] = loadedThumbnails(pane));
            loaded = seen[0];
        }
        assertEquals(3, loaded, "the worker fills every thumbnail after the grid is built");
    }

    /** Thumbnails actually painted into the grid's cells (test hook for the async decode). */
    private static int loadedThumbnails(SpritePane pane) {
        int loaded = 0;
        for (int i = 0; i < pane.cellCount(); i++) {
            loaded += countThumbnails(pane.cellAt(i));
        }
        return loaded;
    }

    private static int countThumbnails(javafx.scene.Node node) {
        int count = node instanceof javafx.scene.image.ImageView view && view.getImage() != null ? 1 : 0;
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                count += countThumbnails(child);
            }
        }
        return count;
    }

    /**
     * Report P2-11 (Lospec paradigm): the preview shows the sprite's colours ranked by frequency;
     * clicking a swatch copies its hex to the clipboard and reports it in the hint label.
     */
    @Test
    void previewPaletteShowsTopColorsAndCopiesOnSwatchClick() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-palette");
            controller.createProject("palettemod", root);
            var item = controller.addContent(ContentType.item);

            // 6 red, 3 blue, 2 green, 1 transparent: the strip must rank exactly that way.
            java.awt.image.BufferedImage art = new java.awt.image.BufferedImage(4, 3,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] colors = {0xFFFF0000, 0xFFFF0000, 0xFFFF0000, 0xFFFF0000, 0xFFFF0000, 0xFFFF0000,
                0xFF0000FF, 0xFF0000FF, 0xFF0000FF, 0xFF00FF00, 0xFF00FF00, 0x00000000};
            for (int i = 0; i < colors.length; i++) {
                art.setRGB(i % 4, i / 4, colors[i]);
            }
            Path file = root.resolve("sprites/items/" + item.name + ".png");
            Files.createDirectories(file.getParent());
            javax.imageio.ImageIO.write(art, "png", file.toFile());
            controller.project().assets.add(new dev.modmaker.core.model.AssetRef(
                "sprites/items/" + item.name + ".png", Files.size(file), "sprite"));

            RecordPreview preview = new RecordPreview(controller);
            javafx.scene.layout.VBox holder = new javafx.scene.layout.VBox(preview);
            Scene scene = new Scene(holder, 396, 500);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            preview.show(item);
            holder.applyCss();
            holder.layout();

            assertEquals(List.of("#ff0000", "#0000ff", "#00ff00"), preview.paletteColors(),
                "ranked by pixel frequency, the transparent pixel excluded");
            assertEquals(3, preview.paletteSwatchCount());

            clickNode(preview.paletteSwatchAt(0), false, false);
            assertEquals("#ff0000", preview.lastPaletteCopy(), "the click reports the swatch colour");
            Node hint = preview.lookup(".palette-hint");
            assertTrue(hint != null && hint.isVisible(), "the hint label appears after a copy");
            try {
                String clipboard = javafx.scene.input.Clipboard.getSystemClipboard().getString();
                assertEquals("#ff0000", clipboard, "and the hex is on the clipboard");
            } catch (RuntimeException unavailable) {
                // A busy or unsupported clipboard must not fail the test: the hook above is the
                // platform-independent half of the copy.
            }

            // A record without any sprite loses the strip entirely (no empty swatch row).
            var bare = controller.addContent(ContentType.weather);
            preview.show(bare);
            assertEquals(0, preview.paletteSwatchCount(), "no sprite, no palette strip");
            Snapshotter.write(scene, Path.of("snapshots", "ui-palette.png"));
        });
    }

    /**
     * The bottom strip is hidden, so its three heavy panes must not walk the project on every edit.
     * Asking for a tab is the activation point; the pane catches up once and then stays live.
     */
    @Test
    void hiddenBottomPaneStaysIdleUntilItsTabIsOpened() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-lazy-bottom");
            controller.createProject("lazy-bottom", root);

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1200, 700);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());

            SpritePane pane = shell.spritePane();
            int before = pane.refreshCount();
            controller.addContent(ContentType.block, 10d, 10d);
            assertEquals(before, pane.refreshCount(),
                "a hidden pane must not re-walk the assets on every edit");

            shell.selectTab("bottom.spritesTab");
            assertTrue(pane.refreshCount() > before, "opening the tab refreshes the pane once");
        });
    }

    /**
     * P0-3: the status bar is always attached and carries the import chrome (progress bar and
     * cancel, hidden until an import arms them) plus the log drawer toggle; the drawer opens from
     * the toggle and from selectTab("bottom.log").
     */
    @Test
    void statusBarCarriesImportChromeAndLogDrawer() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-statusbar");
            controller.createProject("statusbar", root);
            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1200, 700);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            assertTrue(shell.lookup(".status-bar") != null, "the status bar is always attached");
            Node progress = shell.lookup(".import-progress");
            assertTrue(progress != null && !progress.isVisible() && !progress.isManaged(),
                "the import progress bar stays hidden until an import runs");
            Node cancel = shell.lookup(".import-cancel");
            assertTrue(cancel != null && !cancel.isVisible() && !cancel.isManaged(),
                "the cancel button stays hidden until an import runs");
            Node drawer = shell.lookup(".log-drawer");
            assertTrue(drawer != null && !drawer.isVisible(), "the log drawer starts collapsed");

            Button toggle = (Button) shell.lookup(".log-toggle");
            toggle.fire();
            assertTrue(drawer.isVisible(), "the toggle opens the log drawer");
            toggle.fire();
            assertFalse(drawer.isVisible(), "the toggle collapses it again");

            shell.selectTab("bottom.log");
            assertTrue(drawer.isVisible(), "selectTab(bottom.log) opens the drawer");
        });
    }

    /** P3: the raw JSON area is an escape hatch, read-only until the advanced toggle is set. */
    @Test
    void rawJsonIsReadOnlyUntilTheAdvancedToggle() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-raw");
            controller.createProject("raw", root);
            var block = controller.addContent(ContentType.block);

            AppShell shell = new AppShell(controller);
            controller.select(block);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            javafx.scene.control.TextArea raw =
                (javafx.scene.control.TextArea) shell.inspector().lookup(".raw-tree");
            assertTrue(raw != null, "the raw area exists");
            assertFalse(raw.isEditable(), "raw json is read-only by default");

            for (Node node : shell.inspector().lookupAll(".tool-check")) {
                if (node instanceof javafx.scene.control.CheckBox box
                    && Messages.t("inspector.raw.edit").equals(box.getText())) {
                    box.setSelected(true);
                }
            }
            assertTrue(raw.isEditable(), "the advanced toggle enables editing");
        });
    }

    /** P3: the preview tab strip is flush above the preview canvas (same left/right edges). */
    @Test
    void cosmosPreviewTabsSitFlushAboveThePreview() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-cosmos-tabs");
            controller.createProject("cosmos-tabs", root);
            var planet = controller.addContent(ContentType.planet);
            controller.select(planet);

            AppShell shell = new AppShell(controller);
            shell.showWorkspace(Workspace.cosmos);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            Node panel = shell.cosmosPane().getRight();
            assertTrue(panel != null, "the preview panel is in the scene");
            var panelBounds = panel.localToScene(panel.getBoundsInLocal());

            List<Node> tabs = new ArrayList<>(shell.cosmosPane().lookupAll(".preview-tab"));
            assertEquals(3, tabs.size(), "three preview tabs");
            tabs.sort(java.util.Comparator.comparingDouble(
                node -> node.localToScene(node.getBoundsInLocal()).getMinX()));
            var first = tabs.get(0).localToScene(tabs.get(0).getBoundsInLocal());
            var last = tabs.get(2).localToScene(tabs.get(2).getBoundsInLocal());
            assertEquals(panelBounds.getMinX(), first.getMinX(), 1.0,
                "the tab strip starts at the preview panel's left edge");
            assertEquals(panelBounds.getMaxX(), last.getMaxX(), 1.0,
                "the tab strip ends at the preview panel's right edge");
        });
    }

    /** P4: the preview draws the game's sprite layers, resolved through the project atlas regions. */
    @Test
    void previewComposesGameSpriteLayers() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-preview-layers");
            controller.createProject("previewmod", root);
            String internal = controller.project().internalName();

            var block = controller.addContent(ContentType.block);
            var item = controller.addContent(ContentType.item);
            var unit = controller.addContent(ContentType.unit);
            String bbase = internal + "-" + block.name;
            String ibase = internal + "-" + item.name;
            String ubase = internal + "-" + unit.name;

            sprite(root, "sprites/blocks/" + block.name + ".png", controller);
            sprite(root, "sprites/blocks/" + block.name + "-team.png", controller);
            sprite(root, "sprites/blocks/" + block.name + "-top.png", controller);
            sprite(root, "sprites/items/" + item.name + ".png", controller);
            sprite(root, "sprites/units/" + unit.name + "-outline.png", controller);
            sprite(root, "sprites/units/" + unit.name + ".png", controller);

            RecordPreview preview = new RecordPreview(controller);
            preview.show(block);
            assertEquals(List.of(bbase, bbase + "-team", bbase + "-top"), preview.lastLayers(),
                "block: body then team mask then top overlay, in game draw order");

            preview.show(item);
            assertEquals(List.of(ibase), preview.lastLayers(), "item: its atlas icon");

            preview.show(unit);
            assertEquals(List.of(ubase + "-outline", ubase), preview.lastLayers(),
                "unit: the outline sits behind the body");
        });
    }

    /**
     * Patch records are registered under the vanilla name, so their override sprites live in
     * sprites-override/ without the mod prefix. Conveyors draw a framed {@code -frame-direction}
     * body; the preview must pick the deterministic {@code -0-0} instead of a {@code -team} part.
     */
    @Test
    void patchPreviewUsesOverrideSpritesAndConveyorFramesAreTheBody() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-preview-override");
            controller.createProject("previewpatch", root);
            String internal = controller.project().internalName();

            var patch = controller.addContent(ContentType.block);
            patch.patch = true;
            sprite(root, "sprites-override/blocks/" + patch.name + ".png", controller);
            RecordPreview preview = new RecordPreview(controller);
            preview.show(patch);
            assertEquals(List.of(patch.name), preview.lastLayers(),
                "a patch previews its unprefixed sprites-override region");

            var conveyor = controller.addContent(ContentType.block);
            conveyor.fields.put("type", new JsonPrimitive("Conveyor"));
            String base = internal + "-" + conveyor.name;
            sprite(root, "sprites/blocks/" + conveyor.name + "-0-1.png", controller);
            sprite(root, "sprites/blocks/" + conveyor.name + "-0-0.png", controller);
            sprite(root, "sprites/blocks/" + conveyor.name + "-team.png", controller);
            // Assets added through the model directly do not raise a change event; one real edit
            // is what invalidates the preview's region cache, exactly like an import would.
            controller.setField(conveyor, "size", new JsonPrimitive(1));

            SpriteStore store = new SpriteStore(controller);
            assertEquals(base + "-0-0", store.body(base),
                "the conveyor body prefers its deterministic first frame");
            preview.show(conveyor);
            assertEquals(base + "-0-0", preview.lastLayers().get(0),
                "the preview draws the conveyor frame, never the team mask as the body");

            // Floor.java:150 registers <name>1..<name>3 instead of <name>; with no base region the
            // body must fall back to the lowest numbered variant rather than the first one listed.
            var floor = controller.addContent(ContentType.block);
            floor.fields.put("type", new JsonPrimitive("Floor"));
            String floorBase = internal + "-" + floor.name;
            sprite(root, "sprites/blocks/" + floor.name + "3.png", controller);
            sprite(root, "sprites/blocks/" + floor.name + "1.png", controller);
            controller.setField(floor, "size", new JsonPrimitive(1));
            assertEquals(floorBase + "1", new SpriteStore(controller).body(floorBase),
                "a numbered floor body picks its lowest variant deterministically");

            // Framed fallback: only -0-1 exists, so the narrower frame scan must still find it
            // after the -0-0 preference misses and the team/top dash suffixes are excluded.
            var framed = controller.addContent(ContentType.block);
            framed.fields.put("type", new JsonPrimitive("Conveyor"));
            String framedBase = internal + "-" + framed.name;
            sprite(root, "sprites/blocks/" + framed.name + "-0-1.png", controller);
            controller.setField(framed, "size", new JsonPrimitive(1));
            assertEquals(framedBase + "-0-1", new SpriteStore(controller).body(framedBase),
                "a frameless conveyor body falls back to its sole framed sprite");

            // A non-patch record whose name is already category-prefixed must keep the sprite
            // naming rule; blindly prefixing fullName would look for a region that never exists.
            var prefixed = controller.addContent(ContentType.block);
            prefixed.name = "block-previewpatch-x";
            sprite(root, "sprites/blocks/block-previewpatch-x.png", controller);
            RecordPreview prefixedPreview = new RecordPreview(controller);
            prefixedPreview.show(prefixed);
            assertEquals(List.of("block-previewpatch-x"), prefixedPreview.lastLayers());
        });
    }

    /**
     * Pixel art must be scaled with nearest-neighbour: a 4x4 red/blue checker must never gain
     * blended edge colours when it is enlarged for the preview.
     */
    @Test
    void previewScalesPixelArtWithNearestNeighbour() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-preview-pixels");
            controller.createProject("pixelmod", root);
            var item = controller.addContent(ContentType.item);

            Path file = root.resolve("sprites/items/" + item.name + ".png");
            Files.createDirectories(file.getParent());
            var art = new java.awt.image.BufferedImage(4, 4,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    art.setRGB(x, y, ((x + y) & 1) == 0 ? 0xFFFF0000 : 0xFF0000FF);
                }
            }
            javax.imageio.ImageIO.write(art, "png", file.toFile());
            controller.project().assets.add(new dev.modmaker.core.model.AssetRef(
                "sprites/items/" + item.name + ".png", Files.size(file), "sprites"));

            RecordPreview preview = new RecordPreview(controller);
            preview.show(item);
            javafx.scene.layout.VBox holder = new javafx.scene.layout.VBox(preview);
            Scene scene = new Scene(holder, 340, 320);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            holder.applyCss();
            holder.layout();

            javafx.scene.image.WritableImage shot = scene.snapshot(null);
            javafx.scene.image.PixelReader reader = shot.getPixelReader();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = -1, maxY = -1, red = 0, blue = 0;
            for (int y = 40; y < 200; y++) {
                for (int x = 60; x < 300; x++) {
                    int rgb = reader.getArgb(x, y);
                    int r = (rgb >>> 16) & 0xFF, g = (rgb >>> 8) & 0xFF, b = rgb & 0xFF;
                    if (r > 200 && g < 80 && b < 80) {
                        red++;
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    } else if (b > 200 && r < 80 && g < 80) {
                        blue++;
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    }
                }
            }
            assertTrue(red > 0 && blue > 0, "the checker is rendered: red=" + red + " blue=" + blue);
            int blended = 0;
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    int rgb = reader.getArgb(x, y);
                    int r = (rgb >>> 16) & 0xFF, g = (rgb >>> 8) & 0xFF, b = rgb & 0xFF;
                    boolean isRed = r > 200 && g < 80 && b < 80;
                    boolean isBlue = b > 200 && r < 80 && g < 80;
                    if (!isRed && !isBlue) {
                        blended++;
                    }
                }
            }
            assertEquals(0, blended,
                "nearest-neighbour scaling must not introduce blended pixels inside the sprite");
        });
    }

    private static void sprite(Path root, String path, ProjectController controller) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        var image = new java.awt.image.BufferedImage(8, 8,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(image, "png", file.toFile());
        controller.project().assets.add(new dev.modmaker.core.model.AssetRef(
            path, Files.size(file), path.startsWith("sprites-override/") ? "sprites-override" : "sprites"));
    }

    /** The context panel is the single right-hand surface: 属性/预览/校验/文本 in four tabs. */
    @Test
    void contextPanelExposesValidationAndTextTabs() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-context");
            controller.createProject("context", root);
            var block = controller.addContent(ContentType.block);
            JsonObject reference = new JsonObject();
            reference.addProperty("item", "not-a-real-item-xyz");
            reference.addProperty("amount", 1);
            JsonArray requirements = new JsonArray();
            requirements.add(reference);
            controller.setField(block, "requirements", requirements);

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            Node context = shell.lookup(".context-tabs");
            assertTrue(context != null, "the right panel is a tabbed context panel");
            assertEquals(4, context.lookupAll(".tab").size(), "属性/预览/校验/文本 in four tabs");

            shell.selectTab("bottom.validation");
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();
            assertTrue(shell.lookup(".issue-warning") != null || shell.lookup(".issue-error") != null,
                "the validation tab is reachable and shows the unresolved reference");

            // The text tab: the filter row carries the only margin, the table sits edge to edge
            // (no .panel ring around it inside the context panel).
            shell.selectTab("bottom.strings");
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();
            assertTrue(shell.lookup(".context-tabs .table-view") != null,
                "the string table is reachable");
            Snapshotter.write(scene, Path.of("snapshots", "ui-context-strings.png"));
        });
    }

    /**
     * Report P2-12: the context panel's head names the selected record and carries its own finding
     * count from the published validation pass, so every tab - not just 属性 - shows what the
     * panel is about, and the head follows the selection as it changes.
     */
    @Test
    void contextPanelHeadTracksTheSelectedRecord() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-context-head");
            controller.createProject("context-head", root);

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            Node head = shell.lookup(".context-head");
            assertTrue(head != null, "the context panel carries a selection head");
            javafx.scene.control.Label name =
                (javafx.scene.control.Label) head.lookup(".context-head-name");
            javafx.scene.control.Label findings =
                (javafx.scene.control.Label) head.lookup(".context-head-findings");
            assertEquals(Messages.t("inspector.noSelection"), name.getText(),
                "the head says so while nothing is selected");

            // addContent selects what it adds, so the head has already moved to the item when the
            // block is built with its unresolved reference and validated synchronously - exactly
            // the pass the 校验 tab and the inspector's report block read from.
            var block = controller.addContent(ContentType.block);
            JsonObject reference = new JsonObject();
            reference.addProperty("item", "not-a-real-item-xyz");
            reference.addProperty("amount", 1);
            JsonArray requirements = new JsonArray();
            requirements.add(reference);
            controller.setField(block, "requirements", requirements);
            var item = controller.addContent(ContentType.item);
            controller.validateQuietly();

            controller.select(block);
            assertTrue(name.getText().contains(controller.displayName(block)),
                "the head names the selected record");
            long blockFindings = controller.latestIssues().stream()
                .filter(issue -> block.id.equals(issue.contentId())).count();
            assertTrue(blockFindings > 0, "the unresolved reference is a finding for the block");
            assertEquals(Messages.t("context.head.findings", blockFindings), findings.getText(),
                "the head counts this record's findings");
            assertTrue(findings.getStyleClass().contains("context-head-error"),
                "an error finding marks the head red");

            controller.select(item);
            assertEquals(Messages.t("context.head.clean"), findings.getText(),
                "a record without findings shows the clean text");
            assertFalse(findings.getStyleClass().contains("context-head-error"),
                "the error mark follows the selection, not the last record");

            Snapshotter.write(scene, Path.of("snapshots", "ui-context-head.png"));
        });
    }

    /** The command bar and the menu share one UiAction: labels and enabled state cannot drift. */
    @Test
    void commandBarActionsShareTheirStateWithTheMenu() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-command-bar");
            controller.createProject("command-bar", root);

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            Node bar = shell.lookup(".command-bar");
            assertTrue(bar != null, "the global command bar sits under the title strip");
            List<Button> buttons = new ArrayList<>();
            for (String key : List.of("menu.edit.undo", "menu.edit.redo", "toolbar.validate",
                "menu.build.folder", "menu.build.launch", "toolbar.stopGame", "toolbar.openLog")) {
                collectButtons(bar, Messages.t(key), buttons);
            }
            assertEquals(7, buttons.size(), "all global commands have a button in the bar");

            List<Button> undo = new ArrayList<>();
            collectButtons(bar, Messages.t("menu.edit.undo"), undo);
            assertTrue(undo.get(0).isDisable(), "nothing to undo in a fresh project");

            controller.addContent(ContentType.item, 10d, 10d);
            assertFalse(undo.get(0).isDisable(), "the shared action enables after an edit");
            controller.undo();
            assertTrue(undo.get(0).isDisable(), "and disables again after undoing it");
        });
    }

    /** Every palette row must create something; the inline-only types used to be silent dead rows. */
    @Test
    void paletteOnlyListsCreatableTypes() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-palette");
            controller.createProject("palette", root);

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.showWorkspace(Workspace.canvas);
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            var rows = shell.lookupAll(".palette-row");
            assertEquals(ContentType.creatable().size(), rows.size(),
                "only file-backed types belong in the palette");
            for (Node row : rows) {
                assertTrue(row.getOnMouseClicked() != null,
                    "a palette row without a click handler is a dead affordance");
            }
        });
    }

    /**
     * The planet preview renders the sector grid of the selected planet and marks the tiles its
     * sector presets occupy; capacity follows the game's 10*3^size + 2 formula.
     */
    @Test
    void planetPreviewRendersGridAndOccupancy() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-cosmos");
            ModProject project = controller.createProject("cosmos", root);
            ContentRecord planet = controller.addContent(ContentType.planet, 0d, 0d);
            // The serpulo template preloads sectorSize=3; this test narrows it to erekir's 2.
            assertEquals(3, planet.fields.get("sectorSize").getAsInt(),
                "new planets start from the serpulo template");
            controller.setField(planet, "sectorSize", new com.google.gson.JsonPrimitive(2));
            for (int index : new int[] {5, 40, 88}) {
                ContentRecord sector = controller.addContent(ContentType.sector, 0d, 0d);
                controller.setField(sector, "planet", new com.google.gson.JsonPrimitive(planet.name));
                controller.setField(sector, "sector", new com.google.gson.JsonPrimitive(index));
            }

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.showWorkspace(Workspace.cosmos);
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();
            controller.select(planet);

            var cosmos = shell.cosmosPane();
            assertTrue(cosmos.previewRendered(), "the preview must draw the grid");
            assertEquals(93, cosmos.previewCapacity(), "sectorSize=2 -> 92 tiles + 1 slot");
            assertEquals(3, cosmos.previewOccupiedCount());
            // The other two workbench tabs: the planet appearance model and the system schematic
            // (sun + three vanilla siblings + this planet, via the serpulo template's parent).
            assertTrue(cosmos.previewAppearanceRendered(), "the appearance model must build");
            assertEquals(5, cosmos.previewSystemBodies(), "sun + erekir + tantros + serpulo + the planet");
            assertEquals(List.of("planet: " + planet.name,
                "sector: new-sector-1", "sector: new-sector-2", "sector: new-sector-3"),
                cosmos.flattenedTree());

            // The mesh layer forms: the planet mesh (NoiseMesh from the serpulo template) plus
            // the MultiMesh cloud, whose children unwrap into the same add/remove layer list.
            List<MeshEditor> editors = new ArrayList<>();
            collectMeshEditors(shell, editors);
            assertEquals(3, editors.size(),
                "1 surface mesh + 2 unwrapped cloud layers, each with its own form");
            MeshEditor meshEditor = editors.stream()
                .filter(editor -> "NoiseMesh".equals(editor.currentType()))
                .findFirst().orElseThrow();
            meshEditor.editNumber("octaves", 5);
            assertEquals(5, planet.fields.get("mesh").getAsJsonObject().get("octaves").getAsInt(),
                "mesh sub-table edits write into the record");
            assertEquals(2, planet.fields.get("mesh").getAsJsonObject().get("seed").getAsInt(),
                "untouched template keys survive");

            Snapshotter.write(scene, Path.of("snapshots", "ui-cosmos.png"));
            // Capture the two other workbench tabs for visual verification.
            cosmos.selectPreviewTab(1);
            Snapshotter.write(scene, Path.of("snapshots", "ui-appearance.png"));
            cosmos.selectPreviewTab(2);
            Snapshotter.write(scene, Path.of("snapshots", "ui-system.png"));
            // Pixel guards: every workbench tab must actually draw something.
            assertTrue(cosmos.previewNonBackgroundPixels(0) > 300, "grid tab must draw tiles");
            assertTrue(cosmos.previewNonBackgroundPixels(1) > 500, "appearance tab must draw the planet");
            assertTrue(cosmos.previewNonBackgroundPixels(2) > 200, "system tab must draw orbits");
            System.out.println("cosmos smoke: capacity=" + cosmos.previewCapacity()
                + " occupied=" + cosmos.previewOccupiedCount()
                + " appearance=" + cosmos.previewAppearanceRendered()
                + " bodies=" + cosmos.previewSystemBodies()
                + " px grid=" + cosmos.previewNonBackgroundPixels(0)
                + " px appearance=" + cosmos.previewNonBackgroundPixels(1)
                + " px system=" + cosmos.previewNonBackgroundPixels(2));
        });
    }

    /**
     * Regression for the no-op move: a press and release on a card without a drag must not become
     * an undo step, and must not mark the project dirty.
     */
    @Test
    void clickingACardWithoutDraggingIsNotAnEdit() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-click");
            controller.createProject("click", root);
            controller.addContent(ContentType.item, 100d, 100d);
            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            canvas.resize(1400, 900);
            new Scene(canvas);
            controller.save();
            assertFalse(controller.isDirty(), "test setup: a saved project is clean");
            int depth = controller.undoStackDepth();

            canvas.pressAt(200, 150, javafx.scene.input.MouseButton.PRIMARY);
            canvas.releaseAt(200, 150);

            assertEquals(depth, controller.undoStackDepth(),
                "a click on a card must not push a move command");
            assertFalse(controller.isDirty(), "and must not dirty the project");
        });
    }

    /**
     * Regression for the silent selection: pressing the empty canvas (and rubber-banding over a
     * single card) has to tell the controller, or the inspector and the planet preview keep showing
     * the record the user just deselected.
     */
    @Test
    void canvasSelectionChangesReachTheController() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-select");
            controller.createProject("select", root);
            ContentRecord item = controller.addContent(ContentType.item, 100d, 100d);

            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            canvas.resize(1400, 900);
            new Scene(canvas);
            List<ContentRecord> notifications = new ArrayList<>();
            controller.onSelectionChanged(notifications::add);

            controller.select(item);
            notifications.clear();
            canvas.pressAt(1300, 850, javafx.scene.input.MouseButton.PRIMARY);
            canvas.releaseAt(1300, 850);
            assertNull(controller.selected(),
                "an empty-canvas press clears the selection in the controller");
            assertEquals(1, notifications.size(),
                "clearing the selection must notify the other panes once: " + notifications);
            assertNull(notifications.get(0));

            // A rubber band over exactly one card selects that card's record again.
            notifications.clear();
            canvas.pressAt(20, 20, javafx.scene.input.MouseButton.PRIMARY);
            canvas.dragTo(420, 420);
            canvas.releaseAt(420, 420);
            assertEquals(item, controller.selected(),
                "the rubber band selects the single card it covers");
            assertTrue(notifications.contains(item),
                "the rubber-band selection must notify the other panes: " + notifications);
        });
    }

    /**
     * Regression for the sprite tab losing its state: any keystroke in the inspector fires a project
     * change, and the picked file plus its preview image must survive that.
     */
    @Test
    void spriteSelectionAndPreviewSurviveAProjectChange() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-sprites-keep");
            ModProject project = controller.createProject("sprites-keep", root);
            controller.addContent(ContentType.block, 0d, 0d);

            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(2, 2,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
            img.setRGB(1, 1, 0xFFFF00FF);
            Path spriteFile = root.resolve("sprites/tiny.png");
            Files.createDirectories(spriteFile.getParent());
            javax.imageio.ImageIO.write(img, "png", spriteFile.toFile());
            project.assets.add(new dev.modmaker.core.model.AssetRef("sprites/tiny.png",
                Files.size(spriteFile), "sprite"));

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.selectTab("bottom.spritesTab");
            SpritePane pane = shell.spritePane();
            pane.selectFile("sprites/tiny.png");
            assertEquals("sprites/tiny.png", pane.selectedFile());
            assertTrue(pane.previewLoaded(), "the preview loads for the picked sprite");

            controller.setField(project.contents.get(0), "health",
                new com.google.gson.JsonPrimitive(7));
            assertEquals("sprites/tiny.png", pane.selectedFile(),
                "the picked sprite survives the refresh a project change triggers");
            assertTrue(pane.previewLoaded(), "and so does the preview image");

            // Only a sprite that actually disappeared clears the list state.
            project.assets.removeIf(asset -> asset.path.equals("sprites/tiny.png"));
            pane.refresh();
            assertNull(pane.selectedFile(), "a file that is gone clears the selection");
            assertFalse(pane.previewLoaded());
        });
    }

    /**
     * Regression for the shared preview info label: every workbench tab has its own summary line,
     * so the grid and appearance text is not overwritten by the system tab's.
     */
    @Test
    void eachPreviewTabKeepsItsOwnSummaryLine() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-preview-info");
            ModProject project = controller.createProject("preview-info", root);
            ContentRecord planet = controller.addContent(ContentType.planet, 0d, 0d);
            controller.setField(planet, "sectorSize", new com.google.gson.JsonPrimitive(2));

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.showWorkspace(Workspace.cosmos);
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();
            controller.select(planet);

            CosmosPane cosmos = shell.cosmosPane();
            String grid = cosmos.previewInfo(0);
            String appearance = cosmos.previewInfo(1);
            String system = cosmos.previewInfo(2);
            assertFalse(grid.isBlank(), "the grid tab shows its sector summary");
            assertFalse(appearance.isBlank(), "the appearance tab shows its mesh type");
            assertFalse(system.isBlank(), "the system tab shows its body count");
            assertTrue(grid.contains(String.valueOf(cosmos.previewCapacity())),
                "the grid tab keeps its own text: " + grid);
            assertTrue(system.contains(String.valueOf(cosmos.previewSystemBodies())),
                "the system tab keeps its own text: " + system);
            assertFalse(system.equals(grid),
                "the grid text must not be replaced by the system text: " + grid);
            assertFalse(system.equals(appearance),
                "the appearance text must not be replaced by the system text: " + appearance);
            System.out.println("preview info: grid='" + grid + "' appearance='" + appearance
                + "' system='" + system + "'");
        });
    }

    /**
     * Regression for the mesh layer rows: removing a middle layer must not leave its neighbours on a
     * stale array index (which wrote into the wrong layer, or threw inside the FX listener), the new
     * layer button must stay below the rows, and the removal must undo back to the full list.
     */
    @Test
    void removingAMeshLayerKeepsTheRemainingRowOnItsOwnLayer() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-mesh-layers");
            controller.createProject("mesh-layers", root);
            ContentRecord planet = controller.addContent(ContentType.planet, 0d, 0d);
            // One field with two layers, and no cloud layers, so the row count is unambiguous.
            controller.setField(planet, "cloudMesh", null);
            controller.setField(planet, "mesh", dev.modmaker.core.json.Json.parse(
                "[{type: NoiseMesh, seed: 76}, {type: NoiseMesh, seed: 52}]"));
            // A different field in between: consecutive edits of one field coalesce into a single
            // undo step, and this test is about what removing a layer does on its own.
            controller.setField(planet, "seed", new com.google.gson.JsonPrimitive(7));

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.showWorkspace(Workspace.cosmos);
            controller.select(planet);
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            List<Button> removes = new ArrayList<>();
            collectButtons(shell, Messages.t("mesh.remove"), removes);
            assertEquals(2, removes.size(), "one remove button per mesh layer");
            // The mesh field and the (empty) cloud field each own an add button: take the one that
            // belongs to the same layer list as the rows this test removes.
            Button add = addButtonOf(removes.get(0), Messages.t("mesh.addChild"));
            assertTrue(add != null, "the layer list has its own add button");

            removes.get(0).fire(); // delete the first layer
            List<com.google.gson.JsonObject> left =
                dev.modmaker.core.fmt.PlanetMeshes.meshLayerConfigs(planet.fields.get("mesh"));
            assertEquals(1, left.size(), "the removed layer is gone from the field");
            assertEquals(52, left.get(0).get("seed").getAsInt(), "the other layer stayed");

            // The leftover row must write into its own layer instead of a stale array index.
            List<MeshEditor> editors = new ArrayList<>();
            collectEditors(shell, editors);
            assertEquals(1, editors.size(), "one row is left: " + editors.size());
            editors.get(0).editNumber("seed", 99);
            List<com.google.gson.JsonObject> edited =
                dev.modmaker.core.fmt.PlanetMeshes.meshLayerConfigs(planet.fields.get("mesh"));
            assertEquals(1, edited.size(), "the edit must not resurrect the removed layer");
            assertEquals(99, edited.get(0).get("seed").getAsInt(),
                "the edit lands on the remaining layer");

            // A new row belongs above the "add child mesh" button, not below it.
            add.fire();
            assertEquals(2, countMeshRows(shell), "the new row is in the list");
            assertTrue(isAboveTheAddButton(add),
                "a new layer row must land above the add button");

            controller.undo();
            List<com.google.gson.JsonObject> restored =
                dev.modmaker.core.fmt.PlanetMeshes.meshLayerConfigs(planet.fields.get("mesh"));
            assertEquals(2, restored.size(), "undo brings the removed layer back: " + restored);
            assertEquals(76, restored.get(0).get("seed").getAsInt(),
                "the restored list is the original one");
        });
    }

    /**
     * A language switch rebuilds the toolbar and both tab strips: the selected workspace and the
     * selected bottom tab have to survive it (the panes themselves are reused).
     */
    @Test
    void rebuildingTheShellKeepsTheSelectedTabs() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-rebuild");
            controller.createProject("rebuild", root);
            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.showWorkspace(Workspace.cosmos);
            shell.selectTab("bottom.spritesTab");
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            Locale original = Messages.locale();
            try {
                Messages.setLocale(Locale.SIMPLIFIED_CHINESE.equals(original)
                    ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE);
                shell.applyCss();
                shell.layout();
                assertTrue(workspaceTabSelected(shell, Workspace.cosmos),
                    "the workspace tab must survive a language switch");
                assertTrue(bottomTabSelected(shell, "bottom.spritesTab"),
                    "the bottom tab must survive a language switch");
            } finally {
                Messages.setLocale(original);
                shell.applyCss();
                shell.layout();
            }
        });
    }

    /** True when the centre strip shows the given workspace. */
    private static boolean workspaceTabSelected(AppShell shell, Workspace workspace) {
        javafx.scene.control.TabPane tabs = shell.centerTabs();
        if (tabs == null) {
            return false;
        }
        var selected = tabs.getSelectionModel().getSelectedItem();
        return selected != null && Messages.t("workspace." + workspace).equals(selected.getText());
    }

    /**
     * True when the bottom strip's tab pane has the tab with the given message key selected. Reads
     * the pane itself rather than {@code getBottom()}, so it holds whether or not the strip is
     * currently attached to the window (see AppShell.BOTTOM_STRIP_VISIBLE).
     */
    private static boolean bottomTabSelected(AppShell shell, String key) {
        javafx.scene.control.TabPane tabs = shell.bottomTabs();
        if (tabs == null) {
            return false;
        }
        var selected = tabs.getSelectionModel().getSelectedItem();
        return selected != null && Messages.t(key).equals(selected.getText());
    }

    private static void collectMeshEditors(Node node, List<MeshEditor> out) {
        if (node instanceof MeshEditor editor) {
            out.add(editor);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectMeshEditors(child, out);
            }
        }
    }

    /** The add/remove buttons of the mesh layer rows currently in the scene graph (test hook). */
    private static void collectButtons(Node node, String text, List<Button> out) {
        if (node instanceof Button button && text.equals(button.getText())) {
            out.add(button);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectButtons(child, text, out);
            }
        }
    }

    /** How many mesh editors (one per layer row) are in the scene graph right now. */
    private static int countMeshRows(Node root) {
        List<MeshEditor> editors = new ArrayList<>();
        collectMeshEditors(root, editors);
        return editors.size();
    }

    /** The add button that shares a layer list with one of its row buttons. */
    private static Button addButtonOf(Node rowButton, String text) {
        if (rowButton.getParent() == null || rowButton.getParent().getParent() == null) {
            return null;
        }
        for (Node child : rowButton.getParent().getParent().getChildrenUnmodifiable()) {
            if (child instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    /** True when a layer row sits above the add button inside the same box. */
    private static boolean isAboveTheAddButton(Button add) {
        if (add.getParent() == null) {
            return false;
        }
        for (Node child : add.getParent().getChildrenUnmodifiable()) {
            if (child == add) {
                return false;
            }
            if (containsMeshEditor(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMeshEditor(Node node) {
        if (node instanceof MeshEditor) {
            return true;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (containsMeshEditor(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The board node id that shows a given content record. */
    private static String boardNodeId(ProjectController controller, String contentId) {
        return controller.board().nodes.stream()
            .filter(node -> contentId.equals(node.contentId))
            .findFirst().orElseThrow().id;
    }

    private static int countCards(Node node) {
        if (node instanceof ContentCard || node instanceof CarrierCard) {
            return 1;
        }
        if (node instanceof Parent parent) {
            int total = 0;
            for (Node child : parent.getChildrenUnmodifiable()) {
                total += countCards(child);
            }
            return total;
        }
        return 0;
    }

    /**
     * A planet whose mesh/cloudMesh are bare arrays (the game's MultiMesh shorthand - frostnova
     * in Exoprosopa) must map EVERY layer into the inspector as its own editable form, with the
     * mod's values, not editor defaults for a single mesh object.
     */
    @Test
    void inspectorMapsEveryLayerOfArrayFormMeshes() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-ui-mesh");
            controller.createProject("mesh-ui", root);
            ContentRecord planet = controller.addContent(ContentType.planet, 0d, 0d);
            controller.setField(planet, "mesh", dev.modmaker.core.json.Json.parse(
                "[{type: NoiseMesh, seed: 76, divisions: 5, color1: 252d40},"
                    + " {type: NoiseMesh, seed: 52, divisions: 5, color1: 505e7f}]"));
            controller.setField(planet, "cloudMesh", dev.modmaker.core.json.Json.parse(
                "[{type: HexSkyMesh, seed: 3, divisions: 5}]"));

            AppShell shell = new AppShell(controller);
            Scene scene = new Scene(shell, 1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            shell.showWorkspace(Workspace.cosmos);
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();
            controller.select(planet);
            shell.applyCss();
            shell.layout();
            shell.applyCss();
            shell.layout();

            List<MeshEditor> editors = new ArrayList<>();
            collectEditors(shell, editors);
            assertEquals(3, editors.size(),
                "two surface layers + one cloud layer, each with its own form");
            assertEquals(1, editors.stream()
                .filter(editor -> "HexSkyMesh".equals(editor.currentType())).count());
        assertTrue(editors.stream().anyMatch(editor -> editor.currentValue() != null
                && editor.currentValue().get("seed") != null
                && editor.currentValue().get("seed").getAsInt() == 76),
            "the first biome's seed is mapped into its form, not a default");
        // appearance-tab close-up with the game-faithful lighting, clouds and atmosphere
        controller.setField(planet, "atmosphereColor", new com.google.gson.JsonPrimitive("93e2ee"));
        shell.cosmosPane().selectPreviewTab(1);
        shell.cosmosPane().previewSnapshot(1);
        Snapshotter.write(new javafx.scene.Scene(
            new javafx.scene.Group(shell.cosmosPane()), 720, 420),
            Path.of("snapshots", "ui-appearance-layers.png"));
        });
    }

    private static void collectEditors(Node node, List<MeshEditor> into) {
        if (node instanceof MeshEditor editor) {
            into.add(editor);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectEditors(child, into);
            }
        }
    }

    private static int countInputPorts(Node node) {
        if (node instanceof ContentCard card) {
            return (int) card.ports().stream().filter(port -> port.fieldKey != null).count();
        }
        if (node instanceof Parent parent) {
            int total = 0;
            for (Node child : parent.getChildrenUnmodifiable()) {
                total += countInputPorts(child);
            }
            return total;
        }
        return 0;
    }

    // --- tech tree re-parent drags (report P1 items 4 & 5) --------------------------------------

    /** The small forest the drag tests share: root core with children kiln and drill, plus wire
     * hanging off the vanilla "copper" group, so a vanilla parent node sits on the canvas too. */
    private record ReconnectFixture(TechTreePane pane, ProjectController controller,
        ContentRecord core, ContentRecord kiln, ContentRecord drill, ContentRecord wire) { }

    private static ReconnectFixture reconnectFixture() throws Exception {
        SchemaRegistry registry = SchemaRegistry.loadDefault();
        ProjectController controller = new ProjectController(registry);
        Path root = Files.createTempDirectory("mm-techtree-reconnect");
        controller.createProject("techtree", root);
        ContentRecord core = controller.addContent(ContentType.block, 60d, 60d);
        ContentRecord kiln = controller.addContent(ContentType.block, 460d, 60d);
        ContentRecord drill = controller.addContent(ContentType.block, 60d, 460d);
        ContentRecord wire = controller.addContent(ContentType.block, 460d, 460d);
        core.fields.put("research", rootNodeValue());
        kiln.fields.put("research", new JsonPrimitive(core.fullName("techtree")));
        drill.fields.put("research", new JsonPrimitive(core.fullName("techtree")));
        wire.fields.put("research", new JsonPrimitive("copper"));
        return new ReconnectFixture(new TechTreePane(controller), controller, core, kiln, drill,
            wire);
    }

    /** Dragging a node's input port onto another node rewrites research.parent - prefixed mod name,
     * undoable field edit, highlight while hovering, and undo/redo round-trip. */
    @Test
    void techTreePortDragReparentsNode() throws Exception {
        onFx(() -> {
            ReconnectFixture fix = reconnectFixture();
            TechTreePane pane = fix.pane();
            double[] drillPort = pane.portWorld(fix.drill().id);
            assertTrue(drillPort != null, "every record node carries an input port");
            int depth = fix.controller().undoStackDepth();
            assertTrue(pane.beginReconnectAt(drillPort[0], drillPort[1]),
                "pressing the port starts the drag");
            assertTrue(pane.reconnectActive());
            double[] kilnCenter = pane.nodeCenter(fix.kiln().id);
            pane.dragReconnectTo(kilnCenter[0], kilnCenter[1]);
            assertEquals(fix.kiln().id, pane.reconnectTargetId(),
                "the node under the cursor is the prospective parent");
            assertTrue(pane.nodeStyles(fix.kiln().id).contains("target"), "it is highlighted");
            assertTrue(pane.endReconnectAt(kilnCenter[0], kilnCenter[1]));
            assertFalse(pane.reconnectActive(), "the gesture is over");
            assertEquals(new JsonPrimitive(fix.kiln().fullName("techtree")),
                fix.drill().fields.get("research"),
                "the drop rewrote the parent with the prefixed mod name");
            assertEquals(depth + 1, fix.controller().undoStackDepth(),
                "the re-parent is one undoable field edit");
            fix.controller().undo();
            assertEquals(new JsonPrimitive(fix.core().fullName("techtree")),
                fix.drill().fields.get("research"), "undo restores the old parent");
            fix.controller().redo();
            assertEquals(new JsonPrimitive(fix.kiln().fullName("techtree")),
                fix.drill().fields.get("research"), "redo replays the re-parent");
            assertFalse(pane.nodeStyles(fix.kiln().id).contains("target"),
                "no stale highlight survives the drop");
        });
    }

    /** A vanilla group node has no port but is a legal drop target: it keeps the bare name. */
    @Test
    void techTreePortDragCanReparentOntoVanillaNode() throws Exception {
        onFx(() -> {
            ReconnectFixture fix = reconnectFixture();
            TechTreePane pane = fix.pane();
            double[] copper = pane.vanillaCenter("copper");
            assertTrue(copper != null, "the vanilla parent group sits on the canvas");
            double[] drillPort = pane.portWorld(fix.drill().id);
            assertTrue(pane.beginReconnectAt(drillPort[0], drillPort[1]));
            pane.dragReconnectTo(copper[0], copper[1]);
            assertTrue(pane.endReconnectAt(copper[0], copper[1]));
            assertEquals(new JsonPrimitive("copper"), fix.drill().fields.get("research"),
                "a vanilla parent keeps the bare name the game resolves it by");
        });
    }

    /** Dropping on empty space would orphan the node (the game ignores parentless non-roots), so
     * it leaves the tree entirely - and comes back on undo. */
    @Test
    void techTreePortDropOnEmptySpaceRemovesNode() throws Exception {
        onFx(() -> {
            ReconnectFixture fix = reconnectFixture();
            TechTreePane pane = fix.pane();
            double[] kilnPort = pane.portWorld(fix.kiln().id);
            assertTrue(pane.beginReconnectAt(kilnPort[0], kilnPort[1]));
            assertTrue(pane.endReconnectAt(100000, 100000));
            assertNull(fix.kiln().fields.get("research"),
                "an orphaned non-root is removed like the toolbar remove button does");
            fix.controller().undo();
            assertEquals(new JsonPrimitive(fix.core().fullName("techtree")),
                fix.kiln().fields.get("research"), "undo brings the node back");
        });
    }

    /** A root has no parent to break, a node cannot parent itself, and a descendant would loop -
     * each drop writes nothing, the loop case says so in the hint. */
    @Test
    void techTreeReconnectRejectsRootSelfAndDescendantDrops() throws Exception {
        onFx(() -> {
            ReconnectFixture fix = reconnectFixture();
            TechTreePane pane = fix.pane();
            int depth = fix.controller().undoStackDepth();

            double[] corePort = pane.portWorld(fix.core().id);
            assertTrue(corePort != null, "a root node carries a port too (it can get a parent)");
            assertTrue(pane.beginReconnectAt(corePort[0], corePort[1]));
            assertTrue(pane.endReconnectAt(100000, 100000));
            assertTrue(fix.core().fields.get("research").isJsonObject(), "the root stays a root");

            double[] kilnPort = pane.portWorld(fix.kiln().id);
            pane.beginReconnectAt(kilnPort[0], kilnPort[1]);
            double[] kilnCenter = pane.nodeCenter(fix.kiln().id);
            pane.endReconnectAt(kilnCenter[0], kilnCenter[1]);
            assertEquals(new JsonPrimitive(fix.core().fullName("techtree")),
                fix.kiln().fields.get("research"), "a self-drop keeps the parent, no disconnect");

            pane.beginReconnectAt(corePort[0], corePort[1]);
            double[] drillCenter = pane.nodeCenter(fix.drill().id);
            pane.dragReconnectTo(drillCenter[0], drillCenter[1]);
            assertFalse(pane.nodeStyles(fix.drill().id).contains("target"),
                "a descendant never highlights as a parent");
            assertTrue(pane.endReconnectAt(drillCenter[0], drillCenter[1]));
            assertEquals(Messages.t("techtree.reconnect.cycle"), pane.hintLabel().getText());
            assertEquals(depth, fix.controller().undoStackDepth(),
                "the rejected drops wrote nothing");
        });
    }

    /** Report P1-5 (tech tree side): a drop just outside a compatible node snaps onto it. */
    @Test
    void techTreePortDragSnapsToNearestNode() throws Exception {
        onFx(() -> {
            ReconnectFixture fix = reconnectFixture();
            TechTreePane pane = fix.pane();
            double[] drillPort = pane.portWorld(fix.drill().id);
            assertTrue(pane.beginReconnectAt(drillPort[0], drillPort[1]));
            double[] kilnCenter = pane.nodeCenter(fix.kiln().id);
            double x = kilnCenter[0] - TechTreeCanvasLayout.NODE_SIZE / 2 - 5;
            double y = kilnCenter[1];
            pane.dragReconnectTo(x, y);
            assertEquals(fix.kiln().id, pane.reconnectTargetId(),
                "five pixels outside the node body still snaps to it");
            assertTrue(pane.endReconnectAt(x, y));
            assertEquals(new JsonPrimitive(fix.kiln().fullName("techtree")),
                fix.drill().fields.get("research"));
        });
    }

    // --- planet lanes (report P2-10) -------------------------------------------------------------

    /** The lane fixture: root core on erpulo, child kiln on serpulo, child drill with no planet,
     * so two lanes exist and one node is still lane-less and can be dragged into either. */
    private record LaneFixture(TechTreePane pane, ProjectController controller,
        ContentRecord core, ContentRecord kiln, ContentRecord drill) { }

    private static LaneFixture laneFixture() throws Exception {
        SchemaRegistry registry = SchemaRegistry.loadDefault();
        ProjectController controller = new ProjectController(registry);
        Path root = Files.createTempDirectory("mm-techtree-lanes");
        controller.createProject("lanes", root);
        ContentRecord core = controller.addContent(ContentType.block, 60d, 60d);
        ContentRecord kiln = controller.addContent(ContentType.block, 460d, 60d);
        ContentRecord drill = controller.addContent(ContentType.block, 60d, 460d);
        JsonObject coreResearch = rootNodeValue();
        coreResearch.addProperty("planet", "erpulo");
        core.fields.put("research", coreResearch);
        JsonObject kilnResearch = new JsonObject();
        kilnResearch.addProperty("parent", core.fullName("lanes"));
        kilnResearch.addProperty("planet", "serpulo");
        kiln.fields.put("research", kilnResearch);
        drill.fields.put("research", new JsonPrimitive(core.fullName("lanes")));
        return new LaneFixture(new TechTreePane(controller), controller, core, kiln, drill);
    }

    /** Report P2-10: one coloured lane box per distinct research.planet, drawn behind the nodes. */
    @Test
    void techTreeDrawsOnePlanetLanePerResearchPlanet() throws Exception {
        onFx(() -> {
            LaneFixture fix = laneFixture();
            TechTreePane pane = fix.pane();
            assertEquals(2, pane.laneCount(), "erpulo and serpulo each get a lane");
            assertEquals(List.of("erpulo", "serpulo"), pane.lanePlanets(),
                "lanes keep the first-seen order");
            assertTrue(pane.lookup(".tech-lane") != null, "the lane boxes are in the scene graph");
        });
    }

    /** Dragging a node's body into another planet's lane rewrites research.planet - parent kept,
     * one undoable field command, highlight while hovering, and an undo/redo round-trip. */
    @Test
    void techTreeLaneDragMovesNodeToAnotherPlanetAndUndoes() throws Exception {
        onFx(() -> {
            LaneFixture fix = laneFixture();
            TechTreePane pane = fix.pane();
            double[] drillCenter = pane.nodeCenter(fix.drill().id);
            double[] kilnCenter = pane.nodeCenter(fix.kiln().id);
            int depth = fix.controller().undoStackDepth();
            assertTrue(pane.beginLaneDragAt(drillCenter[0], drillCenter[1]),
                "pressing a record node's body arms the lane drag");
            pane.dragLaneTo(kilnCenter[0], kilnCenter[1]);
            assertEquals("serpulo", pane.laneTargetPlanet(), "the hovered lane is the new planet");
            assertTrue(pane.endLaneAt(kilnCenter[0], kilnCenter[1]));
            assertFalse(pane.laneDragActive(), "the gesture is over");
            assertNull(pane.laneTargetPlanet(), "no stale lane highlight survives the drop");
            JsonObject research = fix.drill().fields.get("research").getAsJsonObject();
            assertEquals("serpulo", research.get("planet").getAsString(),
                "the drop rewrote research.planet");
            assertEquals(fix.core().fullName("lanes"), research.get("parent").getAsString(),
                "the parent chain rides along untouched");
            assertEquals(depth + 1, fix.controller().undoStackDepth(),
                "the re-assign is one undoable field edit");
            fix.controller().undo();
            assertEquals(new JsonPrimitive(fix.core().fullName("lanes")),
                fix.drill().fields.get("research"), "undo restores the bare parent string");
            fix.controller().redo();
            assertEquals("serpulo", fix.drill().fields.get("research").getAsJsonObject()
                .get("planet").getAsString(), "redo replays the re-assign");
        });
    }

    /** Dropping back into the node's own lane, or into blank space, writes nothing at all. */
    @Test
    void techTreeLaneDragIgnoresOwnLaneAndBlankSpace() throws Exception {
        onFx(() -> {
            LaneFixture fix = laneFixture();
            TechTreePane pane = fix.pane();
            int depth = fix.controller().undoStackDepth();

            double[] kilnCenter = pane.nodeCenter(fix.kiln().id);
            assertTrue(pane.beginLaneDragAt(kilnCenter[0], kilnCenter[1]));
            pane.dragLaneTo(kilnCenter[0], kilnCenter[1]);
            assertEquals("serpulo", pane.laneTargetPlanet());
            assertTrue(pane.endLaneAt(kilnCenter[0], kilnCenter[1]));
            assertEquals("serpulo", fix.kiln().fields.get("research").getAsJsonObject()
                .get("planet").getAsString(), "a same-lane drop keeps the planet");

            double[] drillCenter = pane.nodeCenter(fix.drill().id);
            assertTrue(pane.beginLaneDragAt(drillCenter[0], drillCenter[1]));
            pane.dragLaneTo(100000, 100000);
            assertNull(pane.laneTargetPlanet(), "blank space highlights no lane");
            assertTrue(pane.endLaneAt(100000, 100000));
            assertEquals(new JsonPrimitive(fix.core().fullName("lanes")),
                fix.drill().fields.get("research"), "a blank drop writes nothing");

            assertEquals(depth, fix.controller().undoStackDepth(), "neither drop reached the stack");
        });
    }

    /** A press on a node body that never travels stays the plain click-select it always was:
     * the lane drag must not engage, and nothing is written. */
    @Test
    void techTreeBodyPressWithoutDragStillSelects() throws Exception {
        onFx(() -> {
            LaneFixture fix = laneFixture();
            TechTreePane pane = fix.pane();
            new Scene(pane, 240, 160);
            pane.applyCss();
            pane.resize(240, 160);
            pane.layout();

            int depth = fix.controller().undoStackDepth();
            javafx.scene.Node body = pane.nodeBody(fix.drill().id);
            assertTrue(body != null, "the record node's body is in the scene graph");
            body.fireEvent(mouseAt(body, javafx.scene.input.MouseEvent.MOUSE_PRESSED, 30, 30));
            assertFalse(pane.laneDragActive(), "a press alone does not start the lane drag");
            assertFalse(pane.reconnectActive(), "nor does it start the port drag");
            body.fireEvent(mouseAt(body, javafx.scene.input.MouseEvent.MOUSE_RELEASED, 30, 30));
            body.fireEvent(click());
            assertEquals(fix.drill().id, fix.controller().selected().id,
                "the click still selects the record");
            assertEquals(new JsonPrimitive(fix.core().fullName("lanes")),
                fix.drill().fields.get("research"), "and the press wrote nothing");
            assertEquals(depth, fix.controller().undoStackDepth());
        });
    }

    /** Gesture arbitration: a press that lands on a port is the batch-A re-parent drag, never a
     * lane drag - the port branch runs first and takes the press. */
    @Test
    void techTreePortPressWinsOverLaneDrag() throws Exception {
        onFx(() -> {
            LaneFixture fix = laneFixture();
            TechTreePane pane = fix.pane();
            new Scene(pane, 240, 160);
            pane.applyCss();
            pane.resize(240, 160);
            pane.layout();
            Node techCanvas = pane.lookup(".tech-canvas");

            int depth = fix.controller().undoStackDepth();
            double[] drillPort = pane.portWorld(fix.drill().id);
            double localX = drillPort[0] * pane.zoomValue() + pane.panXValue();
            double localY = drillPort[1] * pane.zoomValue() + pane.panYValue();
            techCanvas.fireEvent(mouseAt(techCanvas,
                javafx.scene.input.MouseEvent.MOUSE_PRESSED, localX, localY));
            assertTrue(pane.reconnectActive(), "the port grabs first (batch A)");
            assertFalse(pane.laneDragActive(), "the lane drag stays asleep");
            techCanvas.fireEvent(mouseAt(techCanvas,
                javafx.scene.input.MouseEvent.MOUSE_RELEASED, localX, localY));
            assertFalse(pane.reconnectActive());
            assertEquals(new JsonPrimitive(fix.core().fullName("lanes")),
                fix.drill().fields.get("research"), "a self-drop wrote nothing");
            assertEquals(depth, fix.controller().undoStackDepth());
        });
    }

    // --- cosmos sector-to-planet drag (report P2-10, handover todo #2) ----------------------------

    /** The drop handler's write, through the seam the cells call: one undoable field command, a
     * same-planet drop is a no-op that never reaches the stack, undo/redo round-trips. */
    @Test
    void cosmosSectorDragReassignsPlanetUndoably() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-cosmos-drag");
            controller.createProject("cosmos", root);
            ContentRecord planet = controller.addContent(ContentType.planet, 60d, 60d);
            ContentRecord sector = controller.addContent(ContentType.sector, 460d, 60d);
            CosmosPane pane = new CosmosPane(controller);
            new Scene(pane, 640, 420);

            int depth = controller.undoStackDepth();
            assertTrue(pane.reassignSector(sector, planet.name), "the drop rewrites the planet");
            assertEquals(new JsonPrimitive(planet.name), sector.fields.get("planet"));
            assertEquals(depth + 1, controller.undoStackDepth(), "one undoable field edit");
            assertFalse(pane.reassignSector(sector, planet.name),
                "a drop onto the same planet is a no-op");
            assertEquals(depth + 1, controller.undoStackDepth(), "the no-op never reaches the stack");
            controller.undo();
            assertNull(sector.fields.get("planet"), "undo clears the assignment back to default");
            controller.redo();
            assertEquals(new JsonPrimitive(planet.name), sector.fields.get("planet"),
                "redo replays the re-assign");
        });
    }

    // --- minimap & zoom LOD (report P1 items 6 & 7) ----------------------------------------------

    /**
     * Report P1-6 on the tech tree: the corner minimap starts off, the toolbar toggle switches it
     * on, the viewport frame is exactly the projected viewport, and a press-drag-release on the
     * map pans the canvas to the picked world point. The gesture must never leak out as a canvas
     * pan or a re-parent drag.
     */
    @Test
    void techTreeMinimapFramesTheViewportAndDragsTheCanvas() throws Exception {
        onFx(() -> {
            ReconnectFixture fix = reconnectFixture();
            TechTreePane pane = fix.pane();
            new Scene(pane, 240, 160);
            pane.applyCss();
            pane.resize(240, 160);
            pane.layout();

            assertFalse(pane.minimapVisible(), "the minimap starts off (report P1-6 default)");
            javafx.scene.control.ToggleButton toggle = toggleByLabel(
                (Parent) pane.toolbar(), Messages.t("techtree.minimap"));
            assertTrue(toggle != null, "the tech toolbar carries the minimap switch");
            toggle.setSelected(true);
            assertTrue(pane.minimapVisible(), "the toggle switches the map on");

            javafx.scene.layout.Region view =
                (javafx.scene.layout.Region) pane.lookup(".tech-canvas");
            double[] p = pane.minimapProjection();
            MinimapMath.Projection projection = new MinimapMath.Projection(p[0], p[1], p[2]);
            assertArrayEquals(
                MinimapMath.viewportMapRect(pane.panXValue(), pane.panYValue(), pane.zoomValue(),
                    view.getWidth(), view.getHeight(), projection, 160, 110),
                pane.minimapViewportRect(), 1e-6, "the frame is the projected viewport");

            Node map = pane.lookup(".minimap");
            assertTrue(map != null, "the minimap sits inside the canvas");
            map.fireEvent(mouseAt(map, javafx.scene.input.MouseEvent.MOUSE_PRESSED, 40, 30));
            map.fireEvent(mouseAt(map, javafx.scene.input.MouseEvent.MOUSE_DRAGGED, 100, 80));
            map.fireEvent(mouseAt(map, javafx.scene.input.MouseEvent.MOUSE_RELEASED, 100, 80));
            assertFalse(pane.reconnectActive(), "a gesture on the map never starts a re-parent drag");
            double worldX = MinimapMath.worldXAtMap(100, 160, projection);
            double worldY = MinimapMath.worldYAtMap(80, 110, projection);
            assertEquals(MinimapMath.panToCenterX(worldX, pane.zoomValue(), view.getWidth()),
                pane.panXValue(), 1e-6, "the drag recentres the canvas on the picked point");
            assertEquals(MinimapMath.panToCenterY(worldY, pane.zoomValue(), view.getHeight()),
                pane.panYValue(), 1e-6);
            assertArrayEquals(
                MinimapMath.viewportMapRect(pane.panXValue(), pane.panYValue(), pane.zoomValue(),
                    view.getWidth(), view.getHeight(), projection, 160, 110),
                pane.minimapViewportRect(), 1e-6, "the frame follows the pan");

            toggle.setSelected(false);
            assertFalse(pane.minimapVisible(), "the toggle switches the map back off");
        });
    }

    /**
     * Report P1-6 on the content canvas: the AppShell workspace toolbar toggle drives the corner
     * minimap, every card becomes one block plus the viewport frame, and a drag on the map pans
     * the canvas (the overlay's gesture never reaches the pane's own press handlers).
     */
    @Test
    void canvasMinimapFramesViewportAndAppShellToggleDrivesIt() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-minimap-canvas");
            controller.createProject("minimap", root);
            controller.addContent(ContentType.item, 100d, 100d);
            controller.addContent(ContentType.block, 500d, 100d);

            AppShell shell = new AppShell(controller);
            new Scene(shell, 1200, 800);
            shell.applyCss();
            NodeCanvasPane canvas = shell.canvas();
            canvas.resize(1400, 900);
            assertFalse(canvas.minimapVisible(), "the content minimap starts off too");

            javafx.scene.control.ToggleButton toggle = null;
            for (Node bar : shell.lookupAll(".tool-bar")) {
                toggle = toggleByLabel((Parent) bar, Messages.t("canvas.minimap"));
                if (toggle != null) {
                    break;
                }
            }
            assertTrue(toggle != null, "the canvas workspace toolbar carries the minimap switch");
            toggle.setSelected(true);
            assertTrue(canvas.minimapVisible());

            Node map = canvas.lookup(".minimap");
            assertTrue(map != null);
            assertEquals(3, ((Parent) map).getChildrenUnmodifiable().size(),
                "every card gets one block next to the viewport frame");

            double[] p = canvas.minimapProjection();
            MinimapMath.Projection projection = new MinimapMath.Projection(p[0], p[1], p[2]);
            assertArrayEquals(
                MinimapMath.viewportMapRect(canvas.panXValue(), canvas.panYValue(),
                    canvas.zoomValue(), 1400, 900, projection, 160, 110),
                canvas.minimapViewportRect(), 1e-6, "the frame is the projected viewport");

            map.fireEvent(mouseAt(map, javafx.scene.input.MouseEvent.MOUSE_PRESSED, 20, 20));
            map.fireEvent(mouseAt(map, javafx.scene.input.MouseEvent.MOUSE_DRAGGED, 120, 90));
            map.fireEvent(mouseAt(map, javafx.scene.input.MouseEvent.MOUSE_RELEASED, 120, 90));
            double worldX = MinimapMath.worldXAtMap(120, 160, projection);
            double worldY = MinimapMath.worldYAtMap(90, 110, projection);
            assertEquals(MinimapMath.panToCenterX(worldX, canvas.zoomValue(), 1400),
                canvas.panXValue(), 1e-6, "the map drag pans the canvas, not the map");
            assertEquals(MinimapMath.panToCenterY(worldY, canvas.zoomValue(), 900),
                canvas.panYValue(), 1e-6);

            toggle.setSelected(false);
            assertFalse(canvas.minimapVisible(), "the toggle switches the map back off");
        });
    }

    /**
     * Report P1-7 on the tech tree: below the 0.4 threshold the world root carries the .lod class
     * and CSS hides the name labels; Ctrl+0 takes the full detail back. The batch-A port drag is
     * unaffected - hit-testing lives in world space, where zoom has no say.
     */
    @Test
    void techTreeLowZoomEngagesLodAndPortDragStillWorks() throws Exception {
        onFx(() -> {
            ReconnectFixture fix = reconnectFixture();
            TechTreePane pane = fix.pane();
            Scene scene = new Scene(pane, 240, 160);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            pane.applyCss();
            pane.resize(240, 160);
            pane.layout();

            Node world = pane.lookup(".tech-world");
            assertTrue(world != null, "the world root is in the scene graph");
            Node techCanvas = pane.lookup(".tech-canvas");

            techCanvas.fireEvent(keyPressedCtrl(javafx.scene.input.KeyCode.DIGIT0));
            assertEquals(1, pane.zoomValue(), 1e-9);
            assertFalse(world.getStyleClass().contains("lod"), "100% zoom keeps full detail");

            for (int i = 0; i < 7; i++) {
                techCanvas.fireEvent(scrollAt(120, 80, -120, false));
            }
            assertTrue(pane.zoomValue() < 0.4, "seven wheel-downs drop under the threshold");
            assertTrue(world.getStyleClass().contains("lod"), "the LOD class rides on the world root");
            pane.applyCss();
            Node label = pane.lookup(".tech-node-label");
            assertTrue(label != null && !label.isVisible(),
                "CSS visibility:hidden maps to the label node at low zoom");

            int depth = fix.controller().undoStackDepth();
            double[] drillPort = pane.portWorld(fix.drill().id);
            assertTrue(pane.beginReconnectAt(drillPort[0], drillPort[1]),
                "the port still grabs in world space at LOD zoom");
            double[] drillCenter = pane.nodeCenter(fix.drill().id);
            pane.endReconnectAt(drillCenter[0], drillCenter[1]);
            assertEquals(new JsonPrimitive(fix.core().fullName("techtree")),
                fix.drill().fields.get("research"), "a self-drop at LOD zoom changes nothing");
            assertEquals(depth, fix.controller().undoStackDepth(), "and it wrote nothing");

            techCanvas.fireEvent(keyPressedCtrl(javafx.scene.input.KeyCode.DIGIT0));
            assertFalse(world.getStyleClass().contains("lod"), "Ctrl+0 detaches the LOD class");
            pane.applyCss();
            assertTrue(pane.lookup(".tech-node-label").isVisible(), "the labels come back");
        });
    }

    /**
     * Report P1-7 on the content canvas: fitting two far-apart cards drops the zoom under 0.25,
     * the world root gains .lod and CSS hides the card titles; resetZoom brings them back. A port
     * drag at that zoom still writes the reference - the LOD is a render-time style switch only.
     */
    @Test
    void canvasLowZoomEngagesLodAndConnectionsSurviveIt() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-canvas-lod");
            controller.createProject("lod", root);
            ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
            ContentRecord block = controller.addContent(ContentType.block, 6000d, 0d);
            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            Scene scene = new Scene(canvas);
            // The scene re-sizes its root on attach: set the viewport size after, not before.
            canvas.resize(1400, 900);
            scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
            canvas.applyCss();

            Node world = canvas.lookup(".canvas-world");
            assertTrue(world != null, "the world root is in the scene graph");
            canvas.fitToContent();
            assertTrue(canvas.zoomValue() < 0.25,
                "6000px of content cannot fit above the threshold (zoom " + canvas.zoomValue() + ")");
            assertTrue(world.getStyleClass().contains("lod"), "the LOD class rides on the world root");
            canvas.applyCss();
            Node title = canvas.lookup(".node-title");
            assertTrue(title != null && !title.isVisible(), "the card title is hidden at LOD zoom");

            String itemId = boardNodeId(controller, item.id);
            String blockId = boardNodeId(controller, block.id);
            double z = canvas.zoomValue();
            javafx.geometry.Point2D out = canvas.portWorld(itemId, null);
            javafx.geometry.Point2D in = canvas.portWorld(blockId, "requirements");
            canvas.pressAt(out.getX() * z + canvas.panXValue(), out.getY() * z + canvas.panYValue(),
                javafx.scene.input.MouseButton.PRIMARY);
            canvas.dragTo(in.getX() * z + canvas.panXValue(), in.getY() * z + canvas.panYValue());
            canvas.releaseAt(in.getX() * z + canvas.panXValue(), in.getY() * z + canvas.panYValue());
            assertEquals(1, controller.board().edges.size(),
                "a connection dragged at LOD zoom still writes the reference");

            canvas.resetZoom();
            assertFalse(world.getStyleClass().contains("lod"), "Ctrl+0 detaches the LOD class");
            canvas.applyCss();
            assertTrue(canvas.lookup(".node-title").isVisible(), "titles come back at 100%");
        });
    }

    /** The cycle guard is the subtree walk itself: one-way, grandchildren included. */
    @Test
    void techTreeDescendantCheckWalksTheWholeSubtree() {
        TechTreeIndex.Entry leaf = new TechTreeIndex.Entry("l", "leaf", false,
            TechTreeIndex.Problem.none, List.of());
        TechTreeIndex.Entry mid = new TechTreeIndex.Entry("m", "mid", false,
            TechTreeIndex.Problem.none, List.of(leaf));
        TechTreeIndex.Entry top = new TechTreeIndex.Entry("t", "top", false,
            TechTreeIndex.Problem.none, List.of(mid));
        TechTreeIndex.Entry other = new TechTreeIndex.Entry("o", "other", false,
            TechTreeIndex.Problem.none, List.of());
        assertTrue(TechTreePane.isDescendant(leaf, top), "grandchildren count as descendants");
        assertTrue(TechTreePane.isDescendant(mid, top));
        assertFalse(TechTreePane.isDescendant(top, leaf), "the walk is one-way");
        assertFalse(TechTreePane.isDescendant(other, top));
        assertFalse(TechTreePane.isDescendant(top, top), "a node is not its own descendant");
    }

    /**
     * Report P1-5 (canvas side): a pending connection drag highlights and snaps to the nearest
     * compatible port - opposite kind, other card, within the snap radius - and releasing inside
     * that radius connects even where the bare hit radius would have missed.
     */
    @Test
    void canvasPortDragSnapsToNearestCompatiblePort() throws Exception {
        onFx(() -> {
            SchemaRegistry registry = SchemaRegistry.loadDefault();
            ProjectController controller = new ProjectController(registry);
            Path root = Files.createTempDirectory("mm-port-snap");
            controller.createProject("port-snap", root);
            ContentRecord item = controller.addContent(ContentType.item, 100d, 100d);
            ContentRecord block = controller.addContent(ContentType.block, 500d, 100d);
            NodeCanvasPane canvas = new NodeCanvasPane(controller);
            canvas.resize(1400, 900);
            new Scene(canvas);

            String itemId = boardNodeId(controller, item.id);
            String blockId = boardNodeId(controller, block.id);
            javafx.geometry.Point2D out = canvas.portWorld(itemId, null);
            javafx.geometry.Point2D in = canvas.portWorld(blockId, "requirements");

            // 1. Fifteen pixels past the port is outside the 11px hit radius but inside the 20px
            // snap radius: the drag highlights the port and the release connects to it anyway.
            canvas.pressAt(out.getX(), out.getY(), javafx.scene.input.MouseButton.PRIMARY);
            assertTrue(canvas.pendingSourcePort() != null,
                "pressing the ID port starts the pending drag");
            double snapX = in.getX() + 15;
            canvas.dragTo(snapX, in.getY());
            ContentCard.Port snap = canvas.pendingSnapPort();
            assertTrue(snap != null && "requirements".equals(snap.fieldKey),
                "the nearest compatible port is the snap target");
            assertTrue(snap.circle.getStyleClass().contains("port-snap"),
                "the target port circle is highlighted");
            canvas.releaseAt(snapX, in.getY());
            assertEquals(1, controller.board().edges.size(), "the snapped release connected");
            assertNull(canvas.pendingSnapPort(), "the gesture state is cleared");
            assertFalse(snap.circle.getStyleClass().contains("port-snap"),
                "the highlight is removed with the gesture");

            // 2. Same-kind and same-card ports are never candidates, and far points connect nothing.
            canvas.pressAt(out.getX(), out.getY(), javafx.scene.input.MouseButton.PRIMARY);
            javafx.geometry.Point2D blockOut = canvas.portWorld(blockId, null);
            assertTrue(canvas.nearestCompatiblePort(blockOut.getX(), blockOut.getY(),
                canvas.pendingSourcePort()) == null, "two ID ports cannot connect");
            canvas.dragTo(in.getX() + 60, in.getY());
            assertNull(canvas.pendingSnapPort(), "nothing compatible is within the snap radius");
            canvas.releaseAt(in.getX() + 60, in.getY());
            assertEquals(1, controller.board().edges.size(), "the far release connects nothing");
        });
    }
}

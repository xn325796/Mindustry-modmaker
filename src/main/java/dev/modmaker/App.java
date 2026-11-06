package dev.modmaker;

import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.ui.AppShell;
import dev.modmaker.ui.Messages;
import dev.modmaker.ui.ProjectController;
import dev.modmaker.ui.Snapshotter;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import javafx.util.Pair;
import java.nio.file.Path;
import java.util.Map;

public class App extends Application {

    /** Kept so {@link #stop()} can release the shell's worker thread once the toolkit shuts down. */
    private AppShell shell;

    @Override
    public void start(Stage stage) {
        SchemaRegistry registry;
        try {
            registry = SchemaRegistry.loadDefault();
        } catch (Exception failure) {
            new Alert(Alert.AlertType.ERROR,
                "Cannot load the schemas: " + failure.getMessage()
                    + "\nRun `gradlew schemaBootstrap` first.").showAndWait();
            Platform.exit();
            return;
        }

        ProjectController controller = new ProjectController(registry);
        shell = new AppShell(controller);
        // The windowed size - the screen's full width by 720. The app opens maximized, so this is
        // the size that comes back when the window is restored: wide enough for the whole layout,
        // and short enough to clear the taskbar on a 1080p screen (the height is capped so a
        // shorter display cannot produce a window taller than it).
        javafx.geometry.Rectangle2D work = javafx.stage.Screen.getPrimary().getVisualBounds();
        double width = work.getWidth();
        double height = Math.min(720, work.getHeight());
        Scene scene = new Scene(new DesignSurface(shell), width, height);
        scene.getStylesheets().add(App.class.getResource("/app.css").toExternalForm());

        // Frameless window: the system title bar would sit above the app's own 36px strip and give
        // the layout two headers. The strip's buttons are wired to the stage here, so the shell
        // itself never touches a window.
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle(Messages.t("app.title"));
        stage.setScene(scene);
        shell.setWindowActions(() -> stage.setIconified(true),
            () -> {
                if (shell.confirmExit()) {
                    stage.close();
                }
            });
        stage.setX(work.getMinX() + (work.getWidth() - width) / 2);
        stage.setY(work.getMinY() + (work.getHeight() - height) / 2);
        // Alt+F4 and any other close request must run the same unsaved-changes guard as our button.
        stage.setOnCloseRequest(event -> {
            if (!shell.confirmExit()) {
                event.consume();
            }
        });
        stage.show();
        // Open maximized - the shell is dense, and a windowed default gets cramped on a small
        // screen. Done after show() because the windowed bounds it restores to only exist then.
        shell.maximizeWindow();

        loadFromArguments(controller, shell, getParameters().getNamed());

        String workspace = getParameters().getNamed().get("workspace");
        if (workspace != null) {
            try {
                shell.showWorkspace(dev.modmaker.core.fmt.Workspace.valueOf(workspace));
            } catch (IllegalArgumentException unknown) {
                System.err.println("unknown workspace: " + workspace);
            }
        }

        String snapshot = getParameters().getNamed().get("snapshot");
        if (snapshot != null) {
            writeSnapshotThenExit(scene, Path.of(snapshot));
        }
    }

    /**
     * Command line entry points used for automated checks:
     * {@code --open=<project dir>}, {@code --import=<mod zip or folder>} with optional
     * {@code --projectDir=<where to unpack>}, and {@code --snapshot=<png>}.
     */
    private void loadFromArguments(ProjectController controller, AppShell shell, Map<String, String> named) {
        // Command-line runs (tests, CI, snapshots) must never block on a confirmation dialog.
        controller.setOverwriteApprover(path -> true);
        try {
            if (named.containsKey("import")) {
                Path source = Path.of(named.get("import")).toAbsolutePath();
                Path destination = named.containsKey("projectDir")
                    ? Path.of(named.get("projectDir")).toAbsolutePath()
                    : source.getParent();
                controller.importPackage(source, destination);
                // Fitting needs the canvas to have been laid out once.
                Platform.runLater(shell::justLoaded);
            } else if (named.containsKey("open")) {
                controller.openProject(Path.of(named.get("open")).toAbsolutePath());
                Platform.runLater(shell::justLoaded);
            }
            // A plain launch starts empty on purpose. (The workspace-mod probe stays available via
            // --open and as the golden tests' fixture; it was once auto-opened here and is on
            // standby for a "remember last project" feature.)
        } catch (Exception failure) {
            failure.printStackTrace();
            controller.log("could not load from the command line: " + failure);
        }
    }

    private void writeSnapshotThenExit(Scene scene, Path target) {
        PauseTransition settle = new PauseTransition(Duration.millis(2500));
        settle.setOnFinished(event -> {
            try {
                java.nio.file.Files.createDirectories(target.toAbsolutePath().getParent());
                Snapshotter.write(scene, target);
                System.out.println("snapshot: " + target.toAbsolutePath());
            } catch (Exception error) {
                error.printStackTrace();
                Platform.exit();
                System.exit(1);
            }
            Platform.exit();
        });
        settle.play();
    }

    @Override
    public void stop() {
        if (shell != null) {
            shell.shutdown();
        }
    }

    /**
     * Scene root that lays the shell out on the 1920x1020 canvas the UI exports are drawn on and
     * scales it uniformly to the window.
     *
     * <p>The stylesheet uses the exports' absolute pixels (480px inspector, 120px tabs, 24px
     * labels). A smaller logical window - e.g. 1536x816 on a 1920x1080 screen at the usual 125%
     * Windows scale - would otherwise give those fixed sizes a larger share and make the layout
     * look cramped next to the mock. Scaling the whole canvas keeps the proportions; any leftover
     * margin shows the page background, so it reads as a frame rather than a gap.
     */
    private static final class DesignSurface extends Pane {
        private static final double DESIGN_WIDTH = 1920;
        private static final double DESIGN_HEIGHT = 1020;

        DesignSurface(Node content) {
            getStyleClass().add("design-surface");
            getChildren().add(content);
        }

        @Override
        protected void layoutChildren() {
            double scale = Math.min(getWidth() / DESIGN_WIDTH, getHeight() / DESIGN_HEIGHT);
            if (scale <= 0) {
                return;
            }
            Node content = getChildren().get(0);
            content.resize(DESIGN_WIDTH, DESIGN_HEIGHT);
            // Scale about the node's centre, then offset it so the scaled canvas is centred.
            content.setScaleX(scale);
            content.setScaleY(scale);
            content.setLayoutX((getWidth() - DESIGN_WIDTH) / 2);
            content.setLayoutY((getHeight() - DESIGN_HEIGHT) / 2);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}

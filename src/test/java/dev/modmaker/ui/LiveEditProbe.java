package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.io.TestFixtures;
import dev.modmaker.core.schema.SchemaRegistry;
import javafx.scene.Scene;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end regression for the live-edit chain: commit an orbitRadius change the way the
 * inspector does, wait out the preview debounce (the fx thread must stay free - blocking it would
 * stop the very timer the debounce depends on), and require the system-view canvas to actually
 * change. Runs against whichever real mod the fixture probe finds (see {@link TestFixtures}) and
 * skips when there is none, so no mod and no machine path is written into the test.
 */
class LiveEditProbe {

    @Test
    void orbitRadiusEditMovesTheSystemView() throws Exception {
        Path projectRoot = TestFixtures.realModRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(
            projectRoot != null, "no real mod fixture found (set -Dmodmaker.fixture=<dir>)");
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch edited = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(1);
        AtomicReference<Object> failure = new AtomicReference<>();
        AtomicReference<int[]> before = new AtomicReference<>();
        AtomicReference<int[]> after = new AtomicReference<>();

        try {
            javafx.application.Platform.startup(() -> {
            });
        } catch (IllegalStateException alreadyRunning) {
            // another test in this JVM started the toolkit already
        }

        // stage 1 (fx thread): build the shell, select the planet, capture the "before" pixels
        javafx.application.Platform.runLater(() -> {
            try {
                SchemaRegistry registry = SchemaRegistry.loadDefault();
                ProjectController controller = new ProjectController(registry);
                controller.openProject(projectRoot);

                AppShell shell = new AppShell(controller);
                Scene scene = new Scene(shell, 1500, 940);
                scene.getStylesheets().add(LiveEditProbe.class.getResource("/app.css").toExternalForm());
                shell.showWorkspace(dev.modmaker.core.fmt.Workspace.cosmos);
                shell.applyCss();
                shell.layout();

                // Any planet serves: the system view sizes this planet's orbit ring, and the probe
                // only needs a record whose orbitRadius it can move - no fixture-specific name. One
                // with a parent is preferred, since that is where the ring is drawn from.
                var planets = controller.project().contents.stream()
                    .filter(record -> record.type == ContentType.planet && !record.isInline())
                    .toList();
                var planet = planets.stream()
                    .filter(record -> record.fields.containsKey("parent"))
                    .findFirst()
                    .or(() -> planets.stream().findFirst())
                    .orElseThrow(() -> new AssertionError("the fixture has no planet record"));
                var existing = planet.fields.get("orbitRadius");
                double current = existing != null && existing.isJsonPrimitive()
                    ? existing.getAsDouble() : Double.NaN;
                double moved = Double.isNaN(current) ? 60 : current + 17;
                controller.select(planet);
                shell.cosmosPane().selectPreviewTab(2);
                shell.applyCss();
                shell.layout();
                before.set(pixels(shell));
                ready.countDown();

                // stage 2 (fx thread, after the first capture): commit the edit the way the
                // inspector's live number editor does, then wait out the debounce and capture
                javafx.animation.PauseTransition gap = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(200));
                gap.setOnFinished(event -> {
                    try {
                        controller.setField(planet, "orbitRadius",
                            new com.google.gson.JsonPrimitive(moved));
                        javafx.animation.PauseTransition debounce =
                            new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));
                        debounce.setOnFinished(finish -> {
                            try {
                                after.set(pixels(shell));
                                // A mesh edit must mark the record dirty and fire the live preview.
                                // The value is normalised first: mods write cloudMesh either as an
                                // object or as the bare-array MultiMesh shorthand the game accepts.
                                var mesh = dev.modmaker.core.fmt.PlanetMeshes
                                    .normalizeMeshConfig(planet.fields.get("cloudMesh"));
                                mesh = mesh == null
                                    ? new com.google.gson.JsonObject() : mesh.deepCopy();
                                mesh.addProperty("scale", 7);
                                controller.setField(planet, "cloudMesh", mesh);
                                if (!planet.dirty) {
                                    failure.set(new AssertionError(
                                        "mesh edit did not mark the record dirty"));
                                }
                            } catch (Throwable throwable) {
                                failure.set(throwable);
                            } finally {
                                settled.countDown();
                            }
                        });
                        debounce.play();
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                        settled.countDown();
                    }
                });
                gap.play();
            } catch (Throwable throwable) {
                failure.set(throwable);
                ready.countDown();
                settled.countDown();
            }
        });

        if (!ready.await(60, TimeUnit.SECONDS)) {
            throw new AssertionError("setup timed out");
        }
        if (!settled.await(60, TimeUnit.SECONDS)) {
            throw new AssertionError("preview settle timed out");
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        boolean changed = !java.util.Arrays.equals(before.get(), after.get());
        System.out.println("LIVE-EDIT orbit canvas changed: " + changed);
        if (!changed) {
            throw new AssertionError("the system view did not react to an orbitRadius edit");
        }
    }

    private int[] pixels(AppShell shell) {
        var image = shell.cosmosPane().previewSnapshot(2);
        var reader = image.getPixelReader();
        int[] out = new int[(int) (image.getWidth() * image.getHeight())];
        int i = 0;
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                out[i++] = reader.getArgb(x, y);
            }
        }
        return out;
    }
}

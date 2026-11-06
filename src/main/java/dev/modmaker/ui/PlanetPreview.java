package dev.modmaker.ui;

import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.PlanetGrids;
import dev.modmaker.core.fmt.PlanetMeshes;
import dev.modmaker.core.fmt.PlanetOrbits;
import dev.modmaker.core.fmt.Workspace;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The planet preview workbench, one canvas per workflow:
 *
 * <ul>
 *   <li>区块网格 - the sector grid with occupied indexes (previous behavior);</li>
 *   <li>星球外观 - the planet as the game builds it: MeshBuilder.buildHex tiles displaced and
 *       colored through the ported Simplex noise, clouds from the cloudMesh layers, atmosphere
 *       rim;</li>
 *   <li>星系位置 - a schematic of the solar system: the root star, vanilla siblings for context,
 *       the planet and its moons on their computed orbit radii.</li>
 * </ul>
 *
 * All three share the same drag-to-rotate view. Everything is derived from the record's JSON, so
 * edits show up without launching the game.
 */
public final class PlanetPreview extends VBox {

    private static final int SIZE = 360;

    private final Canvas gridCanvas = new Canvas(SIZE, SIZE);
    private final Canvas appearanceCanvas = new Canvas(SIZE, SIZE);
    private final Canvas orbitCanvas = new Canvas(SIZE, SIZE);
    /** Headerless: the three canvases stack; the workspace's own bar owns the tab buttons (P3). */
    private final StackPane stage = new StackPane();
    private int selectedIndex;
    /** One summary line per tab: a single shared label would always show the last tab's text. */
    private final Label gridInfo = new Label();
    private final Label appearanceInfo = new Label();
    private final Label systemInfo = new Label();
    private final Label hint = new Label(Messages.t("cosmos.preview.hint"));

    private ModProject project;
    private int sectorSize = -1;
    private PlanetGrids.Grid grid;
    /** Rendered layers paired with their game-semantics source (unlit / radius / cloud speed). */
    private record RenderedLayer(PlanetMeshes.MeshModel model, PlanetMeshes.Layer source) {
    }
    private final List<RenderedLayer> surfaceLayers = new ArrayList<>();
    private final List<RenderedLayer> cloudLayers = new ArrayList<>();
    private PlanetMeshes.Rgb atmosphere;
    private float atmosphereRad = 0.3f;
    /** The ray-marched atmosphere, cached: recomputed only on setSource (atmosphere.frag port). */
    private javafx.scene.image.WritableImage atmosphereImage;
    /** HexSkyMesh clouds rotate like the game: relRot = time * speed / 40 (HexSkyMesh.relRot). */
    private double cloudTime;
    private String meshTypeInfo = "";
    private List<PlanetOrbits.Body> bodies = List.of();
    private Map<Integer, List<ContentRecord>> occupied = Map.of();
    private ContentRecord highlight;
    private boolean rendered;
    private double yaw = 0.7;
    private double pitch = 0.35;
    private double dragX;
    private double dragY;
    /** The cloud animation; the scene listener below pauses it whenever the pane is detached. */
    private javafx.animation.Timeline cloudTicker;
    /** Deterministic starfield for the appearance/system tabs. */
    private final double[][] stars = new double[110][3];

    {
        java.util.Random random = new java.util.Random(42);
        for (double[] star : stars) {
            star[0] = random.nextDouble();
            star[1] = random.nextDouble();
            star[2] = 0.25 + random.nextDouble() * 0.75;
        }
    }

    PlanetPreview() {
        getStyleClass().addAll("panel", "planet-preview");
        for (Label infoLabel : List.of(gridInfo, appearanceInfo, systemInfo)) {
            infoLabel.getStyleClass().add("muted");
            infoLabel.setWrapText(true);
        }
        hint.getStyleClass().add("muted");
        hint.setText(Messages.t("cosmos.preview.hint"));

        attachRotation(gridCanvas);
        attachRotation(appearanceCanvas);
        attachRotation(orbitCanvas);

        stage.getChildren().addAll(gridCanvas, appearanceCanvas, orbitCanvas);
        stage.getStyleClass().add("planet-stage");
        stage.setMinSize(SIZE, SIZE);
        stage.setPrefSize(SIZE, SIZE);
        stage.setMaxSize(SIZE, SIZE);

        setSpacing(6);
        getChildren().addAll(stage, new VBox(4, gridInfo, appearanceInfo, systemInfo), hint);
        selectTab(0);
        // HexSkyMesh clouds spin with global time (relRot = time * speed / 40); a slow ticker
        // keeps that motion alive while the appearance tab is on screen. An indefinite Timeline
        // keeps the JavaFX pulse running, so it is bound to the pane's scene: detached (discarded,
        // or a workspace tab that is not in front) means stopped.
        cloudTicker = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(120), event -> {
                if (selectedIndex == 1 && !cloudLayers.isEmpty()) {
                    cloudTime += 0.12;
                    drawAppearance();
                }
            }));
        cloudTicker.setCycleCount(javafx.animation.Animation.INDEFINITE);
        sceneProperty().addListener((observable, was, scene) -> {
            if (scene == null) {
                cloudTicker.pause();
            } else {
                cloudTicker.play();
            }
        });
        if (getScene() != null) {
            cloudTicker.play();
        }
        drawAll();
    }

    /** The three tab titles, so the workspace bar can render the tab buttons itself. */
    List<String> tabTitles() {
        return List.of(Messages.t("cosmos.preview.tab.grid"),
            Messages.t("cosmos.preview.tab.appearance"), Messages.t("cosmos.preview.tab.system"));
    }

    /** Selects one of the three canvases; 0 grid, 1 appearance, 2 system. */
    void selectTab(int index) {
        if (index < 0 || index > 2) {
            return;
        }
        selectedIndex = index;
        gridCanvas.setVisible(index == 0);
        appearanceCanvas.setVisible(index == 1);
        orbitCanvas.setVisible(index == 2);
        gridInfo.setVisible(index == 0);
        gridInfo.setManaged(index == 0);
        appearanceInfo.setVisible(index == 1);
        appearanceInfo.setManaged(index == 1);
        systemInfo.setVisible(index == 2);
        systemInfo.setManaged(index == 2);
        drawAll();
    }

    int selectedTab() {
        return selectedIndex;
    }

    private void attachRotation(Canvas canvas) {
        canvas.setOnMousePressed(event -> {
            dragX = event.getX();
            dragY = event.getY();
        });
        canvas.setOnMouseDragged(event -> {
            yaw += (event.getX() - dragX) * 0.01;
            pitch = Math.max(-1.4, Math.min(1.4, pitch + (event.getY() - dragY) * 0.01));
            dragX = event.getX();
            dragY = event.getY();
            drawAll();
        });
    }

    /**
     * Full update from the selected record: resolves the planet (a sector resolves to its planet,
     * vanilla planets included), then builds the grid state, appearance model and orbit bodies.
     */
    void setSource(ContentRecord selected, ModProject project) {
        if (selected == null || project == null) {
            clear();
            return;
        }
        this.project = project;
        this.highlight = selected.type == dev.modmaker.core.fmt.ContentType.sector ? selected : null;

        ContentRecord planet;
        String bare;
        if (selected.type == dev.modmaker.core.fmt.ContentType.planet) {
            planet = selected;
            bare = selected.name;
        } else if (selected.type == dev.modmaker.core.fmt.ContentType.sector) {
            bare = barePlanetName(PlanetGrids.sectorPlanet(selected));
            planet = planetByName(bare);
        } else {
            clear();
            return;
        }

        int size;
        if (planet != null) {
            size = PlanetGrids.intField(planet, "sectorSize", 0);
        } else {
            Integer vanillaSize = PlanetGrids.VANILLA_SIZES.get(bare.toLowerCase(java.util.Locale.ROOT));
            size = vanillaSize == null ? 0 : vanillaSize;
        }
        this.sectorSize = size;
        this.grid = size > 0 ? PlanetGrids.create(size) : null;
        this.occupied = PlanetGrids.sectorsByIndex(project, bare, size);

        // appearance model from the planet record's mesh/cloudMesh JSON; vanilla planets have no
        // record, so they render through the ported serpulo terrain generator. Real mods write
        // multi-biome planets as a bare mesh array (the game's MultiMesh shorthand) - normalise
        // it here, or the mod's own mesh fields would be ignored in favour of a generic surface.
        JsonObject meshConfig = planet == null
            ? null : PlanetMeshes.normalizeMeshConfig(planet.fields.get("mesh"));
        JsonObject cloudConfig = planet == null
            ? null : PlanetMeshes.normalizeMeshConfig(planet.fields.get("cloudMesh"));
        float radius = planet == null ? 1f : radiusOf(planet);
        readAtmosphere(planet);

        meshTypeInfo = meshConfig == null ? "" : PlanetMeshes.stringOr(meshConfig, "type", "NoiseMesh");
        rebuildAppearance(bare, meshConfig, cloudConfig, radius, size);
        this.bodies = PlanetOrbits.systemOf(project, planet, planet == null ? bare : null);
        this.atmosphereImage = null; // the scatter port is cached per record/light state
        this.rendered = false;
        drawAll();
    }

    /** Grid tile count grows as 10*3^divisions; beyond 6 (7292 tiles) a 2D canvas redraw lags. */
    private static final int MAX_DIVISIONS = 6;

    private void rebuildAppearance(String bareName, JsonObject meshConfig, JsonObject cloudConfig,
        float radius, int size) {
        surfaceLayers.clear();
        cloudLayers.clear();
        if (size <= 0) {
            return;
        }
        if (meshConfig == null) {
            // Vanilla planet: the game builds HexMesh(generator, divisions 6) at planet.radius; we
            // approximate the generator with the ported serpulo terrain.
            surfaceLayers.add(new RenderedLayer(PlanetMeshes.buildModel(
                Math.min(MAX_DIVISIONS, Math.max(size, 6)), radius,
                PlanetMeshes.serpuloMesher(0), 0.2f),
                new PlanetMeshes.Layer(PlanetMeshes.serpuloMesher(0), 0.2f, radius, size,
                    false, false, true, 0)));
        } else {
            for (PlanetMeshes.Layer layer : PlanetMeshes.surfaceLayersFromConfig(meshConfig)) {
                int divisions = Math.min(MAX_DIVISIONS,
                    Math.max(1, layer.divisions() <= 1 ? size : layer.divisions()));
                // Radius semantics from the game sources: a NoiseMesh's JSON radius is absolute
                // (NoiseMesh.java passes it straight to buildHex); SunMesh/HexMesh-style builds
                // use planet.radius. Same rule for clouds: HexSkyMesh builds at planet.radius.
                float shellRadius = layer.radiusIsPlanetRelative()
                    ? layer.radius() * radius : layer.radius();
                surfaceLayers.add(new RenderedLayer(PlanetMeshes.buildModel(divisions,
                    shellRadius, layer.mesher(), layer.intensity()), layer));
            }
        }
        for (PlanetMeshes.Layer layer : PlanetMeshes.cloudLayersFromConfig(cloudConfig)) {
            int divisions = Math.min(MAX_DIVISIONS, Math.max(1, layer.divisions()));
            cloudLayers.add(new RenderedLayer(PlanetMeshes.buildModel(divisions,
                layer.radius() * radius, layer.mesher(), layer.intensity()), layer));
        }
    }

    /** Atmosphere colour/radius live on the planet record (Planet.atmosphereColor/…RadOut). */
    private void readAtmosphere(ContentRecord planet) {
        atmosphereRad = 0.3f;
        if (planet != null) {
            var color = planet.fields.get("atmosphereColor");
            if (color != null && color.isJsonPrimitive()) {
                try {
                    atmosphere = PlanetMeshes.Rgb.of(color.getAsString());
                } catch (NumberFormatException ignored) {
                    // fall through to the vanilla default
                }
            }
            var rad = planet.fields.get("atmosphereRadOut");
            if (rad != null && rad.isJsonPrimitive()) {
                try {
                    atmosphereRad = Math.max(0.05f, rad.getAsFloat());
                } catch (NumberFormatException ignored) {
                    // keep the vanilla default
                }
            }
        }
        if (atmosphere == null) {
            atmosphere = new PlanetMeshes.Rgb(0.3f, 0.7f, 1.0f, 1f); // Planet.atmosphereColor default
        }
    }

    void clear() {
        sectorSize = -1;
        grid = null;
        surfaceLayers.clear();
        cloudLayers.clear();
        atmosphere = null;
        atmosphereImage = null;
        bodies = List.of();
        occupied = Map.of();
        highlight = null;
        rendered = false;
        drawAll();
    }

    boolean rendered() {
        return rendered;
    }

    int previewCapacity() {
        return PlanetGrids.capacity(Math.max(sectorSize, 0));
    }

    int previewOccupiedCount() {
        return occupied.size();
    }

    boolean appearanceRendered() {
        return !surfaceLayers.isEmpty();
    }

    int systemBodies() {
        return bodies.size();
    }

    /** Selects one of the three preview tabs (test hook): 0 grid, 1 appearance, 2 system. */
    void selectPreviewTab(int index) {
        selectTab(index);
    }

    /** Snapshot of one tab's canvas for tests: 0 grid, 1 appearance, 2 system. */
    javafx.scene.image.WritableImage snapshot(int tabIndex) {
        Canvas canvas = switch (tabIndex) {
            case 0 -> gridCanvas;
            case 1 -> appearanceCanvas;
            default -> orbitCanvas;
        };
        return canvas.snapshot(null, null);
    }

    /**
     * The summary line of one tab (test hook): 0 grid, 1 appearance, 2 system. Each tab owns its
     * own label, so drawing all three no longer makes the last one win.
     */
    String infoForTab(int tabIndex) {
        return switch (tabIndex) {
            case 0 -> gridInfo.getText();
            case 1 -> appearanceInfo.getText();
            default -> systemInfo.getText();
        };
    }

    /**
     * Counts pixels that differ from the panel background on one tab's canvas (test hook) - guards
     * against "renders but blank" regressions. 0 grid, 1 appearance, 2 system.
     */
    long nonBackgroundPixels(int tabIndex) {
        Canvas canvas = switch (tabIndex) {
            case 0 -> gridCanvas;
            case 1 -> appearanceCanvas;
            default -> orbitCanvas;
        };
        javafx.scene.image.WritableImage image = canvas.snapshot(null, null);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        long count = 0;
        for (int y = 0; y < (int) image.getHeight(); y += 3) {
            for (int x = 0; x < (int) image.getWidth(); x += 3) {
                int argb = reader.getArgb(x, y);
                int r = argb >> 16 & 0xFF;
                int g = argb >> 8 & 0xFF;
                int b = argb & 0xFF;
                // background is #0e1013
                if (Math.abs(r - 0x0e) + Math.abs(g - 0x10) + Math.abs(b - 0x13) > 12) {
                    count++;
                }
            }
        }
        return count;
    }

    // --- drawing ---------------------------------------------------------------------------------

    private void drawAll() {
        drawGrid();
        drawAppearance();
        drawOrbit();
    }

    private void drawGrid() {
        var g = gridCanvas.getGraphicsContext2D();
        paintBackground(g);
        if (sectorSize < 0) {
            gridInfo.setText(Messages.t("cosmos.preview.none"));
            return;
        }
        int capacity = PlanetGrids.capacity(sectorSize);
        if (grid == null) {
            gridInfo.setText(Messages.t("cosmos.preview.gridless"));
            return;
        }

        double scale = Math.min(gridCanvas.getWidth(), gridCanvas.getHeight()) * 0.42;
        double cx = gridCanvas.getWidth() / 2;
        double cy = gridCanvas.getHeight() / 2;
        g.setStroke(Color.web("#343a41"));
        g.strokeOval(cx - scale, cy - scale, scale * 2, scale * 2);

        for (PlanetGrids.Tile tile : grid.tiles) {
            drawGridTile(g, tile, scale, cx, cy);
        }

        for (Map.Entry<Integer, List<ContentRecord>> entry : occupied.entrySet()) {
            int index = entry.getKey();
            if (index >= grid.tiles.length) {
                continue;
            }
            drawTileHighlight(g, grid.tiles[index], index,
                highlight != null && entry.getValue().contains(highlight));
        }

        rendered = true;
        gridInfo.setText(Messages.t("cosmos.preview.summary", capacity, occupied.size(), sectorSize)
            + (occupied.size() > 0 && occupied.size() < grid.tiles.length
                ? "" : ""));
    }

    private void drawGridTile(javafx.scene.canvas.GraphicsContext g, PlanetGrids.Tile tile, double scale, double cx, double cy) {
        double[] normal = rotate(tile.v.x, tile.v.y, tile.v.z);
        boolean front = normal[2] > 0;
        double[] xs = new double[tile.corners.length];
        double[] ys = new double[tile.corners.length];
        for (int k = 0; k < tile.corners.length; k++) {
            PlanetGrids.Corner corner = tile.corners[k];
            double[] p = rotate(corner.v.x, corner.v.y, corner.v.z);
            xs[k] = cx + p[0] * scale;
            ys[k] = cy - p[1] * scale;
        }
        if (front) {
            g.setFill(Color.web("#24282d"));
            g.fillPolygon(xs, ys, xs.length);
            g.setStroke(Color.web("#3f464e"));
            g.setLineWidth(1);
            g.strokePolygon(xs, ys, xs.length);
        } else {
            g.setStroke(Color.web("#1d2024"));
            g.strokePolygon(xs, ys, xs.length);
        }
    }

    private void drawTileHighlight(javafx.scene.canvas.GraphicsContext g, PlanetGrids.Tile tile, int index, boolean selected) {
        double[] normal = rotate(tile.v.x, tile.v.y, tile.v.z);
        double scale = Math.min(gridCanvas.getWidth(), gridCanvas.getHeight()) * 0.42;
        double cx = gridCanvas.getWidth() / 2;
        double cy = gridCanvas.getHeight() / 2;
        double[] xs = new double[tile.corners.length];
        double[] ys = new double[tile.corners.length];
        double cxTile = 0;
        double cyTile = 0;
        for (int k = 0; k < tile.corners.length; k++) {
            PlanetGrids.Corner corner = tile.corners[k];
            double[] p = rotate(corner.v.x, corner.v.y, corner.v.z);
            xs[k] = cx + p[0] * scale;
            ys[k] = cy - p[1] * scale;
            cxTile += xs[k] / xs.length;
            cyTile += ys[k] / ys.length;
        }
        g.setStroke(selected ? Color.WHITE : Color.web("#b47df0"));
        g.setLineWidth(selected ? 2 : 1.2);
        g.strokePolygon(xs, ys, xs.length);
        if (normal[2] > 0) {
            g.setFill(selected ? Color.web("#8455bd") : Color.web("#b47df0", 0.75));
            g.fillPolygon(xs, ys, xs.length);
            g.setFill(Color.WHITE);
            g.setFont(Font.font("System", FontWeight.BOLD, 12));
            g.fillText(String.valueOf(index), cxTile - 5, cyTile + 4);
        }
    }

    private void drawAppearance() {
        var g = appearanceCanvas.getGraphicsContext2D();
        paintBackground(g);
        drawStars(g);
        if (surfaceLayers.isEmpty()) {
            appearanceInfo.setText(sectorSize >= 0 ? Messages.t("cosmos.preview.gridless") : "");
            return;
        }
        double maxExtent = 1;
        for (RenderedLayer layer : surfaceLayers) {
            maxExtent = Math.max(maxExtent, maxLayerLength(layer.model()));
        }
        double scaleFactor = Math.min(appearanceCanvas.getWidth(), appearanceCanvas.getHeight())
            * 0.42 / maxExtent;
        double cx = appearanceCanvas.getWidth() / 2;
        double cy = appearanceCanvas.getHeight() / 2;
        // Game: Shaders.planet.lightDir = normalize(solarSystem.position - planet.position). The
        // system schematic puts the sun at the centre and bodies on deterministic angles, so the
        // light comes from that same angle (the lit side faces the sun as drawn in 星系位置).
        double[] light = lightFromSystem();

        // The outermost surface layer defines the planet's body; layers with a smaller radius
        // are *inside* it. The game never sees those (depth buffer), and neither must we - mods
        // do ship cloud meshes with a radius below the planet's (e.g. 格雷工业's 端点星).
        double surfaceRadius = 0;
        for (RenderedLayer layer : surfaceLayers) {
            surfaceRadius = Math.max(surfaceRadius, layer.model().radius);
        }

        // surface layers, then their clouds, then the additive atmosphere (game render order:
        // planet mesh incl. clouds, then the atmosphere shell over it).
        for (RenderedLayer layer : surfaceLayers) {
            if (layer.model().radius < surfaceRadius * 0.98) {
                continue;
            }
            drawShadedLayer(g, layer, light, cx, cy, scaleFactor);
        }
        for (RenderedLayer layer : cloudLayers) {
            if (layer.model().radius < surfaceRadius * 0.98) {
                continue; // inside the planet: the game's depth buffer hides it
            }
            drawShadedLayer(g, layer, light, cx, cy, scaleFactor);
        }

        // world-unit inner radius: the terrain silhouette (noise-displaced corners bulge past
        // the grid radius); the game adds atmosphereRadOut absolutely to planet.radius.
        drawAtmosphere(g, cx, cy, maxExtent, scaleFactor, light);

        // occupied sector outlines for orientation; the game's planet-select view draws no grid
        if (grid != null && !occupied.isEmpty()) {
            double outlineScale = maxExtent * scaleFactor * 1.04;
            g.setStroke(Color.web("#b47df0", 0.8));
            g.setLineWidth(1.1);
            for (Integer index : occupied.keySet()) {
                if (index < grid.tiles.length) {
                    strokeTileOutline(g, grid.tiles[index], cx, cy, outlineScale, 1.2);
                }
            }
        }

        appearanceInfo.setText(Messages.t("cosmos.preview.appearance.info",
            meshTypeInfo.isEmpty() ? "SerpuloTerrain" : meshTypeInfo));
    }

    /**
     * The ray-marched atmosphere of assets/shaders/atmosphere.frag, ported per pixel and cached
     * (it only changes with the record or the light): single Rayleigh + Mie scattering with the
     * shader's exact constants (PEAK 0.1, FLARE 0.0025, INTENSITY 14.3, G_M -0.85), density
     * exp(-(r - inner) * 4 / (outer - inner)), 3 out / 3 in march steps. Drawn additively, which
     * is what the game's Blending.additive does. Radii add like the game: planet radius (here the
     * terrain silhouette) + atmosphereRadOut.
     */
    private void drawAtmosphere(javafx.scene.canvas.GraphicsContext g, double cx, double cy,
        double silhouette, double scale, double[] light) {
        if (atmosphere == null || atmosphereRad <= 0) {
            return;
        }
        if (atmosphereImage == null) {
            final double PEAK = 0.1;
            final double FLARE = 0.0025;
            final double INTENSITY = 14.3;
            final double G_M = -0.85;
            double inner = silhouette;
            double outer = silhouette + atmosphereRad;
            double eyeZ = outer * 4; // camera distance; the game uses a perspective cam offset
            int size = (int) Math.ceil(outer * scale) * 2 + 2;
            javafx.scene.image.WritableImage image = new javafx.scene.image.WritableImage(size, size);
            var writer = image.getPixelWriter();
            double[] uColor = {atmosphere.r(), atmosphere.g(), atmosphere.b()};
            double[] l = light;
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    double sx = (px - size / 2.0 + 0.5) / scale;
                    double sy = (size / 2.0 + 0.5 - py) / scale;
                    double[] dir = normalize(sx, sy, -eyeZ);
                    double[] e = rayIntersection(0, 0, eyeZ, dir, outer);
                    double[] f = rayIntersection(0, 0, eyeZ, dir, inner);
                    double tEnd = Math.min(e[1], f[0]);
                    if (e[1] <= e[0] || tEnd <= e[0]) {
                        writer.setColor(px, py, Color.BLACK);
                        continue;
                    }
                    // inScatter(eye, dir, e, l) with numOutScatter = numInScatter = 3.
                    // sum is per-channel: extinction exp(-n * (PEAK*u_color[c] + FLARE)).
                    double len = (tEnd - e[0]) / 3.0;
                    double[] step = {dir[0] * len, dir[1] * len, dir[2] * len};
                    double[] p = {dir[0] * e[0], dir[1] * e[0], eyeZ + dir[2] * e[0]};
                    double[] v = {p[0] + step[0] * 0.5, p[1] + step[1] * 0.5, p[2] + step[2] * 0.5};
                    double[] sum = new double[3];
                    for (int i = 0; i < 3; i++) {
                        double[] fv = rayIntersection(v[0], v[1], v[2], l, outer);
                        double[] u = {v[0] + l[0] * fv[1], v[1] + l[1] * fv[1], v[2] + l[2] * fv[1]};
                        double n = (optic(p, v, inner, outer) + optic(v, u, inner, outer))
                            * (Math.PI * 4.0);
                        double dens = Math.exp(-(length(v) - inner) * (4.0 / (outer - inner)));
                        for (int ch = 0; ch < 3; ch++) {
                            sum[ch] += dens * Math.exp(-n * (PEAK * uColor[ch] + FLARE));
                        }
                        v[0] += step[0];
                        v[1] += step[1];
                        v[2] += step[2];
                    }
                    for (int ch = 0; ch < 3; ch++) {
                        sum[ch] *= len / (outer - inner);
                    }
                    double c = -(dir[0] * l[0] + dir[1] * l[1] + dir[2] * l[2]);
                    double cc = c * c;
                    double rayleigh = 0.75 * (1.0 + cc);
                    double gg = G_M * G_M;
                    double mieA = (1.0 - gg) * (1.0 + cc);
                    double mieB = 1.0 + gg - 2.0 * G_M * c;
                    mieB *= Math.sqrt(mieB);
                    mieB *= 2.0 + gg;
                    double mie = 1.5 * mieA / mieB;
                    writer.setColor(px, py, Color.color(
                        Math.min(1, Math.max(0, sum[0] * (PEAK * uColor[0] * rayleigh + FLARE * mie) * INTENSITY)),
                        Math.min(1, Math.max(0, sum[1] * (PEAK * uColor[1] * rayleigh + FLARE * mie) * INTENSITY)),
                        Math.min(1, Math.max(0, sum[2] * (PEAK * uColor[2] * rayleigh + FLARE * mie) * INTENSITY))));
                }
            }
            atmosphereImage = image;
        }
        g.setGlobalBlendMode(javafx.scene.effect.BlendMode.ADD);
        g.drawImage(atmosphereImage, cx - atmosphereImage.getWidth() / 2.0,
            cy - atmosphereImage.getHeight() / 2.0);
        g.setGlobalBlendMode(javafx.scene.effect.BlendMode.SRC_OVER);
    }

    /** Ray-sphere intersection from point p along dir: {near, far} (ShaderLib rayIntersection). */
    static double[] rayIntersection(double px, double py, double pz,
        double[] dir, double radius) {
        double b = px * dir[0] + py * dir[1] + pz * dir[2];
        double c = px * px + py * py + pz * pz - radius * radius;
        double d = b * b - c;
        if (d < 0) {
            return new double[] {10000, -10000};
        }
        d = Math.sqrt(d);
        return new double[] {-b - d, -b + d};
    }

    /** optic(p, q) from atmosphere.frag: 3-step optical-depth integral between two points. */
    static double optic(double[] p, double[] q, double inner, double outer) {
        double[] step = {(q[0] - p[0]) / 3.0, (q[1] - p[1]) / 3.0, (q[2] - p[2]) / 3.0};
        double[] v = {p[0] + step[0] * 0.5, p[1] + step[1] * 0.5, p[2] + step[2] * 0.5};
        double sum = 0;
        for (int i = 0; i < 3; i++) {
            sum += Math.exp(-(length(v) - inner) * (4.0 / (outer - inner)));
            v[0] += step[0];
            v[1] += step[1];
            v[2] += step[2];
        }
        sum *= length(step) * (1.0 / (outer - inner));
        return sum;
    }

    static double length(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    /**
     * Light direction from the system schematic: the sun sits at the centre and bodies sit on
     * deterministic angles (drawOrbit), so the direction from the selected planet to the sun is
     * fully determined. Mirrors Shaders.planet.preRender's normalize(sun - planet).
     */
    private double[] lightFromSystem() {
        int index = 0;
        for (PlanetOrbits.Body body : bodies) {
            if (body.star()) {
                index++;
                continue;
            }
            double angle = 0.6 + index * Math.PI * 2 / 5;
            index++;
            if (body.selected()) {
                return normalize(-Math.cos(angle), -Math.sin(angle), 0.0);
            }
        }
        return normalize(0.75, 0.35, 0.55);
    }

    /**
     * Draws one mesh layer through the game's exact shading chain:
     * <ul>
     *   <li>planet.vert (surface): norc = (ambient + specular) * (0.01 + halfLambert), where
     *       specular = pow(dot(view, reflect(N, L)), 40) * albedo while positive, albedo =
     *       1 - colour.a, and the final colour is albedo.rgb * norc (alpha forced to 1);</li>
     *   <li>clouds.vert (HexSkyMesh): v_col = a_color * (ambient * (0.01 + halfLambert), alpha),
     *       no specular, and the shell rotates around Y by relRot = time * speed / 40;</li>
     *   <li>Shaders.unlit (SunMesh): raw albedo, no lighting at all.</li>
     * </ul>
     * Tiles get no outlines - adjacent same-colour tiles must merge into continuous terrain. */
    private void drawShadedLayer(javafx.scene.canvas.GraphicsContext g,
        RenderedLayer rendered, double[] light, double cx, double cy, double scale) {
        PlanetMeshes.MeshModel layer = rendered.model();
        PlanetMeshes.Layer source = rendered.source();
        // HexSkyMesh.relRot: the cloud shell spins independently of the planet's drag rotation.
        double cloudYaw = source.sky() && source.cloudSpeed() != 0
            ? cloudTime * source.cloudSpeed() / 40.0 : 0;
        for (int i = 0; i < layer.tileCorners.size(); i++) {
            if (layer.tileSkipped.get(i)) {
                continue;
            }
            double[] corners = layer.tileCorners.get(i);
            int points = corners.length / 3;
            double[] xs = new double[points];
            double[] ys = new double[points];
            double centroidZ = 0;
            for (int k = 0; k < points; k++) {
                double x = corners[k * 3];
                double y = corners[k * 3 + 1];
                double z = corners[k * 3 + 2];
                if (cloudYaw != 0) {
                    double cos = Math.cos(cloudYaw);
                    double sin = Math.sin(cloudYaw);
                    double rx = x * cos + z * sin;
                    z = -x * sin + z * cos;
                    x = rx;
                }
                double[] p = rotate(x, y, z);
                xs[k] = cx + p[0] * scale;
                ys[k] = cy - p[1] * scale;
                centroidZ += p[2];
            }
            if (centroidZ / points <= 0) {
                continue; // back face
            }
            PlanetMeshes.Rgb color = layer.tileColors.get(i);
            if (color == null || color.a() <= 0.01) {
                continue;
            }
            var fill = switch (shadeKindOf(source)) {
                case SUN -> color(color.r(), color.g(), color.b(), 1); // Shaders.unlit
                case CLOUD -> {
                    // clouds.vert: v_col = a_color * (ambient * (0.01 + halfLambert), alpha)
                    double halfLambert = Math.max(0, Math.min(1, (lambert(corners, light) + 1) / 2.0));
                    double norc = 0.01 + halfLambert;
                    yield color(color.r() * norc, color.g() * norc, color.b() * norc,
                        color.a());
                }
                case SURFACE -> {
                    // planet.vert: specular = pow(dot(view, reflect(N, L)), 40) * albedo
                    double[] normal = faceNormal(corners, light);
                    double lambert = normal[0] * light[0] + normal[1] * light[1] + normal[2] * light[2];
                    double diffuse = Math.max(0, Math.min(1, (lambert + 1) / 2.0));
                    double albedo = 1 - color.a();
                    // reflect(I = normal, N = light) = normal - 2 * dot(light, normal) * light
                    double rx = normal[0] - 2 * lambert * light[0];
                    double ry = normal[1] - 2 * lambert * light[1];
                    double rz = normal[2] - 2 * lambert * light[2];
                    // orthographic preview: the view direction is (0, 0, 1)
                    double specularFactor = rz;
                    double specular = specularFactor > 0
                        ? Math.pow(specularFactor, 40.0) * albedo : 0;
                    double norc = (1 + specular) * (0.01 + diffuse);
                    yield color(color.r() * norc, color.g() * norc, color.b() * norc, 1);
                }
            };
            g.setFill(fill);
            g.fillPolygon(xs, ys, points);
            // Seal the anti-aliasing hairline between neighbouring tiles with the tile's own
            // colour: the game renders a continuous mesh, visible seams would read as a mosaic.
            g.setStroke(fill);
            g.setLineWidth(1);
            g.strokePolygon(xs, ys, points);
        }
    }

    enum ShadeKind { SUN, CLOUD, SURFACE }

    static ShadeKind shadeKindOf(PlanetMeshes.Layer source) {
        return source.unlit() ? ShadeKind.SUN : source.sky() ? ShadeKind.CLOUD : ShadeKind.SURFACE;
    }

    /** Outward face normal of a tile, from its displaced corners (MeshBuilder.normal). */
    private double[] faceNormal(double[] corners, double[] light) {
        int points = corners.length / 3;
        double[] p0 = rotate(corners[0], corners[1], corners[2]);
        double[] p1 = rotate(corners[3], corners[4], corners[5]);
        double[] p2 = rotate(corners[6], corners[7], corners[8]);
        double ax = p1[0] - p0[0], ay = p1[1] - p0[1], az = p1[2] - p0[2];
        double bx = p2[0] - p0[0], by = p2[1] - p0[1], bz = p2[2] - p0[2];
        double nx = ay * bz - az * by;
        double ny = az * bx - ax * bz;
        double nz = ax * by - ay * bx;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-9) {
            return new double[] {0, 0, 1};
        }
        nx /= len;
        ny /= len;
        nz /= len;
        // outward: the normal must point away from the planet centre (0,0,0)
        if (nx * p0[0] + ny * p0[1] + nz * p0[2] < 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        return new double[] {nx, ny, nz};
    }

    /** Strokes one grid tile's outline when it faces the camera. */
    private void strokeTileOutline(javafx.scene.canvas.GraphicsContext g, PlanetGrids.Tile tile,
        double cx, double cy, double scale, double front) {
        double[] normal = rotate(tile.v.x, tile.v.y, tile.v.z);
        if (normal[2] <= 0) {
            return;
        }
        double[] xs = new double[tile.corners.length];
        double[] ys = new double[tile.corners.length];
        for (int k = 0; k < tile.corners.length; k++) {
            PlanetGrids.Corner corner = tile.corners[k];
            double[] p = rotate(corner.v.x, corner.v.y, corner.v.z);
            xs[k] = cx + p[0] * scale;
            ys[k] = cy - p[1] * scale;
        }
        g.strokePolygon(xs, ys, xs.length);
    }

    private void drawStars(javafx.scene.canvas.GraphicsContext g) {
        g.setFill(Color.WHITE);
        for (double[] star : stars) {
            double x = star[0] * g.getCanvas().getWidth();
            double y = star[1] * g.getCanvas().getHeight();
            g.setGlobalAlpha(star[2] * 0.8);
            g.fillOval(x, y, 1.4, 1.4);
        }
        g.setGlobalAlpha(1);
    }

    private void drawOrbit() {
        var g = orbitCanvas.getGraphicsContext2D();
        paintBackground(g);
        if (bodies.isEmpty()) {
            return;
        }
        double maxOrbit = 1;
        for (PlanetOrbits.Body body : bodies) {
            maxOrbit = Math.max(maxOrbit, body.orbitRadius());
        }
        double scale = Math.min(orbitCanvas.getWidth(), orbitCanvas.getHeight()) * 0.42 / maxOrbit;
        double cx = orbitCanvas.getWidth() / 2;
        double cy = orbitCanvas.getHeight() / 2;

        // orbit rings
        g.setStroke(Color.web("#343a41"));
        g.setLineWidth(1);
        g.setLineDashes(4, 4);
        for (PlanetOrbits.Body body : bodies) {
            if (body.orbitRadius() > 0) {
                double r = body.orbitRadius() * scale;
                g.strokeOval(cx - r, cy - r, r * 2, r * 2);
            }
        }
        g.setLineDashes();

        // bodies on deterministic angles
        int index = 0;
        for (PlanetOrbits.Body body : bodies) {
            if (body.star()) {
                double r = Math.max(8, body.radius() / maxOrbit * scale);
                g.setFill(Color.web(PlanetOrbits.SUN_COLORS[2]));
                g.fillOval(cx - r, cy - r, r * 2, r * 2);
                label(g, body.name(), cx, cy - r - 6, body.selected());
                index++;
                continue;
            }
            double angle = 0.6 + index * Math.PI * 2 / 5;
            index++;
            double px = cx + Math.cos(angle) * body.orbitRadius() * scale;
            double py = cy - Math.sin(angle) * body.orbitRadius() * scale;
            double dot = Math.max(4, Math.min(10, body.radius() / maxOrbit * scale * 3));
            Color fill = body.color() != null
                ? Color.web(body.color())
                : (body.selected() ? Color.web("#b47df0") : Color.web("#5f666e"));
            g.setFill(fill);
            g.fillOval(px - dot, py - dot, dot * 2, dot * 2);
            if (body.selected()) {
                g.setStroke(Color.WHITE);
                g.setLineWidth(1.5);
                g.strokeOval(px - dot - 3, py - dot - 3, dot * 2 + 6, dot * 2 + 6);
            }
            label(g, body.name() + (body.vanilla() ? " (" + Messages.t("cosmos.vanilla") + ")" : ""),
                px, py - dot - 5, body.selected());
        }
        systemInfo.setText(Messages.t("cosmos.preview.system.info", bodies.size()));
    }

    private void label(javafx.scene.canvas.GraphicsContext g, String text, double x, double y, boolean emphasized) {
        g.setFill(emphasized ? Color.WHITE : Color.web("#9aa1a9"));
        g.setFont(Font.font("System", emphasized ? FontWeight.BOLD : FontWeight.NORMAL, 11));
        g.fillText(text, x - 18, y);
    }

    // --- small helpers --------------------------------------------------------------------------

    private void paintBackground(javafx.scene.canvas.GraphicsContext g) {
        g.clearRect(0, 0, g.getCanvas().getWidth(), g.getCanvas().getHeight());
        g.setFill(Color.web("#0e1013"));
        g.fillRect(0, 0, g.getCanvas().getWidth(), g.getCanvas().getHeight());
    }

    private double[] rotate(double x, double y, double z) {
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double x1 = x * cosYaw + z * sinYaw;
        double z1 = -x * sinYaw + z * cosYaw;
        double y2 = y * cosPitch - z1 * sinPitch;
        double z2 = y * sinPitch + z1 * cosPitch;
        return new double[] {x1, y2, z2};
    }

    private double lambert(double[] corners, double[] light) {
        int points = corners.length / 3;
        // face normal from the first three corners (rotated space), flipped outwards - the game
        // uses outward vertex normals and its lighting depends on the sign of the dot product
        double[] p0 = rotate(corners[0], corners[1], corners[2]);
        double[] p1 = rotate(corners[3], corners[4], corners[5]);
        double[] p2 = rotate(corners[6], corners[7], corners[8]);
        double ax = p1[0] - p0[0], ay = p1[1] - p0[1], az = p1[2] - p0[2];
        double bx = p2[0] - p0[0], by = p2[1] - p0[1], bz = p2[2] - p0[2];
        double nx = ay * bz - az * by;
        double ny = az * bx - ax * bz;
        double nz = ax * by - ay * bx;
        double mx = (p0[0] + p1[0] + p2[0]) / 3;
        double my = (p0[1] + p1[1] + p2[1]) / 3;
        double mz = (p0[2] + p1[2] + p2[2]) / 3;
        if (nx * mx + ny * my + nz * mz < 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-9) {
            return 0.5;
        }
        return (nx * light[0] + ny * light[1] + nz * light[2]) / length;
    }

    private double[] normalize(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / length, y / length, z / length};
    }

    private double maxLayerLength(PlanetMeshes.MeshModel layer) {
        double max = 1;
        for (double[] corners : layer.tileCorners) {
            for (int k = 0; k < corners.length; k += 3) {
                double length = Math.sqrt(corners[k] * corners[k] + corners[k + 1] * corners[k + 1]
                    + corners[k + 2] * corners[k + 2]);
                max = Math.max(max, length);
            }
        }
        return max;
    }

    private static Color color(double r, double g, double b, double a) {
        return Color.color(Math.max(0, Math.min(1, r)), Math.max(0, Math.min(1, g)),
            Math.max(0, Math.min(1, b)), Math.max(0, Math.min(1, a)));
    }

    private String barePlanetName(String target) {
        String internal = project == null ? "" : project.internalName();
        return target.startsWith(internal + "-") ? target.substring(internal.length() + 1) : target;
    }

    private ContentRecord planetByName(String bare) {
        if (project == null) {
            return null;
        }
        for (ContentRecord record : project.contents) {
            if (record.type == dev.modmaker.core.fmt.ContentType.planet && !record.isInline()
                && (record.name.equals(bare)
                    || record.fullName(project.internalName()).equals(bare))) {
                return record;
            }
        }
        return null;
    }

    private float radiusOf(ContentRecord planet) {
        var element = planet.fields.get("radius");
        if (element != null && element.isJsonPrimitive()) {
            try {
                return element.getAsFloat();
            } catch (NumberFormatException ignored) {
                return 1f;
            }
        }
        return 1f;
    }
}

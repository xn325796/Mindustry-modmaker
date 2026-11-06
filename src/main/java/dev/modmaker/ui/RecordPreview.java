package dev.modmaker.ui;

import com.google.gson.JsonElement;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.SpriteRegions;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * The single preview surface for the selected record, in the context panel's 预览 tab. It composes
 * the same sprite layers the game draws:
 *
 * <ul>
 *   <li>items/statuses: the atlas icon ({@code <type>-<name>-ui} → {@code <name>} fallbacks);</li>
 *   <li>blocks: body → {@code -team} tinted with the sharded team colour (BuildingComp.draw) →
 *       {@code -top} overlay (Block.drawTop), including floor variants and duct tops;</li>
 *   <li>units: {@code -outline} under the body region (UnitType.java:1160-1183);</li>
 *   <li>liquids: the game has no liquid atlas icon, so the preview shows the liquid colour swatch;</li>
 *   <li>anything else: a colour card.</li>
 * </ul>
 *
 * Sprite paths come from the project's assets via {@link SpriteStore}, so this is the real texture,
 * not a colour approximation.
 */
final class RecordPreview extends VBox {

    private static final double WIDTH = 300;
    private static final double HEIGHT = 200;
    /** Team.sharded.color = Pal.accent-ish; game previews use the default team. */
    private static final Color TEAM = Color.web("#ffd37f");

    private final ProjectController controller;
    private final SpriteStore sprites;
    private final Label name = new Label();
    private final Label id = new Label();
    private final Canvas canvas = new Canvas(WIDTH, HEIGHT);
    private final VBox stats = new VBox();
    /** The Lospec strip: the previewed sprite's top colours, one swatch each (report P2-11). */
    private final HBox paletteBar = new HBox(4);
    private final Label paletteHint = new Label();
    /** The image the current swatches were counted from; reuses the list across keystroke redraws. */
    private Image paletteSource;
    private List<String> paletteColors = List.of();
    private String lastPaletteCopy;
    private List<String> lastLayers = List.of();

    RecordPreview(ProjectController controller) {
        this.controller = controller;
        this.sprites = new SpriteStore(controller);
        getStyleClass().add("record-preview");
        setPadding(new Insets(18));
        setSpacing(12);

        name.getStyleClass().add("preview-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        id.getStyleClass().add("preview-id");
        id.setTextOverrun(OverrunStyle.ELLIPSIS);

        StackPane stage = new StackPane(canvas);
        stage.getStyleClass().add("preview-stage");
        stage.setPrefSize(WIDTH, HEIGHT);
        stage.setMinSize(0, HEIGHT);
        stage.setMaxSize(Double.MAX_VALUE, HEIGHT);

        stats.getStyleClass().add("stat-list");

        paletteBar.getStyleClass().add("sprite-palette");
        paletteBar.setAlignment(Pos.CENTER_LEFT);
        paletteBar.setVisible(false);
        paletteBar.setManaged(false);
        paletteHint.getStyleClass().addAll("muted", "palette-hint");
        paletteHint.setVisible(false);
        paletteHint.setManaged(false);

        getChildren().addAll(stage, paletteBar, paletteHint, new VBox(2, name, id), stats);

        controller.onProjectChanged(() -> {
            sprites.invalidate();
            show(controller.selected());
        });
    }

    void show(ContentRecord record) {
        if (record == null) {
            name.setText(Messages.t("inspector.noSelection"));
            id.setText("");
            stats.getChildren().clear();
            lastLayers = List.of();
            hidePalette();
            canvas.getGraphicsContext2D().clearRect(0, 0, WIDTH, HEIGHT);
            return;
        }
        name.setText(controller.displayName(record));
        id.setText(record.name);
        stats.getChildren().setAll(
            statRow(Messages.t("assets.col.type"), controller.typeName(record.type)),
            statRow(Messages.t("inspector.class"), Labels.clazz(controller.classNameOf(record))),
            statRow(Messages.t("field.name"), record.name),
            statRow(Messages.t("assets.col.file"), sourceLabel(record)));
        render(record);
    }

    /** Region names drawn for the last record (test hook, in draw order). */
    List<String> lastLayers() {
        return lastLayers;
    }

    /** The palette strip colours currently shown, most-used first (test hook). */
    List<String> paletteColors() {
        return paletteColors;
    }

    /** Swatches on screen (test hook). */
    int paletteSwatchCount() {
        return paletteBar.getChildren().size();
    }

    /** The swatch node at a strip index, in rank order (test hook, for synthetic clicks). */
    javafx.scene.Node paletteSwatchAt(int index) {
        return paletteBar.getChildren().get(index);
    }

    /** The hex the last swatch click copied, or null (test hook: the clipboard itself may be busy). */
    String lastPaletteCopy() {
        return lastPaletteCopy;
    }

    // --- rendering ------------------------------------------------------------------------------

    private void render(ContentRecord record) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setImageSmoothing(false);
        gc.clearRect(0, 0, WIDTH, HEIGHT);
        ModProject project = controller.project();
        String internal = project == null ? "" : project.internalName();
        List<Layer> layers = layers(record, internal);
        lastLayers = layers.stream().map(Layer::region).toList();

        Image base = null;
        for (Layer layer : layers) {
            Image image = sprites.image(layer.region());
            if (image != null && (base == null || image.getWidth() * image.getHeight()
                > base.getWidth() * base.getHeight())) {
                base = image;
            }
        }
        if (base == null) {
            hidePalette();
            if (record.type == ContentType.liquid) {
                drawLiquid(gc, record);
            } else {
                drawFallback(gc, record);
            }
            return;
        }
        rebuildPalette(base);

        // Pixel art: nearest-neighbour filtering at the game's readable 2x scale (a 32px block
        // becomes 64px), so the preview matches the in-game feel. Only sprites that would overflow
        // the 300x200 stage at 2x fall back to a fit scale (still nearest-neighbour).
        double fit = Math.min(WIDTH / base.getWidth(), HEIGHT / base.getHeight());
        double scale = Math.min(2.0, fit);
        double spriteW = base.getWidth() * scale;
        double spriteH = base.getHeight() * scale;
        if (record.type == ContentType.block) {
            drawTileGrid(gc, scale, spriteW, spriteH);
        }
        double cx = WIDTH / 2;
        double cy = HEIGHT / 2;
        for (Layer layer : layers) {
            Image image = layer.team() ? sprites.tinted(layer.region(), TEAM) : sprites.image(layer.region());
            if (image == null) {
                continue;
            }
            double w = image.getWidth() * scale;
            double h = image.getHeight() * scale;
            gc.drawImage(image, cx - w / 2, cy - h / 2, w, h);
        }
    }

    /**
     * The layer stack, mirroring the game's draw order: body, team overlay, top overlay. Names are
     * resolved through the project's atlas regions (Mods.packSprites naming); a missing layer is
     * simply skipped.
     */
    private List<Layer> layers(ContentRecord record, String internal) {
        // Patch records are registered under the vanilla name they override, and their override
        // sprites live in sprites-override/ unprefixed. Other records must keep using the sprite
        // naming rule: a name like "block-<mod>-x" is already category-prefixed and must not get
        // another mod prefix.
        String base = record.patch ? record.name : SpriteRegions.expectedRegion(record, internal);
        List<Layer> out = new ArrayList<>();
        switch (record.type) {
            case item, status -> {
                String type = record.type.name();
                String icon = sprites.any(type + "-" + base + "-ui",
                    type + "-" + base + "-full", base + "-full", base, type + "-" + base,
                    base + "1");
                if (icon != null) {
                    out.add(new Layer(icon, false));
                }
            }
            case block -> {
                String body = sprites.body(base);
                if (body != null) {
                    out.add(new Layer(body, false));
                }
                String team = sprites.any(base + "-team");
                if (team != null) {
                    out.add(new Layer(team, true));
                }
                String top = sprites.any(base + "-top", base + "-top-0", base + "-top-1",
                    base + "-top-2", base + "-top-3", base + "-top-4");
                if (top != null) {
                    out.add(new Layer(top, false));
                }
            }
            case unit -> {
                // UnitType.java:1183 — the outline is its own region and sits behind the body.
                String outline = sprites.any(base + "-outline");
                if (outline != null) {
                    out.add(new Layer(outline, false));
                }
                String body = sprites.any(base + "-preview", base);
                if (body != null) {
                    out.add(new Layer(body, false));
                }
            }
            case liquid -> {
                // Most liquids have no sprite (the game draws a colour); a mod may ship one.
                String icon = sprites.any("liquid-" + base + "-ui", "liquid-" + base + "-full",
                    base + "-full", base, "liquid-" + base);
                if (icon != null) {
                    out.add(new Layer(icon, false));
                }
            }
            default -> {
                // Planets/sectors/weathers/teams have no single atlas sprite.
            }
        }
        return out;
    }

    // --- palette strip ---------------------------------------------------------------------------

    /**
     * The Lospec strip under the preview: the sprite's top colours as clickable swatches. Counting
     * is cached per image because every keystroke in the inspector re-renders the preview, and the
     * SpriteStore hands out the same decoded image until the project changes.
     */
    private void rebuildPalette(Image base) {
        if (base != paletteSource) {
            paletteSource = base;
            paletteColors = SpritePalette.topColors(SpritePalette.pixels(base), SpritePalette.LIMIT);
            // A stale "copied" hint from the previous sprite would misreport the new one.
            paletteHint.setVisible(false);
            paletteHint.setManaged(false);
        }
        paletteBar.getChildren().clear();
        for (String hex : paletteColors) {
            Region swatch = new Region();
            swatch.getStyleClass().add("palette-swatch");
            swatch.setStyle("-fx-background-color: " + hex + ";");
            javafx.scene.control.Tooltip.install(swatch, new javafx.scene.control.Tooltip(hex));
            swatch.setOnMouseClicked(event -> copyColor(hex));
            paletteBar.getChildren().add(swatch);
        }
        boolean visible = !paletteColors.isEmpty();
        paletteBar.setVisible(visible);
        paletteBar.setManaged(visible);
    }

    /** Lospec's click-to-pick, adapted to an editor: the hex lands on the clipboard with a hint. */
    private void copyColor(String hex) {
        lastPaletteCopy = hex;
        try {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(hex);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        } catch (RuntimeException failure) {
            // A locked clipboard must not swallow the visible feedback below.
        }
        paletteHint.setText(Messages.t("preview.palette.copied", hex));
        paletteHint.setVisible(true);
        paletteHint.setManaged(true);
        controller.log("palette colour copied: " + hex);
    }

    private void hidePalette() {
        paletteBar.getChildren().clear();
        paletteBar.setVisible(false);
        paletteBar.setManaged(false);
        paletteHint.setVisible(false);
        paletteHint.setManaged(false);
        paletteHint.setText("");
        paletteSource = null;
        paletteColors = List.of();
    }

    private static void drawTileGrid(GraphicsContext gc, double scale, double spriteW,
        double spriteH) {
        double cell = 32 * scale;
        double left = WIDTH / 2 - spriteW / 2;
        double top = HEIGHT / 2 - spriteH / 2;
        gc.setStroke(Color.web("#FFFFFF", 0.045));
        gc.setLineWidth(1);
        // Tile boundaries of the sprite itself (Floor/Block sizes are multiples of 32px).
        for (double x = left; x <= left + spriteW + 0.5; x += cell) {
            gc.strokeLine(x, top, x, top + spriteH);
        }
        for (double y = top; y <= top + spriteH + 0.5; y += cell) {
            gc.strokeLine(left, y, left + spriteW, y);
        }
    }

    private void drawFallback(GraphicsContext gc, ContentRecord record) {
        Color color = parseColor(record.type.color(), Color.web("#5B6EE1"));
        gc.setFill(color.deriveColor(0, 1, 1, 0.28));
        gc.fillRoundRect(WIDTH / 2 - 70, HEIGHT / 2 - 70, 140, 140, 10, 10);
        gc.setStroke(color.deriveColor(0, 1, 1, 0.7));
        gc.strokeRoundRect(WIDTH / 2 - 70, HEIGHT / 2 - 70, 140, 140, 10, 10);
        gc.setFill(Color.web("#FFFFFF", 0.55));
        gc.fillText(Messages.t("inspector.preview.noSprite"), WIDTH / 2 - 52, HEIGHT / 2 + 4);
    }

    private void drawLiquid(GraphicsContext gc, ContentRecord record) {
        gc.setImageSmoothing(false);
        Color color = parseColor(field(record, "color"), Color.web("#5B6EE1"));
        double size = 150;
        double x = WIDTH / 2 - size / 2;
        double y = HEIGHT / 2 - size / 2;
        gc.setFill(color);
        gc.fillRoundRect(x, y, size, size, 12, 12);
        gc.setFill(color.brighter().deriveColor(0, 1, 1, 0.55));
        gc.fillRoundRect(x + 4, y + 4, size - 8, size * 0.35, 10, 10);
        gc.setStroke(color.darker());
        gc.setLineWidth(1.5);
        gc.strokeRoundRect(x, y, size, size, 12, 12);
    }

    private static String field(ContentRecord record, String key) {
        JsonElement value = record.fields.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static Color parseColor(String value, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Color.web(value.startsWith("#") ? value : "#" + value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private VBox statRow(String label, String value) {
        Label key = new Label(label);
        key.getStyleClass().add("stat-key");
        Label val = new Label(value == null || value.isBlank() ? "-" : value);
        val.getStyleClass().add("stat-value");
        val.setTextOverrun(OverrunStyle.ELLIPSIS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, key, spacer, val);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("stat-row");
        VBox box = new VBox(6, row);
        box.getStyleClass().add("stat");
        return box;
    }

    private String sourceLabel(ContentRecord record) {
        return record.rawPath == null ? Messages.t("inspector.newFile") : record.rawPath;
    }

    private record Layer(String region, boolean team) {
    }
}

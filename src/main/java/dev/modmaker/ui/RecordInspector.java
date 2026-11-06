package dev.modmaker.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.fmt.PlanetMeshes;
import dev.modmaker.core.json.Json;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.schema.FieldDef;
import dev.modmaker.core.schema.FieldType;
import dev.modmaker.core.validate.Issue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Schema-driven editor for the selected record: identity, localised strings, then one editor per
 * field the schema knows, and a raw JSON area for whatever it does not.
 *
 * <p>Edits go through the controller, which marks the record dirty so the file is re-serialised on
 * save; untouched records keep their original bytes.
 */
public final class RecordInspector extends BorderPane {

    private final ProjectController controller;
    private final VBox content = new VBox(12);
    private final ScrollPane scroll = new ScrollPane(content);
    private final Label headTitle = new Label();
    private final Label headSub = new Label();
    /** The raw JSON dump, kept so self-edits can refresh just it instead of rebuilding all rows. */
    private TextArea rawArea;
    /**
     * Set while this inspector itself is committing an edit: the resulting project change must not
     * rebuild the whole panel (that would drop focus mid-keystroke), only the raw JSON view.
     */
    private boolean selfEdit;
    private ContentRecord selfEditRecord;
    /** The single research-field editor, reused across rebuilds (P3). */
    private final ResearchEditor researchEditor;
    /** Opens the single string editor (the context panel's 文本 tab), wired by AppShell. */
    private Runnable openStrings = () -> { };

    public RecordInspector(ProjectController controller) {
        this.controller = controller;
        this.researchEditor = new ResearchEditor(controller);
        setPrefWidth(480);

        // Fixed head strip (prototype #right-head), then the scrolling block body (#right-body).
        headTitle.getStyleClass().add("detail-title");
        headSub.getStyleClass().add("detail-sub");
        VBox head = new VBox(4, headTitle, headSub);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("detail-head");
        setTop(head);

        content.setPadding(new Insets(18));
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("panel-scroll");
        setCenter(scroll);

        controller.onSelectionChanged(this::rebuild);
        controller.onProjectChanged(() -> {
            if (selfEdit && controller.selected() == selfEditRecord) {
                refreshRawArea();
                return;
            }
            rebuild(controller.selected());
        });
        // The report section shows this record's findings; a background pass that lands after the
        // panel was built has to reach the panel without the user re-selecting the record. Only the
        // report block is swapped, so editing focus is not thrown away by a late pass.
        controller.onValidationRefreshed(this::refreshReport);
        // Start from the record the shell may already have selected, so the panel never shows
        // "no selection" while the context header above names a record.
        rebuild(controller.selected());
    }

    /** Commits an edit that originates from this panel; the panel stays focused and responsive. */
    private void commitField(ContentRecord record, String field, JsonElement value) {
        selfEdit = true;
        selfEditRecord = record;
        try {
            controller.setField(record, field, value);
        } finally {
            selfEdit = false;
            selfEditRecord = null;
        }
    }

    private void refreshRawArea() {
        if (rawArea != null && controller.selected() != null) {
            rawArea.setText(Json.write(controller.selected().tree()));
        }
    }

    /**
     * Replaces just the report block with a freshly rendered one. The report is always the last child
     * of the content box; swapping it keeps the rest of the panel - and any focused editor inside it -
     * exactly where the user left it.
     */
    private void refreshReport() {
        ContentRecord record = controller.selected();
        List<javafx.scene.Node> children = content.getChildren();
        if (record == null || children.isEmpty()) {
            return;
        }
        javafx.scene.Node last = children.get(children.size() - 1);
        if (last.getStyleClass().contains("report")) {
            children.set(children.size() - 1, report(record));
        }
    }

    private void rebuild(ContentRecord record) {
        content.getChildren().clear();
        rawArea = null;
        if (record == null || controller.project() == null) {
            headTitle.setText(Messages.t("inspector.title"));
            headSub.setText(Messages.t("inspector.noSelection"));
            content.getChildren().add(muted("inspector.empty"));
            return;
        }

        headTitle.setText(controller.displayName(record));
        headSub.setText(controller.subtitleOf(record)
            + (record.isInline() ? " · inline" : "") + (record.patch ? " · patch" : ""));

        content.getChildren().addAll(
            identity(record), localisation(record), fields(record), research(record),
            unknownFields(record), report(record));
    }

    // --- sections -------------------------------------------------------------------------------

    /** The class choices currently offered by the identity section (for tests and tooling). */
    List<String> currentClassChoices() {
        return lastClassChoices == null ? List.of() : List.copyOf(lastClassChoices);
    }

    private List<String> lastClassChoices;

    private String sourceLabel(ContentRecord record) {
        if (record.isInline()) {
            return Messages.t("inspector.inlineIn", ownerPath(record), record.inlinePath);
        }
        return record.rawPath == null ? Messages.t("inspector.newFile") : record.rawPath;
    }

    /** This record's own validation findings, styled like the prototype's report entries. */
    private Region report(ContentRecord record) {
        VBox box = new VBox(10, blockTitle("inspector.report"));
        List<Issue> issues = controller.project() == null ? List.of()
            : controller.latestIssues().stream()
                .filter(issue -> record.id.equals(issue.contentId()))
                .toList();
        if (issues.isEmpty()) {
            box.getChildren().add(muted("inspector.report.ok"));
        } else {
            for (Issue issue : issues) {
                Label text = new Label(Messages.t(issue.key(), issue.args().toArray()));
                text.getStyleClass().add("report-title");
                text.setWrapText(true);
                VBox item = new VBox(3, text);
                item.getStyleClass().add("report-item");
                if (!issue.isError()) {
                    item.getStyleClass().add("warn");
                }
                box.getChildren().add(item);
            }
        }
        box.getStyleClass().addAll("block", "report");
        box.setPadding(new Insets(18));
        return box;
    }

    private Region identity(ContentRecord record) {
        VBox box = blockBox("identity");
        TextField nameField = new TextField(record.name);
        nameField.setOnAction(event -> {
            controller.rename(record, nameField.getText().trim());
            rebuild(controller.selected());
        });
        nameField.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                controller.rename(record, nameField.getText().trim());
            }
        });
        box.getChildren().add(row(Messages.t("inspector.name"), nameField));

        ComboBox<String> classBox = new ComboBox<>();
        classBox.setEditable(true);
        // Units are the exception to "type is a class": the value is a movement kind from a closed
        // set (ContentParser.unitType), so offering class names there would only break the mod.
        List<String> classes = record.type == ContentType.unit
            ? new ArrayList<>(controller.registry().unitTypes())
            : controller.adaptiveClasses()
                ? controller.registry().relatedClasses(record.type, controller.classNameOf(record))
                : controller.registry().classesForType(record.type);
        String currentClass = controller.classNameOf(record);
        if (!classes.contains(currentClass)) {
            classes = new ArrayList<>(classes);
            classes.add(0, currentClass);
        }
        lastClassChoices = classes;
        classBox.getItems().addAll(classes.isEmpty() ? List.of(currentClass) : classes);
        // Editable combo boxes render the button through the converter, not the button cell:
        // show the translated label, and accept either the label or the raw class name as input.
        classBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : Labels.clazz(value);
            }

            @Override
            public String fromString(String text) {
                if (text == null) {
                    return null;
                }
                for (String item : classBox.getItems()) {
                    if (item.equals(text) || Labels.clazz(item).equals(text)) {
                        return item;
                    }
                }
                return text;
            }
        });
        classBox.setValue(controller.classNameOf(record));
        classBox.valueProperty().addListener((observable, was, value) -> {
            if (value == null) {
                return;
            }
            String base = controller.registry().baseClassOf(record.type);
            // The game assumes the base class, so writing it out would be noise.
            controller.setField(record, "type", value.equals(base) ? null : new JsonPrimitive(value));
            rebuild(controller.selected());
        });
        box.getChildren().add(row(Messages.t("inspector.class"), classBox));

        CheckBox patch = new CheckBox(Messages.t("inspector.patch"));
        patch.setSelected(record.patch);
        patch.setDisable(record.isInline());
        patch.selectedProperty().addListener((observable, was, value) ->
            controller.setPatch(record, Boolean.TRUE.equals(value)));
        box.getChildren().add(patch);

        if (record.isInline()) {
            Label note = new Label(Messages.t("inspector.inlineIn", ownerPath(record), record.inlinePath));
            note.getStyleClass().add("muted");
            note.setWrapText(true);
            box.getChildren().add(note);
        }
        return box;
    }

    /** Commits a localisation edit from this panel without rebuilding it (keeps focus). */
    private void commitString(String key, String locale, String value) {
        selfEdit = true;
        selfEditRecord = controller.selected();
        try {
            controller.setString(key, locale, value);
        } finally {
            selfEdit = false;
            selfEditRecord = null;
        }
    }

    private Region localisation(ContentRecord record) {
        VBox box = blockBox("localisation");
        if (record.isInline()) {
            box.getChildren().add(muted("inspector.notLocalised"));
            return box;
        }
        // Read-only summary: the string table (文本 tab) is the single editor for these keys now.
        for (String field : NameRules.BUNDLE_FIELDS) {
            String fieldLabel = Messages.t("field." + field);
            for (String locale : controller.project().locales) {
                String value = controller.stringValue(record, field, locale);
                Label display = new Label(value == null || value.isBlank() ? "-" : value);
                display.getStyleClass().add("stat-value");
                display.setTextOverrun(OverrunStyle.ELLIPSIS);
                box.getChildren().add(row(fieldLabel + " [" + locale + "]", display));
            }
        }
        Button open = new Button(Messages.t("inspector.strings.open"));
        open.getStyleClass().add("tool-button");
        open.setOnAction(event -> openStrings.run());
        box.getChildren().add(open);
        return box;
    }

    private Region fields(ContentRecord record) {
        VBox box = blockBox("fields");
        String className = controller.classNameOf(record);
        List<FieldDef> definitions = controller.registry().fieldsOf(className);
        if (definitions.isEmpty()) {
            box.getChildren().add(muted("inspector.noSchema"));
            return box;
        }
        // Make the adaptive behaviour visible: these are the fields of the selected class.
        Label source = new Label(Messages.t("inspector.fieldsFor", Labels.clazz(className)));
        source.getStyleClass().add("muted");
        source.setWrapText(true);
        box.getChildren().add(source);
        for (FieldDef definition : definitions) {
            if (definition.name().equals("type") || definition.name().equals("name")
                || definition.name().equals("description") || definition.name().equals("details")
                || definition.name().equals("credit")) {
                continue;
            }
            box.getChildren().add(editorRow(record, definition));
        }
        box.getChildren().add(addFieldRow(record, definitions));
        return box;
    }

    private Region research(ContentRecord record) {
        VBox box = blockBox("inspector.research");
        researchEditor.build(record);
        box.getChildren().add(researchEditor);
        return box;
    }

    /** The single research editor, so tests drive the same path as the buttons (P3). */
    ResearchEditor researchEditor() {
        return researchEditor;
    }

    void setOpenStrings(Runnable openStrings) {
        this.openStrings = openStrings;
    }

    private Region unknownFields(ContentRecord record) {
        VBox box = blockBox("inspector.raw");
        rawArea = new TextArea(Json.write(record.tree()));
        rawArea.getStyleClass().addAll("raw-area", "raw-tree");
        rawArea.setPrefRowCount(10);
        rawArea.setWrapText(false);
        // Read-only until the user asks for it: the raw area is an escape hatch, not a second
        // editor that competes with the structured fields above.
        rawArea.setEditable(false);

        Label hint = new Label(Messages.t("inspector.raw.hint"));
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        javafx.scene.control.CheckBox edit = new javafx.scene.control.CheckBox(Messages.t("inspector.raw.edit"));
        edit.getStyleClass().add("tool-check");

        javafx.scene.control.Button apply = new javafx.scene.control.Button(Messages.t("inspector.raw.apply"));
        apply.setDisable(true);
        apply.setOnAction(event -> {
            try {
                var parsed = Json.parseObject(rawArea.getText());
                controller.applyRawTree(record, parsed);
                controller.log("applied raw json to '" + record.name + "'");
                rebuild(record);
            } catch (RuntimeException failure) {
                controller.log("raw json is not valid: " + failure.getMessage());
            }
        });
        edit.selectedProperty().addListener((observable, was, now) -> {
            rawArea.setEditable(Boolean.TRUE.equals(now));
            apply.setDisable(!Boolean.TRUE.equals(now));
            hint.setText(Messages.t(Boolean.TRUE.equals(now)
                ? "inspector.raw.hint" : "inspector.raw.readonly.hint"));
        });

        box.getChildren().addAll(rawArea, hint, edit, apply);
        return box;
    }

    // --- editors --------------------------------------------------------------------------------

    private Region editorRow(ContentRecord record, FieldDef definition) {
        // Meshes get structured sub-editors: the game's parser accepts fixed types with fixed
        // fields (ContentParser.parseMesh), so free-form JSON editing is unnecessary here.
        if (definition.name().equals("mesh") || definition.name().equals("cloudMesh")) {
            return meshRow(record, definition);
        }
        JsonElement value = record.fields.get(definition.name());
        // Live edits: every committed value goes through commitField, so the panel keeps focus
        // and the canvas card / preview update as the user types.
        Consumer<JsonElement> commit = next -> commitField(record, definition.name(), next);
        Node editor = switch (definition.type()) {
            case number -> numberEditor(plain(value), commit, 90);
            case text, sprite -> textEditor(plain(value),
                input -> commit.accept(new JsonPrimitive(input)), 130);
            case textArray -> textEditor(stringArrayText(value),
                input -> commit.accept(arrayOf(input)), 150);
            case bool -> boolEditor(value, commit);
            case color -> colorEditor(value, commit);
            case enumeration -> enumEditor(definition, value, commit);
            case content -> contentEditor(definition, value, commit);
            case stack -> textEditor(stackText(value), input -> commit.accept(stackOf(input)), 130);
            case stackArray -> textEditor(stackArrayText(value),
                input -> commit.accept(stackArrayOf(input)), 150);
            // Structured groups (consumes, unit requirements, ammo maps, plans): the canvas wires
            // the references and amounts; here the whole group stays editable as raw JSON.
            case struct -> rawEditor(value, commit);
            case raw -> rawEditor(value, commit);
        };
        // The row shows the translated label; the raw JSON key stays in the tooltip next to the
        // description, because the raw key is what appears in the exported file.
        Region row = row(Labels.field(definition.name()), editor);
        String tooltip = definition.description() == null || definition.description().isBlank()
            ? definition.name()
            : definition.name() + " — " + definition.description();
        javafx.scene.control.Tooltip.install(row, new javafx.scene.control.Tooltip(tooltip));
        return row;
    }

    /**
     * Structured editor for planet "mesh" / "cloudMesh": a list of mesh layers, addable and
     * removable, because a planet in the wild rarely stops at one mesh (Exoprosopa's frostnova
     * layers two biome NoiseMeshes). The value forms the game accepts - one object, a bare array
     * (the MultiMesh shorthand) or an explicit MultiMesh object - all map into the same layer
     * list, and packing back keeps the convention "one layer = object, several = bare array".
     * Edits route through commitField so typing keeps the panel (and focus) alive; each editor
     * works on its own deep copy so setField's undo snapshots stay meaningful.
     */
    private Region meshRow(ContentRecord record, FieldDef definition) {
        String field = definition.name();
        boolean cloud = field.equals("cloudMesh");
        List<LayerSlot> slots = new ArrayList<>();
        for (JsonObject layer : PlanetMeshes.meshLayerConfigs(record.fields.get(field))) {
            slots.add(new LayerSlot(layer.deepCopy()));
        }

        VBox box = new VBox(4);
        Label title = new Label(Labels.field(field));
        title.getStyleClass().add("field-label");
        box.getChildren().add(title);

        Consumer<List<LayerSlot>> commitLayers = updated -> {
            List<JsonObject> values = new ArrayList<>();
            for (LayerSlot slot : updated) {
                values.add(slot.value);
            }
            if (values.isEmpty()) {
                commitField(record, field, null);
            } else if (values.size() == 1) {
                commitField(record, field, values.get(0).deepCopy());
            } else {
                com.google.gson.JsonArray array = new com.google.gson.JsonArray();
                values.forEach(layer -> array.add(layer.deepCopy()));
                commitField(record, field, array);
            }
        };

        Button add = new Button(Messages.t("mesh.addChild"));
        add.getStyleClass().add("tool-button");

        // Rows are rebuilt from the slot list after every structural change: a row that kept the
        // array index it was built with would write to - or index past - the wrong layer once a
        // neighbour was removed or added. The add button stays last, so new rows land above it.
        Runnable[] refreshRows = new Runnable[1];
        refreshRows[0] = () -> {
            box.getChildren().removeIf(node -> node != title && node != add);
            int insertAt = box.getChildren().indexOf(add);
            for (LayerSlot slot : slots) {
                box.getChildren().add(insertAt++, layerRow(cloud, slots, slot, commitLayers, refreshRows[0]));
            }
        };
        add.setOnAction(event -> {
            slots.add(new LayerSlot(defaultLayer(cloud)));
            refreshRows[0].run();
            commitLayers.accept(slots);
        });
        box.getChildren().add(add);
        refreshRows[0].run();
        return box;
    }

    /** One layer in a mesh list; the row handlers resolve it by identity, never by array index. */
    private static final class LayerSlot {
        private JsonObject value;

        LayerSlot(JsonObject value) {
            this.value = value;
        }
    }

    /**
     * One editable mesh layer with its remove button; mutations rewrite the whole field value. The
     * row finds its own layer through its slot, so rows below a removed layer keep pointing at the
     * right object. Removing is a normal field edit, so it undoes back to the full layer list.
     */
    private Region layerRow(boolean cloud, List<LayerSlot> slots, LayerSlot slot,
        Consumer<List<LayerSlot>> commitLayers, Runnable structuralChange) {
        MeshEditor editor = new MeshEditor(slot.value.deepCopy(),
            cloud ? "HexSkyMesh" : "NoiseMesh", false, 0,
            value -> {
                slot.value = value == null ? defaultLayer(cloud) : value.deepCopy();
                commitLayers.accept(slots);
            });
        Button remove = new Button(Messages.t("mesh.remove"));
        HBox row = new HBox(6, editor, remove);
        row.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        remove.getStyleClass().add("tool-button");
        remove.setOnAction(event -> {
            if (!slots.remove(slot)) {
                return;
            }
            // The section rebuilds itself in place: the panel-wide rebuild is suppressed while a
            // panel edit is in flight (selfEdit), so the remaining rows must be re-indexed here.
            structuralChange.run();
            commitLayers.accept(slots);
        });
        return row;
    }

    private static JsonObject defaultLayer(boolean cloud) {
        JsonObject layer = new JsonObject();
        layer.addProperty("type", cloud ? "HexSkyMesh" : "NoiseMesh");
        return layer;
    }

    private Node textEditor(String value, Consumer<String> commit, double width) {
        TextField field = new TextField(value == null ? "" : value);
        field.setPrefWidth(width);
        // live: every keystroke commits; setField's equality guard swallows no-op echoes
        field.textProperty().addListener((observable, was, now) -> commit.accept(now));
        return field;
    }

    /**
     * Number fields no longer store unparseable input as a string (the game then rejects the
     * whole file): invalid text is flagged red and simply not committed. Live: each keystroke
     * that parses commits immediately.
     */
    private Node numberEditor(String value, Consumer<JsonElement> commit, double width) {
        TextField field = new TextField(value == null ? "" : value);
        field.setPrefWidth(width);
        field.textProperty().addListener((observable, was, now) -> {
            String text = now.trim();
            if (text.isEmpty()) {
                field.getStyleClass().remove("editor-invalid");
                commit.accept(null);
                return;
            }
            JsonElement parsed = numberOf(text);
            if (parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isNumber()) {
                field.getStyleClass().remove("editor-invalid");
                commit.accept(parsed);
            } else {
                if (!field.getStyleClass().contains("editor-invalid")) {
                    field.getStyleClass().add("editor-invalid");
                }
            }
        });
        return field;
    }

    private Node boolEditor(JsonElement value, Consumer<JsonElement> commit) {
        CheckBox box = new CheckBox();
        box.setSelected(value != null && value.isJsonPrimitive() && value.getAsBoolean());
        box.selectedProperty().addListener((observable, was, now) -> commit.accept(new JsonPrimitive(now)));
        return box;
    }

    private Node colorEditor(JsonElement value, Consumer<JsonElement> commit) {
        String text = plain(value);
        ColorPicker picker = new ColorPicker();
        try {
            picker.setValue(text == null || text.isBlank() ? Color.WHITE : Color.web(text.startsWith("#") ? text : "#" + text));
        } catch (RuntimeException ignored) {
            picker.setValue(Color.WHITE);
        }
        TextField hex = new TextField(text == null ? "" : text);
        hex.setPrefWidth(80);
        picker.setOnAction(event -> {
            String formatted = String.format("#%02x%02x%02x",
                Math.round(picker.getValue().getRed() * 255),
                Math.round(picker.getValue().getGreen() * 255),
                Math.round(picker.getValue().getBlue() * 255));
            hex.getStyleClass().remove("editor-invalid");
            hex.setText(formatted); // the hex field's live listener commits it
        });
        // Live hex input: only well-formed colours are committed; anything else turns red.
        hex.textProperty().addListener((observable, was, now) -> {
            String hexText = now.trim();
            try {
                Color.web(hexText.startsWith("#") || hexText.isBlank() ? hexText : "#" + hexText);
                hex.getStyleClass().remove("editor-invalid");
                commit.accept(new JsonPrimitive(hexText));
            } catch (RuntimeException invalid) {
                // The game rejects unknown colour formats, so don't write one silently.
                if (!hex.getStyleClass().contains("editor-invalid")) {
                    hex.getStyleClass().add("editor-invalid");
                }
            }
        });
        HBox box = new HBox(6, picker, hex);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node enumEditor(FieldDef definition, JsonElement value, Consumer<JsonElement> commit) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().addAll(definition.enumValues());
        box.setEditable(true);
        box.setValue(plain(value));
        box.valueProperty().addListener((observable, was, now) -> {
            if (now != null) {
                commit.accept(new JsonPrimitive(now));
            }
        });
        return box;
    }

    private Node contentEditor(FieldDef definition, JsonElement value,
        Consumer<JsonElement> commit) {
        ComboBox<String> box = new ComboBox<>();
        box.setEditable(true);
        box.getItems().addAll(referenceCandidates(definition));
        box.setValue(plain(value));
        box.valueProperty().addListener((observable, was, now) -> {
            if (now != null) {
                commit.accept(new JsonPrimitive(now));
            }
        });
        return box;
    }

    private Node rawEditor(JsonElement value, Consumer<JsonElement> commit) {
        TextArea area = new TextArea(value == null ? "" : Json.write(value));
        area.setPrefRowCount(4);
        area.getStyleClass().add("raw-area");
        javafx.scene.control.Button applyButton = new javafx.scene.control.Button(Messages.t("inspector.set"));
        applyButton.setOnAction(event -> {
            try {
                commit.accept(area.getText().isBlank() ? null : Json.parse(area.getText()));
            } catch (RuntimeException failure) {
                controller.log("value is not valid JSON: " + failure.getMessage());
            }
        });
        VBox box = new VBox(4, area, applyButton);
        return box;
    }

    private Region addFieldRow(ContentRecord record, List<FieldDef> definitions) {
        Map<String, FieldDef> known = new LinkedHashMap<>();
        definitions.forEach(definition -> known.put(definition.name(), definition));
        TextField fieldName = new TextField();
        fieldName.setPromptText(Messages.t("inspector.addField.name"));
        fieldName.setPrefWidth(120);
        TextField fieldValue = new TextField();
        fieldValue.setPromptText(Messages.t("inspector.addField.value"));
        HBox.setHgrow(fieldValue, Priority.ALWAYS);
        javafx.scene.control.Button add = new javafx.scene.control.Button(Messages.t("inspector.add"));
        add.setOnAction(event -> {
            String name = fieldName.getText().trim();
            if (name.isEmpty()) {
                return;
            }
            String raw = fieldValue.getText();
            JsonElement parsed;
            try {
                parsed = raw.isBlank() ? new JsonPrimitive("") : Json.parse(raw);
            } catch (RuntimeException failure) {
                parsed = new JsonPrimitive(raw);
            }
            controller.setField(record, name, parsed);
            rebuild(record);
        });
        HBox box = new HBox(6, fieldName, fieldValue, add);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Reference candidates: this project's content first, then the game's own names. */
    private List<String> referenceCandidates(FieldDef definition) {
        List<String> candidates = new ArrayList<>();
        String wanted = definition.contentCtype();
        String internal = controller.project().internalName();
        for (ContentRecord record : controller.project().contents) {
            if (record.isInline() || !record.type.fileBacked()) {
                continue;
            }
            if (wanted != null && !wanted.equals("any") && !record.type.name().equals(wanted)) {
                continue;
            }
            candidates.add(record.fullName(internal));
        }
        for (ContentType type : ContentType.values()) {
            if (wanted != null && !wanted.equals("any") && !type.name().equals(wanted)) {
                continue;
            }
            controller.registry().vanillaNames(type).stream().limit(120).forEach(candidates::add);
        }
        return candidates.stream().distinct().limit(400).toList();
    }

    // --- small helpers --------------------------------------------------------------------------

    private String ownerPath(ContentRecord record) {
        ContentRecord owner = controller.project().contentById(record.inlineOwnerId);
        return owner == null ? "(missing)" : (owner.rawPath == null ? owner.name : owner.rawPath);
    }

    static String plain(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.isJsonPrimitive() ? value.getAsString() : Json.write(value);
    }

    static String stringArrayText(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return plain(value);
        }
        List<String> parts = new ArrayList<>();
        value.getAsJsonArray().forEach(element -> parts.add(plain(element)));
        return String.join(", ", parts);
    }

    static JsonElement arrayOf(String text) {
        JsonArray array = new JsonArray();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                array.add(new JsonPrimitive(trimmed));
            }
        }
        return array.isEmpty() ? null : array;
    }

    static String stackText(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        JsonElement target = value.getAsJsonObject().get("item") != null
            ? value.getAsJsonObject().get("item")
            : value.getAsJsonObject().get("liquid");
        JsonElement amount = value.getAsJsonObject().get("amount");
        return target == null ? "" : target.getAsString() + "/" + (amount == null ? "1" : amount.getAsString());
    }

    static JsonElement stackOf(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : new JsonPrimitive(trimmed);
    }

    static String stackArrayText(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return stackText(value);
        }
        List<String> parts = new ArrayList<>();
        value.getAsJsonArray().forEach(element -> parts.add(stackText(element)));
        return String.join(", ", parts);
    }

    static JsonElement stackArrayOf(String text) {
        JsonArray array = new JsonArray();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                array.add(new JsonPrimitive(trimmed));
            }
        }
        return array.isEmpty() ? null : array;
    }

    static JsonElement numberOf(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return trimmed.contains(".") ? new JsonPrimitive(Double.parseDouble(trimmed))
                : new JsonPrimitive(Long.parseLong(trimmed));
        } catch (NumberFormatException failure) {
            return new JsonPrimitive(trimmed);
        }
    }

    /** A titled 18px-padded block (prototype .block). */
    private VBox blockBox(String titleKey) {
        VBox box = new VBox(10, blockTitle(titleKey));
        box.getStyleClass().add("block");
        box.setPadding(new Insets(18));
        return box;
    }

    private Label blockTitle(String key) {
        Label label = new Label(Messages.t(key));
        label.getStyleClass().add("block-title");
        return label;
    }

    private Label muted(String key) {
        Label label = new Label(Messages.t(key));
        label.getStyleClass().add("muted");
        label.setWrapText(true);
        return label;
    }

    private Region row(String label, Node editor) {
        Label key = new Label(label);
        key.getStyleClass().add("field-key");
        key.setMinWidth(96);
        HBox.setHgrow(editor, Priority.ALWAYS);
        HBox box = new HBox(8, key, editor);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("field-row");
        box.setPadding(new Insets(4, 8, 4, 8));
        return box;
    }
}

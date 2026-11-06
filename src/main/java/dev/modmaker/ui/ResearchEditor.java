package dev.modmaker.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.TechTreeRules;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The single writable surface for a record's {@code research} field, living in the inspector's
 * 属性 tab. The tech tree is now read-only navigation: every research edit commits here through
 * {@link ProjectController#setField}, so undo/dirty/save behave like any other field edit and the
 * forest rebuilds from the same source of truth.
 */
final class ResearchEditor extends VBox {

    private final ProjectController controller;

    ResearchEditor(ProjectController controller) {
        this.controller = controller;
        getStyleClass().add("research");
        setSpacing(8);
    }

    /** (Re)builds the editor for the given record. The instance is reused across rebuilds. */
    void build(ContentRecord record) {
        getChildren().clear();
        if (record == null || controller.project() == null) {
            getChildren().add(muted("techtree.none"));
            return;
        }
        if (!TechTreeRules.allowsResearch(record.patch)) {
            // ContentParser.java:1338-1341 — the game refuses research on a patch outright.
            getChildren().add(muted("techtree.patchRefused"));
            getChildren().add(button("techtree.clear", this::clearResearch));
            return;
        }
        TechTreeRules.Node node = node(record);

        ComboBox<String> parent = combo(candidates(null), node.parent());
        getChildren().add(section("techtree.parent"));
        getChildren().add(row(parent, button("techtree.apply", () -> setParent(parent.getValue()))));

        CheckBox root = new CheckBox(Messages.t("techtree.root"));
        root.setSelected(node.root());
        root.selectedProperty().addListener((observable, was, now) -> setRoot(now));
        getChildren().add(root);

        ComboBox<String> planet = combo(candidates(ContentType.planet), node.planet());
        getChildren().add(section("techtree.planet"));
        getChildren().add(row(planet, button("techtree.apply", () -> setPlanet(planet.getValue()))));

        TextField requirementInput = new TextField();
        requirementInput.setPromptText(Messages.t("techtree.requirements.prompt"));
        ListView<String> requirementList = new ListView<>();
        requirementList.getItems().addAll(node.requirements());
        requirementList.setPrefHeight(80);
        getChildren().add(section("techtree.requirements"));
        getChildren().add(row(requirementInput,
            button("techtree.add", () -> addRequirement(requirementInput.getText()))));
        getChildren().add(requirementList);
        getChildren().add(button("techtree.remove", () ->
            removeRequirement(requirementList.getSelectionModel().getSelectedItem())));

        ComboBox<String> objectiveType = new ComboBox<>();
        objectiveType.getItems().addAll(TechTreeRules.OBJECTIVE_TYPES);
        objectiveType.setValue(TechTreeRules.OBJECTIVE_TYPES.get(0));
        ComboBox<String> objectiveTarget = new ComboBox<>();
        objectiveTarget.setEditable(true);
        objectiveTarget.setPromptText(Messages.t("techtree.objective.target"));
        objectiveTarget.getItems().setAll(candidates(objectiveType(objectiveType.getValue())));
        objectiveType.valueProperty().addListener((observable, was, now) ->
            objectiveTarget.getItems().setAll(candidates(objectiveType(now))));
        ListView<JsonObject> objectiveList = new ListView<>();
        objectiveList.getItems().addAll(node.objectives());
        objectiveList.setPrefHeight(80);
        objectiveList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(JsonObject objective, boolean empty) {
                super.updateItem(objective, empty);
                setText(empty || objective == null ? null : objectiveText(objective));
            }
        });
        getChildren().add(section("techtree.objectives"));
        getChildren().add(row(objectiveType, objectiveTarget,
            button("techtree.add", () ->
                addObjective(objectiveType.getValue(), objectiveTarget.getValue()))));
        getChildren().add(objectiveList);
        getChildren().add(button("techtree.remove", () ->
            removeObjective(objectiveList.getSelectionModel().getSelectedItem())));

        getChildren().add(button("techtree.clear", this::clearResearch));
    }

    // --- editing (package-private so tests drive the same path as the buttons) -------------------

    void setRoot(boolean root) {
        ContentRecord record = controller.selected();
        if (record != null) {
            apply(record, node(record).withRoot(root));
        }
    }

    void setParent(String parent) {
        ContentRecord record = controller.selected();
        if (record != null) {
            apply(record, node(record).withParent(parent));
        }
    }

    void setPlanet(String planet) {
        ContentRecord record = controller.selected();
        if (record != null) {
            apply(record, node(record).withPlanet(planet));
        }
    }

    void addRequirement(String text) {
        ContentRecord record = controller.selected();
        String trimmed = text == null ? "" : text.trim();
        if (record == null || trimmed.isEmpty()) {
            return;
        }
        List<String> requirements = new ArrayList<>(node(record).requirements());
        if (!requirements.contains(trimmed)) {
            requirements.add(trimmed);
            apply(record, node(record).withRequirements(requirements));
        }
    }

    void removeRequirement(String text) {
        ContentRecord record = controller.selected();
        if (record == null || text == null) {
            return;
        }
        List<String> requirements = new ArrayList<>(node(record).requirements());
        if (requirements.remove(text)) {
            apply(record, node(record).withRequirements(requirements));
        }
    }

    void addObjective(String type, String target) {
        ContentRecord record = controller.selected();
        if (record == null) {
            return;
        }
        String wanted = target == null ? "" : target.trim();
        if (wanted.isEmpty()) {
            controller.log("an objective needs a target");
            return;
        }
        List<JsonObject> objectives = new ArrayList<>(node(record).objectives());
        objectives.add(TechTreeRules.objective(type, wanted));
        apply(record, node(record).withObjectives(objectives));
    }

    void removeObjective(JsonObject objective) {
        ContentRecord record = controller.selected();
        if (record == null || objective == null) {
            return;
        }
        List<JsonObject> objectives = new ArrayList<>(node(record).objectives());
        if (objectives.remove(objective)) {
            apply(record, node(record).withObjectives(objectives));
        }
    }

    void clearResearch() {
        ContentRecord record = controller.selected();
        if (record != null) {
            apply(record, TechTreeRules.Node.empty());
        }
    }

    private void apply(ContentRecord record, TechTreeRules.Node node) {
        controller.setField(record, TechTreeRules.FIELD, TechTreeRules.write(node));
    }

    private static TechTreeRules.Node node(ContentRecord record) {
        return TechTreeRules.read(record.fields.get(TechTreeRules.FIELD));
    }

    // --- small helpers --------------------------------------------------------------------------

    /** The content type a candidate list should be limited to, or null for "anything". */
    private static ContentType objectiveType(String type) {
        return switch (type == null ? "" : type) {
            case "SectorComplete", "OnSector" -> ContentType.sector;
            case "OnPlanet" -> ContentType.planet;
            default -> null;
        };
    }

    private static String objectiveText(JsonObject objective) {
        JsonElement type = objective.get("type");
        String kind = type != null && type.isJsonPrimitive() ? type.getAsString() : "?";
        JsonElement target = objective.get(TechTreeRules.objectiveTargetKey(kind));
        if (target == null) {
            return kind;
        }
        // A raw-JSON edit or an imported mod can put a non-primitive target here (a nested sector
        // object, say); getAsString would throw on it and take the whole list cell down with it.
        return kind + ": " + (target.isJsonPrimitive() ? target.getAsString() : target.toString());
    }

    /** Parent/planet/objective candidates: this mod's records plus the known vanilla names. */
    private List<String> candidates(ContentType only) {
        List<String> out = new ArrayList<>();
        ModProject project = controller.project();
        if (project == null) {
            return out;
        }
        String internal = project.internalName();
        for (ContentRecord record : project.contents) {
            if (record.isInline() || !record.type.fileBacked()) {
                continue;
            }
            if (only == null || record.type == only) {
                out.add(record.fullName(internal));
            }
        }
        for (ContentType type : ContentType.values()) {
            if (only == null || type == only) {
                controller.registry().vanillaNames(type).stream().limit(120).forEach(out::add);
            }
        }
        return out.stream().distinct().limit(500).toList();
    }

    private static ComboBox<String> combo(List<String> candidates, String current) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().addAll(candidates);
        box.setEditable(true);
        box.setValue(current == null ? "" : current);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static HBox row(Node... nodes) {
        HBox box = new HBox(6, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Button button(String key, Runnable action) {
        Button button = new Button(Messages.t(key));
        button.getStyleClass().add("tool-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Label section(String key) {
        Label label = new Label(Messages.t(key));
        label.getStyleClass().add("field-label");
        return label;
    }

    private static Label muted(String key) {
        Label label = new Label(Messages.t(key));
        label.getStyleClass().add("muted");
        label.setWrapText(true);
        return label;
    }
}

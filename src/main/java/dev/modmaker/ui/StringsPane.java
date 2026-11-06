package dev.modmaker.ui;

import dev.modmaker.core.model.ContentRecord;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The string table: one row per bundle key this tool manages, one column per locale. Editing a cell
 * writes into the string table and marks that locale's bundle for rewriting on save. Missing
 * translations are highlighted and can be filtered down; keys the tool does not manage (but the mod
 * ships) are shown read-only so nothing silently disappears.
 */
public final class StringsPane extends BorderPane {

    private static final class Row {
        private final String key;
        private final boolean managed;
        private final Map<String, String> values = new LinkedHashMap<>();

        private Row(String key, boolean managed) {
            this.key = key;
            this.managed = managed;
        }
    }

    private final ProjectController controller;
    private final TableView<Row> table = new TableView<>();
    private final TextField filter = new TextField();
    private final Label missingSummary = new Label();
    private List<Row> allRows = List.of();
    /** Off while the bottom strip is hidden; the pane then waits for its next activation. */
    private boolean live = true;
    private boolean stale;

    public StringsPane(ProjectController controller) {
        this.controller = controller;
        filter.setPromptText(Messages.t("strings.filter.prompt"));
        filter.setPrefWidth(240);
        filter.textProperty().addListener((observable, was, now) -> applyFilter());
        HBox top = new HBox(8, filter, missingSummary);
        top.setAlignment(Pos.CENTER_LEFT);
        // Inside the context panel the table sits edge to edge; only the filter row carries a
        // margin. The old .panel ring (padding + darker background) read as a black frame around
        // the table here.
        top.setPadding(new javafx.geometry.Insets(10, 10, 8, 10));
        setTop(top);
        setCenter(table);
        missingSummary.getStyleClass().add("muted");
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

    /** Disables the table rebuild while the pane is not on screen; re-activation catches up. */
    public void setLive(boolean value) {
        live = value;
        if (live && stale) {
            stale = false;
            refresh();
        }
    }

    public void refresh() {
        String query = filter.getText() == null ? "" : filter.getText().toLowerCase(java.util.Locale.ROOT);
        table.getColumns().clear();
        table.getItems().clear();
        missingSummary.setText("");
        if (controller.project() == null) {
            allRows = List.of();
            return;
        }
        List<String> locales = controller.project().locales;

        TableColumn<Row, String> keyColumn = new TableColumn<>(Messages.t("strings.key"));
        keyColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().key));
        keyColumn.setPrefWidth(300);
        table.getColumns().add(keyColumn);

        for (String locale : locales) {
            TableColumn<Row, String> column = new TableColumn<>(locale);
            column.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().values.getOrDefault(locale, "")));
            // Editable cells that also show missing translations in red.
            column.setCellFactory(view -> new TextFieldTableCell<Row, String>(stringConverter()) {
                @Override
                public void updateItem(String value, boolean empty) {
                    super.updateItem(value, empty);
                    Row row = getTableRow() == null ? null : getTableRow().getItem();
                    boolean missing = !empty && row != null && row.managed
                        && (value == null || value.isEmpty())
                        && row.values.values().stream().anyMatch(other -> !other.isEmpty());
                    setStyle(missing ? "-fx-text-fill: #f4564e;" : "");
                }
            });
            column.setOnEditCommit(event -> {
                String key = event.getRowValue().key;
                controller.setString(key, locale, event.getNewValue());
                controller.log("string " + key + " [" + locale + "] updated");
            });
            column.setPrefWidth(220);
            table.getColumns().add(column);
        }

        List<Row> rows = new ArrayList<>();
        controller.project().strings.values.forEach((key, byLocale) -> {
            Row row = new Row(key, true);
            row.values.putAll(byLocale);
            rows.add(row);
        });
        // Extra keys (stat.*, category.*, script strings) ship with the mod; show them read-only
        // so their existence and content are visible instead of silently rewritten on save.
        controller.project().strings.extra.forEach((locale, values) -> values.forEach((key, value) -> {
            Row row = rows.stream().filter(candidate -> candidate.key.equals(key)).findFirst()
                .orElseGet(() -> {
                    Row extra = new Row(key, false);
                    rows.add(extra);
                    return extra;
                });
            row.values.put(locale, value);
        }));
        allRows = rows;
        table.setItems(FXCollections.observableArrayList(rows));
        table.setEditable(true);
        long missing = countMissing(locales);
        if (!rows.isEmpty()) {
            missingSummary.setText(Messages.t("strings.missing", missing));
        }
        if (rows.isEmpty()) {
            table.setPlaceholder(new Label(Messages.t("strings.empty")));
        }
    }

    private long countMissing(List<String> locales) {
        return allRows.stream().filter(row -> row.managed)
            .filter(row -> locales.stream().anyMatch(locale ->
                row.values.getOrDefault(locale, "").isEmpty()
                    && row.values.values().stream().anyMatch(value -> !value.isEmpty())))
            .count();
    }

    /** Passes strings through unchanged, as the default text-cell converter does. */
    private static javafx.util.StringConverter<String> stringConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : value;
            }

            @Override
            public String fromString(String value) {
                return value;
            }
        };
    }

    private void applyFilter() {
        String query = filter.getText() == null ? "" : filter.getText().toLowerCase(java.util.Locale.ROOT);
        if (query.isEmpty()) {
            table.setItems(FXCollections.observableArrayList(allRows));
            return;
        }
        table.setItems(FXCollections.observableArrayList(allRows.stream()
            .filter(row -> row.key.toLowerCase(java.util.Locale.ROOT).contains(query)
                || row.values.values().stream()
                    .anyMatch(value -> value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query)))
            .toList()));
    }

    /** Adds a string row for a record's four localisable fields, so new content can be named. */
    public void ensureRowsFor(ContentRecord record) {
        refresh();
    }
}

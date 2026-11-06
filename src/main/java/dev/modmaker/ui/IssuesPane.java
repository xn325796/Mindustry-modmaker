package dev.modmaker.ui;

import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.validate.Issue;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

import java.util.List;

/** Validation findings for the current project; clicking one selects the record it belongs to. */
public final class IssuesPane extends BorderPane {

    private final ProjectController controller;
    private final ListView<Issue> list = new ListView<>();
    private final Label summary = new Label();
    /** Off while the bottom strip is hidden; the pane then waits for its next activation. */
    private boolean live = true;
    private boolean stale;

    public IssuesPane(ProjectController controller) {
        this.controller = controller;
        // Same treatment as StringsPane: the list sits edge to edge, only the summary row carries
        // a margin - the .panel ring read as a black frame inside the context panel.
        summary.setPadding(new javafx.geometry.Insets(10, 10, 8, 10));
        setTop(summary);
        setCenter(list);
        summary.getStyleClass().add("muted");

        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Issue issue, boolean empty) {
                super.updateItem(issue, empty);
                if (empty || issue == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText("[" + Messages.t(issue.isError() ? "validation.level.error" : "validation.level.warning")
                    + "] " + Messages.t(issue.key(), issue.args().toArray()));
                getStyleClass().removeAll("issue-error", "issue-warning");
                getStyleClass().add(issue.isError() ? "issue-error" : "issue-warning");
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((observable, was, issue) -> {
            if (issue == null || issue.contentId() == null || controller.project() == null) {
                return;
            }
            ContentRecord record = controller.project().contentById(issue.contentId());
            if (record != null) {
                controller.select(record);
            }
        });
        // The findings list must track edits live: fixing a warning makes it disappear immediately,
        // and an edit that introduces a problem shows up without pressing "validate" again. The list
        // is rendered from the last published pass and refreshed when a new one lands, so opening
        // this pane never re-runs the validator on the FX thread.
        controller.onProjectChanged(this::onProjectChanged);
        controller.onValidationRefreshed(this::onValidationRefreshed);
        refresh();
    }

    private void onValidationRefreshed() {
        if (live) {
            refresh();
        } else {
            stale = true;
        }
    }

    private void onProjectChanged() {
        if (live) {
            refresh();
        } else {
            stale = true;
        }
    }

    /** Disables the refresh work while the pane is not on screen; re-activation catches up. */
    public void setLive(boolean value) {
        live = value;
        if (live && stale) {
            stale = false;
            refresh();
        }
    }

    public void refresh() {
        if (controller.project() == null) {
            list.setItems(FXCollections.observableArrayList());
            summary.setText("");
            return;
        }
        List<Issue> issues = controller.latestIssues();
        list.setItems(FXCollections.observableArrayList(issues));
        long errors = issues.stream().filter(Issue::isError).count();
        summary.setText(Messages.t("validation.summary", errors, issues.size() - errors));
    }
}

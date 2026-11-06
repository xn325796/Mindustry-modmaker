package dev.modmaker.ui;

import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.validate.Issue;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.List;

/**
 * The single right-hand panel: four tabs instead of the fixed inspector plus a stack of side
 * previews and the hidden bottom panes. 属性 is the default; 预览/校验/文本 activate their pane the
 * first time they are opened, so the heavy string table and the issue list stay idle until asked.
 *
 * <p>The head strip above the tabs names the record the panel is about - display name, type and
 * its own finding count from the published validation pass - so every tab, not just 属性, carries
 * the selection context. AppShell drives {@link #updateHead}: the panel is rebuilt on a language
 * switch, and a rebuilt pane must not re-register controller listeners.
 */
final class ContextPanel extends BorderPane {

    private final ProjectController controller;
    private final TabPane tabs = new TabPane();
    private final java.util.List<Label> headIds = new java.util.ArrayList<>();
    private final java.util.List<Label> headNames = new java.util.ArrayList<>();
    private final java.util.List<Label> headFindings = new java.util.ArrayList<>();
    private final Tab validationTab;
    private final Tab stringsTab;
    private final IssuesPane issues;
    private final StringsPane strings;

    ContextPanel(ProjectController controller, RecordInspector inspector, RecordPreview preview,
        IssuesPane issues, StringsPane strings) {
        this.controller = controller;
        this.issues = issues;
        this.strings = strings;
        getStyleClass().add("context-panel");
        setPrefWidth(480);
        setMinWidth(480);
        setMaxWidth(480);

        Tab propertiesTab = tab("context.properties", inspector);
        Tab previewTab = tab("context.preview", preview);
        validationTab = tab("context.validation", issues);
        stringsTab = tab("context.strings", strings);

        tabs.getStyleClass().add("context-tabs");
        tabs.getTabs().addAll(propertiesTab, previewTab, validationTab, stringsTab);
        tabs.getSelectionModel().select(propertiesTab);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            if (now == validationTab) {
                issues.setLive(true);
            } else if (now == stringsTab) {
                strings.setLive(true);
            }
        });
        setCenter(tabs);
        updateHead(controller.selected());
    }

    /**
     * One inspector head per tab (the head sits below the shared tab strip, so it belongs to each
     * tab's content). The three labels are registered so {@link #updateHead} keeps every copy in
     * sync with the selection.
     */
    private javafx.scene.layout.VBox head() {
        Label id = new Label();
        id.getStyleClass().add("context-head-id");
        id.setTextOverrun(OverrunStyle.ELLIPSIS);
        id.setMinWidth(0);
        HBox.setHgrow(id, Priority.ALWAYS);
        HBox idRow = new HBox(id);
        idRow.getStyleClass().add("context-head-row");
        idRow.setAlignment(Pos.CENTER_LEFT);

        Label name = new Label();
        name.getStyleClass().add("context-head-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMinWidth(0);
        HBox.setHgrow(name, Priority.ALWAYS);
        Label findings = new Label();
        findings.getStyleClass().add("context-head-findings");
        findings.setMaxWidth(Region.USE_PREF_SIZE);
        HBox nameRow = new HBox(10, name, findings);
        nameRow.getStyleClass().add("context-head-row");
        nameRow.setAlignment(Pos.CENTER_LEFT);

        javafx.scene.layout.VBox head = new javafx.scene.layout.VBox(idRow, nameRow);
        head.getStyleClass().add("context-head");
        headIds.add(id);
        headNames.add(name);
        headFindings.add(findings);
        return head;
    }

    private javafx.scene.Node tabBody(javafx.scene.Node content) {
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(head(), content);
        javafx.scene.layout.VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    /**
     * The record the panel is about: id, display name, type and its own findings from the last
     * published validation pass. Called on construction and by AppShell on selection, project and
     * validation changes; the count follows the same source as the 校验 tab and the inspector's
     * report block, so the head can never disagree with them.
     */
    void updateHead(ContentRecord record) {
        for (Label findings : headFindings) {
            findings.getStyleClass().removeAll("context-head-error", "context-head-warn");
            findings.setText("");
        }
        if (record == null || controller.project() == null) {
            String none = Messages.t("inspector.title") + ": " + Messages.t("inspector.noSelection");
            for (Label id : headIds) {
                id.setText(none);
            }
            for (Label name : headNames) {
                name.setText(Messages.t("inspector.noSelection"));
            }
            return;
        }
        String title = Messages.t("inspector.title") + ": " + record.name;
        String name = controller.displayName(record) + " · " + controller.subtitleOf(record);
        for (Label id : headIds) {
            id.setText(title);
        }
        for (Label label : headNames) {
            label.setText(name);
        }
        List<Issue> issuesOfRecord = controller.latestIssues().stream()
            .filter(issue -> record.id.equals(issue.contentId()))
            .toList();
        String count = issuesOfRecord.isEmpty()
            ? Messages.t("context.head.clean")
            : Messages.t("context.head.findings", issuesOfRecord.size());
        for (Label findings : headFindings) {
            findings.setText(count);
            if (!issuesOfRecord.isEmpty()) {
                findings.getStyleClass().add(issuesOfRecord.stream().anyMatch(Issue::isError)
                    ? "context-head-error" : "context-head-warn");
            }
        }
    }

    void showValidation() {
        issues.setLive(true);
        tabs.getSelectionModel().select(validationTab);
    }

    void showStrings() {
        strings.setLive(true);
        tabs.getSelectionModel().select(stringsTab);
    }

    private Tab tab(String key, javafx.scene.Node content) {
        Tab tab = new Tab(Messages.t(key), tabBody(content));
        tab.setClosable(false);
        return tab;
    }
}

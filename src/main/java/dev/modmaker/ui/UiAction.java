package dev.modmaker.ui;

import java.util.function.Consumer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;

/**
 * One command, one definition. Menu items and toolbar buttons derive from the same action, so an
 * entry can never be enabled in one place and disabled in another, and both share the accelerator
 * and the error handling. Labels resolve from the message key when a control is built, so a
 * language switch rebuilds the shell and gets fresh text for free.
 */
final class UiAction {

    private final String textKey;
    private final Runnable run;
    private final KeyCombination accelerator;
    private final Consumer<String> onError;

    /** Mirrored by every built control: {@code false} means the command is unavailable right now. */
    final BooleanProperty enabled = new SimpleBooleanProperty(true);

    UiAction(String textKey, Runnable run, KeyCombination accelerator, Consumer<String> onError) {
        this.textKey = textKey;
        this.run = run;
        this.accelerator = accelerator;
        this.onError = onError;
    }

    MenuItem menuItem() {
        MenuItem item = new MenuItem(Messages.t(textKey));
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.disableProperty().bind(enabled.not());
        item.setOnAction(event -> fire());
        return item;
    }

    Button button() {
        Button button = new Button(Messages.t(textKey));
        button.getStyleClass().add("tool-button");
        button.disableProperty().bind(enabled.not());
        button.setOnAction(event -> fire());
        return button;
    }

    private void fire() {
        try {
            run.run();
        } catch (RuntimeException failure) {
            onError.accept("error: " + failure);
        }
    }
}

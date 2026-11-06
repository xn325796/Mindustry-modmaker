package dev.modmaker.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;

import java.util.function.Consumer;

/**
 * The one place the UI turns a failure into a message the user sees.
 *
 * <p>Error handling used to be scattered: the io worker built its own {@link Alert}, the menu and
 * toolbar handlers logged a naked exception, exit-save logged a third way. The same failure could
 * therefore be invisible, logged, or shown in a dialog depending on which code path hit it - and a
 * headless test could not observe any of them. Everything goes through one object now: the message
 * is always logged, and interactive paths additionally show a single dialog.
 */
public final class ErrorReporter {

    private final Consumer<String> log;

    public ErrorReporter(Consumer<String> log) {
        this.log = log;
    }

    /**
     * Logs the failure and shows one error dialog. Safe to call from a worker thread: the dialog is
     * raised on the FX thread.
     *
     * @param context what the user was trying to do, e.g. "could not save the project"
     */
    public void report(String context, Throwable failure) {
        String message = context + ": " + describe(failure);
        log.accept(message);
        onFxThread(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message);
            alert.setHeaderText(context);
            alert.showAndWait();
        });
    }

    /** Logs the failure without interrupting the user (background cleanup, optional actions). */
    public void reportQuietly(String context, Throwable failure) {
        log.accept(context + ": " + describe(failure));
    }

    /**
     * The one-line rendering of a failure. {@code getMessage()} is often null (an
     * {@code AccessDeniedException} from a locked file, say), so the class name is always shown.
     */
    public static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown error";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    }

    private static void onFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        try {
            Platform.runLater(action);
        } catch (IllegalStateException toolkitNotInitialized) {
            // Headless: the log line is all there is.
        }
    }
}

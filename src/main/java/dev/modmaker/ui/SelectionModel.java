package dev.modmaker.ui;

import dev.modmaker.core.model.ContentRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The single selected content record plus its listeners.
 *
 * <p>Extracted from {@link ProjectController}: "which record is selected" is a plain piece of state
 * with one notification rule (every {@link #select} notifies, a programmatic {@link #clear} does not),
 * and keeping it here means the controller's edit commands no longer have to know about the panes.
 */
public final class SelectionModel {

    private final List<Consumer<ContentRecord>> listeners = new ArrayList<>();
    private ContentRecord selected;

    public ContentRecord selected() {
        return selected;
    }

    /** Selects a record and notifies every listener, even when the record did not change. */
    public void select(ContentRecord record) {
        selected = record;
        listeners.forEach(listener -> listener.accept(record));
    }

    /**
     * Forgets the selection without notifying. Used where the project itself is being replaced - a
     * listener asked to render a record of a project that is already gone would be wrong.
     */
    public void clear() {
        selected = null;
    }

    /** Registers a listener invoked for every {@link #select}. */
    public void onChange(Consumer<ContentRecord> listener) {
        listeners.add(listener);
    }
}

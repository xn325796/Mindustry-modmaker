package dev.modmaker.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The undo/redo history, on its own so it can be reasoned about (and unit-tested) without a project.
 *
 * <p>It used to be two {@code ArrayDeque}s and a handful of methods inside
 * {@link ProjectController}; the controller now only decides <em>what</em> a command is, this class
 * decides when it becomes undoable.
 */
public final class UndoManager {

    /** One reversible user action. redo() re-applies what the user did. */
    public interface Command {
        void undo();

        void redo();
    }

    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();

    /** Records a fresh command and drops the redo branch, as any new edit does. */
    public void push(Command command) {
        undoStack.push(command);
        redoStack.clear();
    }

    /** The command undo would run, without running it; used to coalesce repeated edits. */
    public Command peekUndo() {
        return undoStack.peek();
    }

    /** Drops the redo branch without touching the undo stack. */
    public void clearRedo() {
        redoStack.clear();
    }

    /** Undoes the last command; returns it, or null when the history is empty. */
    public Command undo() {
        Command command = undoStack.poll();
        if (command == null) {
            return null;
        }
        command.undo();
        redoStack.push(command);
        return command;
    }

    /** Redoes the last undone command; returns it, or null when there is nothing to redo. */
    public Command redo() {
        Command command = redoStack.poll();
        if (command == null) {
            return null;
        }
        command.redo();
        undoStack.push(command);
        return command;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public int depth() {
        return undoStack.size();
    }

    /** Forgets everything; a newly opened project starts with no history. */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}

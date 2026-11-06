package dev.modmaker.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The undo stack on its own: ordering, the redo branch, depth and clearing. */
class UndoManagerTest {

    private final UndoManager history = new UndoManager();
    private final List<String> calls = new ArrayList<>();

    private UndoManager.Command command(String name) {
        return new UndoManager.Command() {
            @Override
            public void undo() {
                calls.add("undo:" + name);
            }

            @Override
            public void redo() {
                calls.add("redo:" + name);
            }
        };
    }

    @Test
    void undoesAndRedoesInStackOrder() {
        UndoManager.Command first = command("first");
        UndoManager.Command second = command("second");
        history.push(first);
        history.push(second);
        assertEquals(2, history.depth());

        history.undo();
        history.undo();
        assertEquals(List.of("undo:second", "undo:first"), calls);
        assertFalse(history.canUndo());
        assertTrue(history.canRedo());

        history.redo();
        assertEquals("redo:first", calls.get(calls.size() - 1));
        assertEquals(1, history.depth());
    }

    @Test
    void aNewCommandDropsTheRedoBranch() {
        history.push(command("old"));
        history.undo();
        assertTrue(history.canRedo());

        history.push(command("new"));
        assertFalse(history.canRedo(), "a fresh edit invalidates the redo branch");
        assertEquals(1, history.depth());
    }

    @Test
    void peekDoesNotRunTheCommand() {
        UndoManager.Command command = command("peeked");
        history.push(command);
        assertSame(command, history.peekUndo());
        assertEquals(List.of(), calls);
    }

    @Test
    void clearingForgetsBothBranches() {
        history.push(command("kept"));
        history.undo();
        history.clear();
        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
        assertEquals(0, history.depth());
        assertNull(history.undo(), "undoing an empty history is a no-op, not an error");
        assertNull(history.redo());
    }
}

package dev.modmaker.ui;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Selection notifications: every select notifies, a programmatic clear does not. */
class SelectionModelTest {

    private final SelectionModel model = new SelectionModel();
    private final List<ContentRecord> seen = new ArrayList<>();

    @Test
    void selectPublishesTheRecordAndNotifies() {
        model.onChange(seen::add);
        ContentRecord record = ContentRecord.create("c1", ContentType.item, "thing");

        model.select(record);

        assertEquals(record, model.selected());
        assertEquals(List.of(record), seen);
    }

    @Test
    void selectingTheSameRecordAgainStillNotifies() {
        ContentRecord record = ContentRecord.create("c1", ContentType.item, "thing");
        model.select(record);
        seen.clear();
        model.onChange(seen::add);

        model.select(record);

        assertEquals(List.of(record), seen, "panes re-read the record on every explicit select");
    }

    @Test
    void clearingForgetsTheSelectionWithoutTellingThePanes() {
        model.onChange(seen::add);
        model.select(ContentRecord.create("c1", ContentType.item, "thing"));
        seen.clear();

        model.clear();

        assertNull(model.selected());
        assertEquals(List.of(), seen, "a project replacement must not make panes render a dead record");
    }
}

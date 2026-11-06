package dev.modmaker.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-line rendering every failure goes through. The dialog half needs a toolkit and is exercised
 * by the smoke test; here the formatting and the "never throw while reporting a failure" contract are
 * locked down.
 */
class ErrorReporterTest {

    @Test
    void showsTheExceptionTypeWhenThereIsNoMessage() {
        assertEquals("IOException", ErrorReporter.describe(new IOException()));
        assertEquals("IOException: file is locked",
            ErrorReporter.describe(new IOException("file is locked")));
        assertEquals("unknown error", ErrorReporter.describe(null));
    }

    @Test
    void aBlankMessageFallsBackToTheType() {
        assertEquals("IllegalStateException", ErrorReporter.describe(new IllegalStateException("  ")));
    }

    @Test
    void quietReportsAlwaysReachTheLog() {
        List<String> messages = new ArrayList<>();
        ErrorReporter reporter = new ErrorReporter(messages::add);
        reporter.reportQuietly("could not save", new IOException("disk full"));
        assertEquals(List.of("could not save: IOException: disk full"), messages);
    }

    @Test
    void reportingANullFailureIsNotAnErrorItself() {
        List<String> messages = new ArrayList<>();
        ErrorReporter reporter = new ErrorReporter(messages::add);
        assertDoesNotThrow(() -> reporter.reportQuietly("command failed", null));
        assertTrue(messages.get(0).contains("unknown error"), messages.toString());
    }
}

package dev.modmaker.core.io;

/**
 * Callback the import pipeline reports to while it works, and the channel a "cancel" click
 * travels back on. Every importer entry point has an overload without one (or with {@link #NONE}),
 * so command-line runs and tests stay untouched.
 *
 * <p>Reporting is the io thread's job; the listener decides how to get the numbers to the FX
 * thread. Throttling is the caller's job too: call {@link #worthReporting} before emitting a
 * tick so a 1500-file mod does not flood the queue with 1500 status updates.
 */
public interface ImportProgress {

    /** The three phases an import runs through, in order. */
    enum Stage {
        READING, BUILDING, WRITING
    }

    /** Emit a tick every this many processed units, plus one for the last unit. */
    int REPORT_EVERY = 64;

    /** Announced once per stage before its work starts; {@code total} may be 0 or -1 when unknown. */
    void stageStarted(Stage stage, int total);

    /** One throttled tick: {@code done} of {@code total} units of this stage are processed. */
    void progressed(Stage stage, int done, int total);

    /** Checked between files; true once the user asked the import to stop. */
    boolean isCancelled();

    /** Throws {@link ImportCancelledException} when the user has cancelled. */
    default void checkCancelled() {
        if (isCancelled()) {
            throw new ImportCancelledException();
        }
    }

    /** True when a tick for this counter is worth emitting (every {@link #REPORT_EVERY}th, plus the last). */
    static boolean worthReporting(int done, int total) {
        return done == total || done % REPORT_EVERY == 0;
    }

    /** Silent, never-cancelled listener for callers that do not show progress. */
    ImportProgress NONE = new ImportProgress() {
        @Override
        public void stageStarted(Stage stage, int total) {
        }

        @Override
        public void progressed(Stage stage, int done, int total) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };
}

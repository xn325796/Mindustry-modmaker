package dev.modmaker.core.io;

/**
 * Thrown out of the import pipeline when {@link ImportProgress#isCancelled()} turns true. It is
 * unchecked on purpose: every reader, importer and writer signature stays as it was, and only the
 * UI entry point has to catch it.
 */
public class ImportCancelledException extends RuntimeException {

    public ImportCancelledException() {
        super("import cancelled");
    }
}

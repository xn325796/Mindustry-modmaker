package dev.modmaker.ui;

import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.core.validate.Issue;
import dev.modmaker.core.validate.Validator;

import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The project's validation findings: what is cached, when it is recomputed, and how a pass gets off
 * the JavaFX thread.
 *
 * <p>Validation used to run inline on the FX thread the first time any pane asked for it, and then
 * again after every edit that invalidated the cache. That is tens of milliseconds on a large mod and
 * a visible stall per keystroke. The session keeps the same cache contract - {@link #current()} still
 * computes synchronously when nothing is cached, so callers that own the model (the CLI-style tests,
 * the exit guard) see fresh findings immediately - and adds a background mode: after an edit it
 * debounces, snapshots the model on the FX thread, runs the validator on a worker over the snapshot,
 * and publishes the result back on the FX thread.
 *
 * <p>Views read {@link #latest()} and subscribe with {@link #onRefreshed}; they never block the UI
 * thread waiting for a pass. {@link #pending()} backs the "checking..." indicator.
 */
public final class ValidationSession {

    /** A typing run produces one pass, not one per keystroke. */
    private static final long DEBOUNCE_MILLIS = 150;

    /** Shared debounce timer: daemon, so it never keeps the JVM alive. */
    private static final ScheduledExecutorService DEBOUNCE = Executors.newSingleThreadScheduledExecutor(
        runnable -> {
            Thread thread = new Thread(runnable, "modmaker-validation-debounce");
            thread.setDaemon(true);
            return thread;
        });

    private final Validator validator;
    private final Supplier<ModProject> projects;
    private final List<Runnable> refreshedListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong generation = new AtomicLong();

    /** Findings for the current model state; null while a pass is pending. */
    private volatile List<Issue> cache;
    /** The last findings that were actually computed; never null, may be stale. */
    private volatile List<Issue> published = List.of();
    /** Background executor; null keeps validation synchronous (headless callers, tests). */
    private volatile Executor worker;
    private volatile boolean pending;

    public ValidationSession(SchemaRegistry registry, Supplier<ModProject> projects) {
        this.validator = new Validator(registry);
        this.projects = projects;
    }

    /**
     * Enables background passes. Called once by the shell with its io executor; until then every
     * request is served synchronously, which is what headless tests need.
     */
    public void useWorker(Executor executor) {
        this.worker = executor;
    }

    /** Registers a callback run on the FX thread after fresh findings were published. */
    public void onRefreshed(Runnable listener) {
        refreshedListeners.add(listener);
    }

    /** The findings for the current model, computing them here and now when needed. */
    public List<Issue> current() {
        List<Issue> cached = cache;
        if (cached != null) {
            return cached;
        }
        ModProject project = projects.get();
        if (project == null) {
            return List.of();
        }
        List<Issue> computed = List.copyOf(validator.validate(project));
        // A synchronous pass is fresher than anything in flight: cancel the background one.
        generation.incrementAndGet();
        pending = false;
        cache = computed;
        published = computed;
        return computed;
    }

    /** The last computed findings; never blocks, may be stale while {@link #pending()}. */
    public List<Issue> latest() {
        List<Issue> cached = cache;
        return cached == null ? published : cached;
    }

    /** Invalidates the cache after a model change and, when background mode is on, schedules a pass. */
    public void invalidate() {
        cache = null;
        generation.incrementAndGet();
        schedule();
    }

    /** Forgets everything, including the published list; a newly opened project has no findings. */
    public void reset() {
        cache = null;
        published = List.of();
        generation.incrementAndGet();
        pending = false;
        refreshedListeners.forEach(Runnable::run);
    }

    /** An explicit "validate now": a fresh synchronous pass that supersedes any queued one. */
    public List<Issue> forceSync() {
        ModProject project = projects.get();
        if (project == null) {
            return List.of();
        }
        List<Issue> findings = List.copyOf(validator.validate(project));
        generation.incrementAndGet();
        pending = false;
        cache = findings;
        published = findings;
        return findings;
    }

    /**
     * Recomputes in the background regardless of the cache. Must be called on the FX thread: the
     * snapshot is taken there, because the model belongs to it.
     */
    public void refreshAsync() {
        Executor executor = worker;
        if (executor == null) {
            forceSync();
            return;
        }
        long gen = generation.incrementAndGet();
        pending = true;
        ModProject project = projects.get();
        if (project == null) {
            pending = false;
            return;
        }
        ModProject snapshot = project.snapshot();
        try {
            executor.execute(() -> runPass(gen, snapshot));
        } catch (RejectedExecutionException rejected) {
            pending = false;
        }
    }

    /** True while a background pass is queued or running. */
    public boolean pending() {
        return pending;
    }

    /** The validator itself, for callers that validate a snapshot of their own. */
    public List<Issue> validate(ModProject snapshot) {
        return List.copyOf(validator.validate(snapshot));
    }

    private void schedule() {
        Executor executor = worker;
        if (executor == null) {
            return;
        }
        long gen = generation.get();
        pending = true;
        try {
            DEBOUNCE.schedule(() -> {
                try {
                    Platform.runLater(() -> capture(gen, executor));
                } catch (IllegalStateException headless) {
                    // No FX toolkit: there is nothing to keep responsive, validation runs on demand.
                    pending = false;
                }
            }, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            pending = false;
        }
    }

    /** Runs on the FX thread: snapshots the model, then hands the read-only copy to the worker. */
    private void capture(long gen, Executor executor) {
        if (gen != generation.get()) {
            return;
        }
        ModProject project = projects.get();
        if (project == null) {
            pending = false;
            return;
        }
        ModProject snapshot = project.snapshot();
        try {
            executor.execute(() -> runPass(gen, snapshot));
        } catch (RejectedExecutionException shuttingDown) {
            // The shell shuts its worker down while the toolkit is still delivering events, so a
            // debounce that fires during teardown lands here. Without this the rejection escaped on
            // the FX thread and left "checking..." stuck on.
            pending = false;
        }
    }

    private void runPass(long gen, ModProject snapshot) {
        if (gen != generation.get()) {
            return;
        }
        List<Issue> findings;
        try {
            findings = List.copyOf(validator.validate(snapshot));
        } catch (RuntimeException failure) {
            onFxThread(() -> {
                if (gen == generation.get()) {
                    pending = false;
                }
            });
            return;
        }
        onFxThread(() -> publish(gen, findings));
    }

    private void publish(long gen, List<Issue> findings) {
        if (gen != generation.get()) {
            // The project changed while this pass ran: a newer pass is already queued.
            return;
        }
        pending = false;
        cache = findings;
        published = findings;
        refreshedListeners.forEach(Runnable::run);
    }

    private static void onFxThread(Runnable action) {
        try {
            Platform.runLater(action);
        } catch (IllegalStateException toolkitNotInitialized) {
            // Headless tests: there is no FX thread, so publish inline.
            action.run();
        }
    }
}

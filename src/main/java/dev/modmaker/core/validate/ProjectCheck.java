package dev.modmaker.core.validate;

/**
 * One rule of the project validation, checked independently so it can be run on its own.
 *
 * <p>{@link Validator} used to be a single method that called a dozen private helpers in a fixed
 * order: adding a rule meant editing the main flow, a rule could not be switched off, and a scene
 * that only needed one family of findings (the reference sweep after a rename, say) still paid for
 * all of them. A check is now a small object with a stable id, a self-contained body and no ordering
 * dependency on its neighbours - the only shared state is the read-only {@link ValidationContext}.
 */
public interface ProjectCheck {

    /** Stable short id; used for selective runs and to keep test failures readable. */
    String id();

    /**
     * True when the check reads content names through the mod's internal name. Such checks are
     * skipped entirely for a project whose internal name is empty, because every content file name
     * and every reference prefix would be meaningless.
     */
    default boolean requiresInternalName() {
        return true;
    }

    /** Appends this check's findings to the context. Must not mutate the project. */
    void check(ValidationContext context);
}

package dev.modmaker.core.fmt;

/**
 * Which main-area workspace a content type is edited in. The block builder keeps the node canvas as
 * the flow editor (and everything that participates in reference edges); simple assets get a
 * resource grid; units get their own grid view; planets and sectors get an arrangement view; the
 * tech tree is a global view of every record's research index rather than a per-type one.
 */
public enum Workspace {
    /** Resource grid: items, liquids and the other simple assets. */
    assets,
    /** Flow editing on the node canvas: blocks, and everything reference-related. */
    canvas,
    /** Unit list: units get their own tab next to the block builder. */
    units,
    /** Arrangement view: planets and their sectors. */
    cosmos,
    /** Research index of every record: the tech tree the {@code research} fields describe. */
    techTree,
    /** Reserved for the (not yet implemented) scripting workspace; reaches no content type. */
    script;

    public static Workspace of(ContentType type) {
        return switch (type) {
            case item, liquid, status, weather, team -> assets;
            case block -> canvas;
            case unit -> units;
            case planet, sector -> cosmos;
            default -> canvas;
        };
    }

    /**
     * The types the resources grid lists - every file-backed type whose canonical home is the
     * assets tab. The others own a dedicated workspace (canvas / units / cosmos) and must not be
     * listed twice; inline-only types are reached through their host record. Kept here next to
     * {@link #of} so the type-to-workspace map stays in exactly one place.
     */
    public static java.util.List<ContentType> assetTypes() {
        return java.util.Arrays.stream(ContentType.values())
            .filter(type -> type.fileBacked() && of(type) == assets)
            .toList();
    }
}

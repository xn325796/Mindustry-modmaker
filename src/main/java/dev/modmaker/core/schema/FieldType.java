package dev.modmaker.core.schema;

/** Field widget kinds, matching the "type" strings used by schemas/schema.json and fields.json. */
public enum FieldType {
    number,
    text,
    textArray,
    bool,
    color,
    /** One value out of a fixed list (schema spelling: "enum"). */
    enumeration,
    /** A reference to another content object, by name. */
    content,
    /** A sprite/region name. */
    sprite,
    /** A single item/liquid/payload stack, written as "name/amount". */
    stack,
    /** A list of stacks (schema spelling: "stack[]"). */
    stackArray,
    /**
     * A structured group whose members are references - {@code consumes} groups, a unit's
     * {@code requirements} (UnitReq), a turret's {@code ammoTypes} map, a factory's {@code plans}.
     * Schema spelling: "struct", with the shape in the stack kind ("struct:unitReq"). The
     * inspector edits these as raw JSON; the canvas derives edges and wires references through
     * {@code ReferenceOps} by shape.
     */
    struct,
    /** Anything else: edited as raw JSON. */
    raw;

    public static FieldType parse(String rawType) {
        if (rawType == null) {
            return raw;
        }
        String value = rawType.trim();
        if (value.startsWith("stack[]")) {
            return stackArray;
        }
        // "struct:unitReq" & co - the shape rides in the stack kind (SchemaRegistry.stackKindOf).
        if (value.startsWith("struct")) {
            return struct;
        }
        return switch (value) {
            case "stack" -> stack;
            case "text[]" -> textArray;
            case "enum" -> enumeration;
            case "number" -> number;
            case "text" -> text;
            case "bool" -> bool;
            case "color" -> color;
            case "content" -> content;
            case "sprite" -> sprite;
            case "struct" -> struct;
            default -> raw;
        };
    }

    /** Fields whose value names other content; these become edges on the canvas. */
    public boolean isReference() {
        return this == content || this == stack || this == stackArray || this == struct;
    }

    public boolean isStack() {
        return this == stack || this == stackArray;
    }
}

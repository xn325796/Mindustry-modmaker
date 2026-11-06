package dev.modmaker.core.validate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

/** An input batch bigger than the block's item buffer can never accumulate, so the crafter stalls. */
final class RecipeCapacityCheck implements ProjectCheck {

    /** Block.itemCapacity default in the game sources (Block.java:77). */
    private static final int DEFAULT_ITEM_CAPACITY = 10;

    @Override
    public String id() {
        return "recipeCapacity";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        for (ContentRecord record : project.contents) {
            if (record.type != ContentType.block || record.isInline()) {
                continue;
            }
            String className = context.registry().resolveClass(record.type, record.className());
            if (className == null
                || !CheckSupport.isA(context.registry(), className, "GenericCrafter")) {
                continue;
            }
            JsonElement consumes = record.fields.get("consumes");
            if (consumes == null || !consumes.isJsonObject()) {
                continue;
            }
            JsonObject consumeObject = consumes.getAsJsonObject();
            double batch = maxStackAmount(consumeObject.get("items"));
            if (batch < 0) {
                batch = maxStackAmount(consumeObject.get("item"));
            }
            if (batch < 0) {
                continue;
            }
            Double capacity = CheckSupport.number(record.fields.get("itemCapacity"));
            double cap = capacity == null ? DEFAULT_ITEM_CAPACITY : capacity;
            if (batch > cap) {
                context.report(Issue.warningOn(record.id, "validate.crafter.batchTooBig",
                    record.name, (int) Math.ceil(batch), (int) cap,
                    capacity == null ? " (the game default)" : ""));
            }
        }
    }

    /**
     * The largest amount in a stack value ("item/5", {item, amount}, or arrays of either); -1 when
     * the value names no amount at all.
     */
    private static double maxStackAmount(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return -1;
        }
        if (value.isJsonArray()) {
            double max = -1;
            for (JsonElement element : value.getAsJsonArray()) {
                max = Math.max(max, maxStackAmount(element));
            }
            return max;
        }
        if (value.isJsonObject()) {
            Double amount = CheckSupport.number(value.getAsJsonObject().get("amount"));
            return amount == null ? -1 : amount;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return -1;
        }
        String text = value.getAsString().trim();
        int slash = text.indexOf('/');
        if (slash < 0) {
            return -1;
        }
        try {
            return Double.parseDouble(text.substring(slash + 1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}

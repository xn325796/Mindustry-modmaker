package dev.modmaker.core.validate;

import com.google.gson.JsonElement;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

/**
 * Crafter output mistakes the game silently punishes: outputItem is ignored once outputItems is
 * set, and a liquid output backs up into a full stop unless ignoreLiquidFullness is opted in.
 */
final class CrafterOutputCheck implements ProjectCheck {

    @Override
    public String id() {
        return "crafterOutputs";
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
            if (record.fields.containsKey("outputItem")
                && record.fields.containsKey("outputItems")) {
                context.report(Issue.warningOn(record.id, "validate.crafter.outputItemIgnored",
                    record.name));
            }
            JsonElement ignoreFull = record.fields.get("ignoreLiquidFullness");
            boolean ignoresFullness = ignoreFull != null && ignoreFull.isJsonPrimitive()
                && ignoreFull.getAsBoolean();
            // A singular outputLiquid is copied into the outputLiquids array at init
            // (GenericCrafter.java:125-127) and the stall decision then reads only that array
            // (GenericCrafter.java:214-229), so both spellings mean "this block outputs liquid".
            if ((record.fields.containsKey("outputLiquid")
                || record.fields.containsKey("outputLiquids")) && !ignoresFullness) {
                context.report(Issue.warningOn(record.id,
                    "validate.crafter.liquidFullnessUnset", record.name));
            }
        }
    }
}

package dev.modmaker.core.validate;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drills mine nothing above their tier, and an item whose hardness tops every available drill
 * (the mod's own plus the vanilla maximum) can never be mined by anyone.
 */
final class DrillTierCheck implements ProjectCheck {

    /** Strongest vanilla drill tier (Blocks.java tops out at 7); mod drills may exceed it. */
    private static final int MAX_VANILLA_DRILL_TIER = 7;

    @Override
    public String id() {
        return "drillTiers";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        Map<String, ContentRecord> items = new LinkedHashMap<>();
        for (ContentRecord record : project.contents) {
            if (record.type != ContentType.item || record.isInline()) {
                continue;
            }
            Double hardness = CheckSupport.number(record.fields.get("hardness"));
            if (hardness != null && hardness > 0) {
                items.put(record.name, record);
            }
        }
        List<ContentRecord> drills = new ArrayList<>();
        for (ContentRecord record : project.contents) {
            if (record.type != ContentType.block || record.isInline()) {
                continue;
            }
            String className = context.registry().resolveClass(record.type, record.className());
            if (className != null && CheckSupport.isA(context.registry(), className, "Drill")) {
                drills.add(record);
            }
        }
        for (ContentRecord drill : drills) {
            Double tier = CheckSupport.number(drill.fields.get("tier"));
            if (tier == null) {
                context.report(Issue.warningOn(drill.id, "validate.drill.noTier", drill.name));
                continue;
            }
            for (ContentRecord item : items.values()) {
                Double hardness = CheckSupport.number(item.fields.get("hardness"));
                if (hardness != null && hardness > tier) {
                    context.report(Issue.warningOn(drill.id, "validate.drill.tierTooLow", drill.name,
                        (int) Math.ceil(tier), item.name, (int) Math.ceil(hardness)));
                }
            }
        }
        double bestModTier = 0;
        for (ContentRecord drill : drills) {
            Double tier = CheckSupport.number(drill.fields.get("tier"));
            if (tier != null && tier > bestModTier) {
                bestModTier = tier;
            }
        }
        for (ContentRecord item : items.values()) {
            Double hardness = CheckSupport.number(item.fields.get("hardness"));
            if (hardness != null && hardness > Math.max(bestModTier, MAX_VANILLA_DRILL_TIER)) {
                context.report(Issue.warningOn(item.id, "validate.item.unmineable",
                    item.name, (int) Math.ceil(hardness), MAX_VANILLA_DRILL_TIER));
            }
        }
    }
}

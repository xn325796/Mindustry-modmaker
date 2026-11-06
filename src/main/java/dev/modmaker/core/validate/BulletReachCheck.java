package dev.modmaker.core.validate;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

/**
 * A turret bullet must cover the turret's declared range: bullet reach is speed x lifetime
 * (pixels), so a short pair means targets at the edge of the range circle are never hit.
 */
final class BulletReachCheck implements ProjectCheck {

    @Override
    public String id() {
        return "bulletReach";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        for (ContentRecord record : project.contents) {
            if (record.type != ContentType.bullet || !record.isInline()) {
                continue;
            }
            ContentRecord host = project.contentById(record.inlineOwnerId);
            Double range = host == null ? null : CheckSupport.number(host.fields.get("range"));
            Double speed = CheckSupport.number(record.fields.get("speed"));
            Double lifetime = CheckSupport.number(record.fields.get("lifetime"));
            if (range == null || speed == null || lifetime == null || range <= 0) {
                continue;
            }
            double reach = speed * lifetime;
            if (reach < range * 0.9) {
                context.report(Issue.warningOn(record.id, "validate.bullet.reachShort", record.name,
                    host.type, host.name, Math.round(reach / 8), CheckSupport.trim(reach),
                    CheckSupport.trim(range)));
            } else if (reach > range * 3) {
                context.report(Issue.warningOn(record.id, "validate.bullet.reachLong", record.name,
                    host.type, host.name, Math.round(reach / 8), CheckSupport.trim(reach),
                    CheckSupport.trim(range)));
            }
        }
    }
}

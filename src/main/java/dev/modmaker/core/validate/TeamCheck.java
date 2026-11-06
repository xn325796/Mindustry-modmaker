package dev.modmaker.core.validate;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

import java.util.List;

/**
 * A content/teams record must name a vanilla team. The parser throws "Team field missing." when
 * the key is absent (ContentParser.java:866-870) and "Unknown team" when the value is neither one
 * of the base team names nor an id in 0..255 (ContentParser.java:386-398), so both are errors:
 * the file does not load at all.
 */
final class TeamCheck implements ProjectCheck {

    /**
     * Team names a string "team" value may use: exactly {@code Team.baseTeams}, which the constructor
     * fills for every team whose id is below 6 (Team.java:30, 32-41, 67). Spelled out rather than read
     * from {@link dev.modmaker.core.schema.SchemaRegistry} because the registry's team list also
     * carries {@code neoplastic}: that team has id 6 (Team.java:41), so it is only reachable by id,
     * and the parser throws "Unknown team" for the string (ContentParser.java:388-389).
     */
    private static final List<String> VANILLA_TEAM_NAMES =
        List.of("derelict", "sharded", "crux", "malis", "green", "blue");

    /** Team.all.length: ids 0..255 resolve, anything else throws (Team.java:28, ContentParser.java:392). */
    private static final int TEAM_IDS = 256;

    @Override
    public String id() {
        return "teams";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        for (ContentRecord record : project.contents) {
            if (record.type != ContentType.team || record.isInline()) {
                continue;
            }
            JsonElement team = record.fields.get("team");
            if (team == null) {
                context.report(Issue.errorOn(record.id, "validate.team.missingField", record.name));
                continue;
            }
            if (!team.isJsonPrimitive()) {
                context.report(Issue.errorOn(record.id, "validate.team.notPrimitive", record.name));
                continue;
            }
            JsonPrimitive value = team.getAsJsonPrimitive();
            if (value.isNumber()) {
                int id = value.getAsInt();
                if (id < 0 || id >= TEAM_IDS) {
                    context.report(Issue.errorOn(record.id, "validate.team.idOutOfRange",
                        record.name, id, TEAM_IDS - 1));
                }
            } else if (!VANILLA_TEAM_NAMES.contains(value.getAsString())) {
                context.report(Issue.errorOn(record.id, "validate.team.badName",
                    record.name, value.getAsString(), String.join(", ", VANILLA_TEAM_NAMES)));
            }
        }
    }
}

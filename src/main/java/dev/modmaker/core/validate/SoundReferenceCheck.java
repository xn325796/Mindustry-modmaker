package dev.modmaker.core.validate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.modmaker.core.model.AssetRef;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Sound assets must be real OGG files, and references never carry the file extension. */
final class SoundReferenceCheck implements ProjectCheck {

    /** Audio extensions the game's data loader takes under sounds/ (DataAssetType.sound). */
    private static final Set<String> SOUND_EXTENSIONS = Set.of("mp3", "ogg");

    @Override
    public String id() {
        return "sounds";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        for (AssetRef asset : project.assets) {
            if (!asset.path.startsWith("sounds/")) {
                continue;
            }
            String fileName = asset.path.substring(asset.path.lastIndexOf('/') + 1);
            int dot = fileName.lastIndexOf('.');
            String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!SOUND_EXTENSIONS.contains(extension)) {
                context.report(Issue.warning("validate.sound.badExtension", asset.path, extension,
                    String.join(" or ", SOUND_EXTENSIONS)));
            }
        }
        for (ContentRecord record : project.contents) {
            checkFields(context, record.fields, record);
        }
    }

    private void checkFields(ValidationContext context, Map<String, JsonElement> fields,
        ContentRecord record) {
        for (Map.Entry<String, JsonElement> entry : fields.entrySet()) {
            JsonElement value = entry.getValue();
            checkField(context, entry.getKey(), value, record);
            // Recurse so weapon arrays and other nested objects keep being checked.
            if (value.isJsonObject()) {
                checkFields(context, value.getAsJsonObject(), record);
            } else if (value.isJsonArray()) {
                for (JsonElement element : value.getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        checkFields(context, element.getAsJsonObject(), record);
                    }
                }
            }
        }
    }

    private void checkFields(ValidationContext context, JsonObject object, ContentRecord record) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            checkField(context, entry.getKey(), entry.getValue(), record);
        }
    }

    private void checkField(ValidationContext context, String key, JsonElement value,
        ContentRecord record) {
        if (key.endsWith("Sound") && value.isJsonPrimitive()
            && value.getAsJsonPrimitive().isString()) {
            String name = value.getAsString();
            if (name.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                context.report(Issue.warningOn(record.id,
                    "validate.sound.referenceCarriesExtension",
                    record.type, record.name, key, name,
                    name.substring(0, name.lastIndexOf('.'))));
            }
        }
    }
}

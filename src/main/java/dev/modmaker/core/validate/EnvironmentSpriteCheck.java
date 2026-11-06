package dev.modmaker.core.validate;

import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.fmt.NameRules;
import dev.modmaker.core.fmt.SpriteRegions;
import dev.modmaker.core.model.AssetRef;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;

import java.util.Set;

/**
 * Environment blocks (floors, ores, walls, props) draw terrain, so they need a sprite like any
 * block - see {@link SpriteRegions#matchesFamily} for the variant and part conventions the game
 * itself uses. The folder matters for the map editor's palette rather than for rendering
 * (Mods.java files a sprite under the environment page by its path), which is what the second
 * warning is about.
 */
final class EnvironmentSpriteCheck implements ProjectCheck {

    @Override
    public String id() {
        return "environmentSprites";
    }

    @Override
    public void check(ValidationContext context) {
        ModProject project = context.project();
        String internal = context.internalName();
        Set<String> regions = context.spriteRegions();
        if (regions.isEmpty()) {
            // No sprite assets at all: the project does not use sprites yet, so nagging about each
            // terrain block would be noise. Same stance as the block sprite check.
            return;
        }
        for (ContentRecord record : project.contents) {
            if (record.type != ContentType.block || record.patch || record.isInline()) {
                continue;
            }
            String className = context.registry().resolveClass(record.type, record.className());
            if (className == null || !CheckSupport.isEnvironmentClass(context.registry(), className)) {
                continue;
            }
            String region = SpriteRegions.expectedRegion(record, internal);
            if (!SpriteRegions.hasSprite(regions, region)) {
                context.report(Issue.warningOn(record.id, "validate.environment.noSprite",
                    record.name, record.name, record.name));
            } else if (!filesEnvironmentSprite(project, internal, region)) {
                context.report(Issue.warningOn(record.id, "validate.environment.wrongFolder",
                    record.name));
            }
        }
    }

    /**
     * Whether any of the record's sprites is filed under the folder the map editor reads. The game
     * decides that with two substring tests, without a trailing slash and for both sprite trees
     * (Mods.java:445-452, which packs them at Mods.java:168-174), so "sprites/blocks/environmental/"
     * and a sprites-override copy count as well.
     */
    private static boolean filesEnvironmentSprite(ModProject project, String internal, String region) {
        for (AssetRef asset : project.assets) {
            boolean override = asset.path.startsWith("sprites-override/");
            if (!override && !asset.path.startsWith("sprites/")) {
                continue;
            }
            String fileName = asset.path.substring(asset.path.lastIndexOf('/') + 1);
            if (!fileName.endsWith(".png")) {
                continue;
            }
            String candidate = NameRules.spriteRegion(internal,
                fileName.substring(0, fileName.length() - 4), !override);
            if (SpriteRegions.matchesFamily(region, candidate)
                && (asset.path.contains("sprites/blocks/environment")
                    || asset.path.contains("sprites-override/blocks/environment"))) {
                return true;
            }
        }
        return false;
    }
}

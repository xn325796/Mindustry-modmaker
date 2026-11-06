package dev.modmaker.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.json.Json;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-undo-step contract for reference rewrites: renaming or deleting content updates every
 * holder, and undo/redo restores the exact field trees, names, bundles and dirty flags.
 */
class ReferenceRewriteControllerTest {

    private static SchemaRegistry registry;
    private ProjectController controller;
    private Path root;

    @BeforeAll
    static void loadRegistry() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.exists(Path.of("schemas", "fields.json")), "run schemaBootstrap first");
        registry = SchemaRegistry.loadDefault();
    }

    private ModProject newProject() throws IOException {
        root = Files.createTempDirectory("mm-ref-rewrite");
        controller = new ProjectController(registry);
        return controller.createProject("test-mod", root);
    }

    private static JsonArray requirements(String item, int amount) {
        JsonArray array = new JsonArray();
        JsonObject stack = new JsonObject();
        stack.addProperty("item", item);
        stack.addProperty("amount", amount);
        array.add(stack);
        return array;
    }

    @Test
    void renameUpdatesEveryHolderAndUndoRestoresThem() throws IOException {
        ModProject project = newProject();
        ContentRecord item = controller.addContent(ContentType.item);
        ContentRecord block = controller.addContent(ContentType.block);
        String oldName = item.name;

        JsonArray requirements = requirements(oldName, 7);
        requirements.add(new JsonPrimitive("test-mod-" + oldName + "/3"));
        controller.setField(block, "requirements", requirements);

        JsonObject objective = new JsonObject();
        objective.addProperty("type", "Research");
        objective.addProperty("content", oldName);
        objective.addProperty("note", "keep me");
        JsonArray objectives = new JsonArray();
        objectives.add(objective);
        JsonObject research = new JsonObject();
        research.addProperty("parent", oldName);
        research.add("objectives", objectives);
        research.addProperty("requiresUnlock", true);
        controller.setField(block, "research", research);

        JsonObject ammo = new JsonObject();
        JsonObject ammoEntry = new JsonObject();
        ammoEntry.addProperty("type", "BasicBulletType");
        ammo.add(oldName, ammoEntry);
        controller.setField(block, "ammoTypes", ammo);

        JsonObject consumes = new JsonObject();
        JsonArray items = new JsonArray();
        items.add(new JsonPrimitive(oldName + "/5"));
        consumes.add("items", items);
        controller.setField(block, "consumes", consumes);

        project.meta.contentOrder.add(oldName);
        String oldKey = controller.stringKey(item, "name");
        controller.setString(oldKey, "en", "Old Item");
        controller.save();
        assertFalse(controller.isDirty(), "test setup: a saved project is clean");

        controller.rename(item, "steel");

        assertEquals("steel", item.name);
        assertEquals("steel", block.fields.get("requirements").getAsJsonArray()
            .get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(7, block.fields.get("requirements").getAsJsonArray()
            .get(0).getAsJsonObject().get("amount").getAsInt());
        assertEquals("test-mod-steel/3", block.fields.get("requirements").getAsJsonArray()
            .get(1).getAsString());
        JsonObject rewrittenResearch = block.fields.get("research").getAsJsonObject();
        assertEquals("steel", rewrittenResearch.get("parent").getAsString());
        assertEquals("steel", rewrittenResearch.getAsJsonArray("objectives").get(0)
            .getAsJsonObject().get("content").getAsString());
        assertEquals("keep me", rewrittenResearch.getAsJsonArray("objectives").get(0)
            .getAsJsonObject().get("note").getAsString());
        assertTrue(rewrittenResearch.get("requiresUnlock").getAsBoolean());
        assertTrue(block.fields.get("ammoTypes").getAsJsonObject().has("steel"),
            "the real ammoTypes map key follows the rename");
        assertEquals("steel/5", block.fields.get("consumes").getAsJsonObject()
            .getAsJsonArray("items").get(0).getAsString());
        assertEquals(List.of("steel"), project.meta.contentOrder);
        assertEquals("Old Item", project.strings.get(controller.stringKey(item, "name"), "en"));
        assertTrue(controller.isDirty(), "the rewrite is unsaved work");

        controller.undo();
        assertEquals(oldName, item.name);
        assertEquals(oldName, block.fields.get("requirements").getAsJsonArray()
            .get(0).getAsJsonObject().get("item").getAsString());
        assertEquals("test-mod-" + oldName + "/3", block.fields.get("requirements")
            .getAsJsonArray().get(1).getAsString());
        assertEquals(oldName, block.fields.get("research").getAsJsonObject()
            .get("parent").getAsString());
        assertEquals(oldName, block.fields.get("research").getAsJsonObject()
            .getAsJsonArray("objectives").get(0).getAsJsonObject().get("content")
            .getAsString());
        assertTrue(block.fields.get("ammoTypes").getAsJsonObject().has(oldName),
            "undo restores the original ammo map key");
        assertEquals(oldName + "/5", block.fields.get("consumes").getAsJsonObject()
            .getAsJsonArray("items").get(0).getAsString());
        assertEquals(List.of(oldName), project.meta.contentOrder);
        assertEquals("Old Item", project.strings.get(oldKey, "en"),
            "the bundle key migrates back");
        assertFalse(controller.isDirty(), "undo leaves the saved project clean");

        controller.redo();
        assertEquals("steel", item.name);
        assertEquals("steel", block.fields.get("research").getAsJsonObject()
            .get("parent").getAsString());
        assertEquals(List.of("steel"), project.meta.contentOrder);
        assertTrue(controller.isDirty(), "redo is unsaved work again");
    }

    @Test
    void deleteRemovesReferencesAndUndoRestoresTheWholeGraph() throws IOException {
        ModProject project = newProject();
        ContentRecord item = controller.addContent(ContentType.item);
        ContentRecord block = controller.addContent(ContentType.block);
        String name = item.name;

        controller.setField(block, "requirements", requirements(name, 4));
        JsonObject objective = new JsonObject();
        objective.addProperty("type", "Research");
        objective.addProperty("content", name);
        JsonArray objectives = new JsonArray();
        objectives.add(objective);
        JsonObject research = new JsonObject();
        research.addProperty("parent", name);
        research.add("objectives", objectives);
        controller.setField(block, "research", research);
        JsonObject ammo = new JsonObject();
        JsonObject ammoEntry = new JsonObject();
        ammoEntry.addProperty("type", "BasicBulletType");
        ammo.add(name, ammoEntry);
        controller.setField(block, "ammoTypes", ammo);
        project.meta.contentOrder.add(name);
        controller.save();
        assertFalse(controller.isDirty(), "test setup: a saved project is clean");

        controller.deleteContent(item);

        assertFalse(controller.project().contents.contains(item));
        assertFalse(block.fields.containsKey("requirements"));
        assertFalse(block.fields.containsKey("research"),
            "the research object held nothing but references to the deleted item");
        assertFalse(block.fields.containsKey("ammoTypes"));
        assertTrue(project.meta.contentOrder.isEmpty());
        assertTrue(controller.isDirty());

        controller.undo();
        assertTrue(controller.project().contents.contains(item));
        assertEquals(name, block.fields.get("requirements").getAsJsonArray()
            .get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(name, block.fields.get("research").getAsJsonObject()
            .get("parent").getAsString());
        assertEquals(name, block.fields.get("research").getAsJsonObject()
            .getAsJsonArray("objectives").get(0).getAsJsonObject().get("content")
            .getAsString());
        assertTrue(block.fields.get("ammoTypes").getAsJsonObject().has(name),
            "undo restores the real ammo map entry");
        assertEquals(List.of(name), project.meta.contentOrder);
        assertFalse(controller.isDirty(), "undo restores the saved state, flags included");

        controller.redo();
        assertFalse(controller.project().contents.contains(item));
        assertFalse(block.fields.containsKey("requirements"));
        assertTrue(project.meta.contentOrder.isEmpty());
    }

    @Test
    void batchDeleteCleansReferencesBetweenRecordsInTheBatch() throws IOException {
        ModProject project = newProject();
        ContentRecord item = controller.addContent(ContentType.item);
        ContentRecord block = controller.addContent(ContentType.block);
        controller.setField(block, "requirements", requirements(item.name, 2));
        project.meta.contentOrder.add(item.name);
        controller.save();

        controller.deleteContents(List.of(item, block));
        assertTrue(controller.project().contents.isEmpty());
        assertTrue(project.meta.contentOrder.isEmpty());

        controller.undo();
        assertEquals(2, controller.project().contents.size());
        assertEquals(item.name, block.fields.get("requirements").getAsJsonArray()
            .get(0).getAsJsonObject().get("item").getAsString(),
            "the host's own reference comes back with the batch");
        assertEquals(List.of(item.name), project.meta.contentOrder);
        assertFalse(controller.isDirty());

        controller.redo();
        assertTrue(controller.project().contents.isEmpty());
    }

    @Test
    void deletingContentClearsEveryManagedBundleFieldAndUndoRestoresThem() throws IOException {
        ModProject project = newProject();
        ContentRecord record = controller.addContent(ContentType.item);
        for (String field : dev.modmaker.core.fmt.NameRules.BUNDLE_FIELDS) {
            controller.setString(controller.stringKey(record, field), "en", field + "-en");
            controller.setString(controller.stringKey(record, field), "zh_CN", field + "-zh");
        }
        controller.save();
        var keys = new java.util.LinkedHashMap<String, String>();
        for (String field : dev.modmaker.core.fmt.NameRules.BUNDLE_FIELDS) {
            keys.put(field, controller.stringKey(record, field));
        }

        controller.deleteContent(record);
        for (String key : keys.values()) {
            assertNull(project.strings.get(key, "en"), "deleted content leaves no bundle line: " + key);
            assertNull(project.strings.get(key, "zh_CN"));
        }
        assertTrue(project.strings.isBundleDirty("en"));
        assertTrue(project.strings.isBundleDirty("zh_CN"));

        controller.undo();
        for (String field : dev.modmaker.core.fmt.NameRules.BUNDLE_FIELDS) {
            assertEquals(field + "-en", project.strings.get(keys.get(field), "en"));
            assertEquals(field + "-zh", project.strings.get(keys.get(field), "zh_CN"));
        }

        controller.redo();
        for (String key : keys.values()) {
            assertNull(project.strings.get(key, "en"));
        }
    }

    @Test
    void renamingAndDeletingAnAmmoItemUpdatesTheInlineBulletOnSave() throws IOException {
        ModProject project = newProject();
        ContentRecord item = controller.addContent(ContentType.item);
        ContentRecord block = controller.addContent(ContentType.block);
        String oldName = item.name;

        block.fields.put("type", new JsonPrimitive("ItemTurret"));
        JsonObject bulletFields = new JsonObject();
        bulletFields.addProperty("type", "BasicBulletType");
        bulletFields.addProperty("damage", 10);
        JsonObject ammo = new JsonObject();
        ammo.add(oldName, bulletFields.deepCopy());
        block.fields.put("ammoTypes", ammo);

        ContentRecord inline = new ContentRecord();
        inline.id = "inline-" + oldName;
        inline.type = ContentType.bullet;
        inline.name = oldName;
        inline.inlineOwnerId = block.id;
        inline.inlinePath = "ammoTypes." + oldName;
        bulletFields.entrySet().forEach(entry -> inline.fields.put(entry.getKey(), entry.getValue()));
        project.contents.add(inline);
        controller.save();

        controller.rename(item, "steel");
        assertEquals("ammoTypes.steel", inline.inlinePath);
        controller.save();
        JsonObject renamed = readBlock(block);
        assertTrue(renamed.getAsJsonObject("ammoTypes").has("steel"));
        assertFalse(renamed.getAsJsonObject("ammoTypes").has(oldName),
            "the stale inline path must not be re-inserted on save");

        controller.undo();
        controller.save();
        JsonObject undone = readBlock(block);
        assertTrue(undone.getAsJsonObject("ammoTypes").has(oldName));
        assertFalse(undone.getAsJsonObject("ammoTypes").has("steel"));

        controller.redo();
        controller.save();
        assertEquals("ammoTypes.steel", inline.inlinePath);

        controller.deleteContent(item);
        assertFalse(project.contents.contains(inline));
        controller.save();
        assertFalse(readBlock(block).has("ammoTypes"),
            "deleting the ammo item removes the key and its inline bullet");

        controller.undo();
        assertTrue(project.contents.contains(inline));
        controller.save();
        assertTrue(readBlock(block).getAsJsonObject("ammoTypes").has("steel"));

        controller.redo();
        controller.save();
        assertFalse(readBlock(block).has("ammoTypes"));
    }

    private JsonObject readBlock(ContentRecord block) throws IOException {
        return Json.parseObject(Files.readString(root.resolve(block.rawPath)));
    }

    @Test
    void deletingAPatchCleansItsContentOrderEntry() throws IOException {
        ModProject project = newProject();
        ContentRecord patch = controller.addContent(ContentType.block);
        controller.setPatch(patch, true);
        project.meta.contentOrder.add(patch.name);
        controller.save();

        controller.deleteContent(patch);
        assertTrue(project.meta.contentOrder.isEmpty());

        controller.undo();
        assertEquals(List.of(patch.name), project.meta.contentOrder);

        controller.redo();
        assertTrue(project.meta.contentOrder.isEmpty());
    }

    @Test
    void renamingAPatchIsRefused() throws IOException {
        newProject();
        ContentRecord patch = controller.addContent(ContentType.block);
        controller.setPatch(patch, true);
        String name = patch.name;

        controller.rename(patch, "super-router");
        assertEquals(name, patch.name, "a patch keeps the vanilla name it overrides");
    }
}

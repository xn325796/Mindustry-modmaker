package dev.modmaker.ui;

import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The metadata gate fields the project dialog edits: minGameVersion, author, version, hidden and
 * the legacyCompatible exemption. Each is undoable and marks the meta dirty exactly like a content
 * edit. Pure JVM, no JavaFX toolkit needed.
 */
class ProjectMetaEditTest {

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
        root = Files.createTempDirectory("mm-meta");
        controller = new ProjectController(registry);
        return controller.createProject("test-mod", root);
    }

    @Test
    void setMetaStringMarksDirtyAndIsUndoable() throws IOException {
        newProject();
        controller.setMetaString("displayName", "My Mod");
        assertTrue(controller.isDirty(), "editing metadata marks the project unsaved");
        assertEquals("My Mod", controller.metaString("displayName"));

        controller.undo();
        assertEquals("test-mod", controller.metaString("displayName"), "undo restores the previous value");
        assertFalse(controller.isDirty(), "undoing the only edit leaves the project clean");

        controller.redo();
        assertEquals("My Mod", controller.metaString("displayName"));
        assertTrue(controller.isDirty());
    }

    @Test
    void setMinGameVersionIsUndoableAndRoundTripsThroughSave() throws IOException {
        newProject();
        controller.setMetaString("minGameVersion", "160.7");
        controller.setMetaString("author", "Ann");
        controller.save();

        ModProject reopened = dev.modmaker.core.io.ProjectIo.open(root, registry);
        assertEquals("160.7", reopened.meta.minGameVersion);
        assertEquals("Ann", reopened.meta.author);

        controller.undo(); // reverts "author"
        controller.undo(); // reverts "minGameVersion"
        assertEquals("160", controller.metaString("minGameVersion"));
    }

    @Test
    void setLegacyCompatibleTogglesTheExtraFlagAndIsUndoable() throws IOException {
        newProject();
        assertFalse(controller.project().meta.legacyCompatible());

        controller.setLegacyCompatible(true);
        assertTrue(controller.project().meta.legacyCompatible());
        assertTrue(controller.project().meta.extra.containsKey("legacyCompatible"));

        controller.undo();
        assertFalse(controller.project().meta.legacyCompatible());
        assertFalse(controller.project().meta.extra.containsKey("legacyCompatible"),
            "clearing the flag removes the key so an untouched mod stays verbatim");

        controller.redo();
        assertTrue(controller.project().meta.legacyCompatible());
    }

    @Test
    void setMetaFlagTogglesHiddenAndIsUndoable() throws IOException {
        newProject();
        assertFalse(controller.metaFlag("hidden"));

        controller.setMetaFlag("hidden", true);
        assertTrue(controller.metaFlag("hidden"));
        assertTrue(controller.isDirty());

        controller.undo();
        assertFalse(controller.metaFlag("hidden"));
        assertFalse(controller.isDirty());
    }
}

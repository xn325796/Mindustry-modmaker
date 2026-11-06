package dev.modmaker.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.modmaker.core.fmt.ContentType;
import dev.modmaker.core.io.ProjectIo;
import dev.modmaker.core.model.Board;
import dev.modmaker.core.model.CanvasEdge;
import dev.modmaker.core.model.CanvasNode;
import dev.modmaker.core.model.ContentRecord;
import dev.modmaker.core.model.ModProject;
import dev.modmaker.core.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller-level interaction contract: placing, connecting, disconnecting, moving and deleting
 * - each with working undo and redo. Pure JVM, no JavaFX toolkit needed.
 */
class ProjectControllerInteractionTest {

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
        root = Files.createTempDirectory("mm-interact");
        controller = new ProjectController(registry);
        return controller.createProject("test-mod", root);
    }

    private CanvasNode nodeOf(ContentRecord record) {
        return controller.board().nodes.stream()
            .filter(node -> record.id.equals(node.contentId))
            .findFirst().orElseThrow();
    }

    @Test
    void addContentPlacesNodeAtGivenPosition() throws IOException {
        newProject();
        ContentRecord record = controller.addContent(ContentType.item, 120d, 90d);
        CanvasNode node = nodeOf(record);
        assertEquals(120, node.x, 0.001);
        assertEquals(90, node.y, 0.001);
    }

    @Test
    void connectWritesTheFieldAndAddsTheEdge() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord block = controller.addContent(ContentType.block, 400d, 0d);
        String itemNodeId = nodeOf(item).id;
        String blockNodeId = nodeOf(block).id;

        assertTrue(controller.connect(itemNodeId, blockNodeId, "requirements"));
        JsonArray requirements = block.fields.get("requirements").getAsJsonArray();
        assertEquals(1, requirements.size());
        assertEquals("new-item-1", requirements.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(1, controller.board().edges.size());
        assertTrue(block.dirty, "the target record must be re-serialised on save");

        assertFalse(controller.connect(itemNodeId, blockNodeId, "requirements"), "duplicate is rejected");
    }

    @Test
    void disconnectRemovesFieldAndEdge() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord block = controller.addContent(ContentType.block, 400d, 0d);
        controller.connect(nodeOf(item).id, nodeOf(block).id, "requirements");
        CanvasEdge edge = controller.board().edges.get(0);

        controller.disconnect(edge);
        assertFalse(block.fields.containsKey("requirements"));
        assertTrue(controller.board().edges.isEmpty());

        controller.undo();
        assertTrue(block.fields.containsKey("requirements"), "undo restores the reference");
        assertEquals(1, controller.board().edges.size());
        controller.redo();
        assertFalse(block.fields.containsKey("requirements"));
    }

    @Test
    void disconnectingAnEdgeWhoseTargetRecordIsGoneIsUndoable() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord block = controller.addContent(ContentType.block, 400d, 0d);
        controller.connect(nodeOf(item).id, nodeOf(block).id, "requirements");
        CanvasEdge edge = controller.board().edges.get(0);

        // Stale authoring data: the node that holds the field survives, its binding to a record does
        // not. Dropping the edge here used to bypass the undo stack entirely, so the one delete the
        // user could still trigger was also the one they could not take back.
        nodeOf(block).contentId = null;
        controller.disconnect(edge);
        assertTrue(controller.board().edges.isEmpty());

        controller.undo();
        assertEquals(1, controller.board().edges.size(),
            "removing an edge whose target record is gone must still be undoable");
        controller.redo();
        assertTrue(controller.board().edges.isEmpty());
    }

    @Test
    void rebuildLinksRestoresWhatTheCanvasLostAndUndoesCleanly() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord block = controller.addContent(ContentType.block, 400d, 0d);
        controller.connect(nodeOf(item).id, nodeOf(block).id, "requirements");
        assertEquals(1, controller.board().edges.size());

        // Simulate a stale authoring layer: a canvas written by a build that drew no links.
        controller.board().edges.clear();
        assertFalse(controller.canvasDiff().clean(), "the drift must be visible, not silent");

        assertTrue(controller.rebuildLinks() > 0, "the missing link is restored");
        assertEquals(1, controller.board().edges.size());
        CanvasEdge restored = controller.board().edges.get(0);
        assertEquals(nodeOf(item).id, restored.from, "the arrow leaves the referenced item");
        assertEquals(nodeOf(block).id, restored.to, "and enters the record that requires it");
        assertTrue(controller.canvasDiff().clean(), "after a rebuild the canvas matches the records");

        controller.undo();
        assertTrue(controller.board().edges.isEmpty(), "undo removes the rebuilt link");
        controller.redo();
        assertEquals(1, controller.board().edges.size());
    }

    @Test
    void undoRedoCoversAddMoveAndDelete() throws IOException {
        newProject();
        int recordsBefore = controller.project().contents.size();

        ContentRecord record = controller.addContent(ContentType.item, 10d, 20d);
        CanvasNode node = nodeOf(record);
        assertEquals(recordsBefore + 1, controller.project().contents.size());

        Map<String, javafx.geometry.Point2D> before = new LinkedHashMap<>();
        before.put(node.id, new javafx.geometry.Point2D(10, 20));
        node.x = 300;
        node.y = 400;
        Map<String, double[]> after = new LinkedHashMap<>();
        after.put(node.id, new double[] {300, 400});
        controller.commitMoves(before, after);
        assertEquals(300, node.x, 0.001);

        controller.undo(); // move back
        assertEquals(10, node.x, 0.001);
        assertEquals(20, node.y, 0.001);

        controller.deleteContent(record);
        assertFalse(controller.project().contents.contains(record));
        assertTrue(controller.board().nodes.stream().noneMatch(n -> n.id.equals(node.id)));

        controller.undo(); // undelete
        assertTrue(controller.project().contents.contains(record));
        assertTrue(controller.board().nodes.stream().anyMatch(n -> n.id.equals(node.id)));

        controller.redo(); // delete again
        assertFalse(controller.project().contents.contains(record));

        controller.undo(); // undelete
        controller.undo(); // un-move (already undone above; this un-adds)
        assertEquals(recordsBefore, controller.project().contents.size());
    }

    @Test
    void historySurvivesSaveAndConnectWritesReachDisk() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord block = controller.addContent(ContentType.block, 400d, 0d);
        controller.connect(nodeOf(item).id, nodeOf(block).id, "requirements");
        ProjectIo.save(controller.project(), root);

        controller.undo();
        assertFalse(controller.project().contents.stream()
            .filter(record -> record.type == ContentType.block)
            .findFirst().orElseThrow().fields.containsKey("requirements"));
        ProjectIo.save(controller.project(), root);

        String blockJson = Files.readString(
            root.resolve("content/blocks/new-block-1.json"));
        assertFalse(blockJson.contains("new-item-1"), "the undone reference is gone from disk");
    }

    @Test
    void setFieldIsUndoableAndQuickEditsCoalesce() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        var glow = new com.google.gson.JsonPrimitive(true);
        controller.setField(item, "glow", glow);
        assertTrue(item.fields.get("glow").getAsBoolean());

        controller.undo();
        assertFalse(item.fields.containsKey("glow"), "undo removes the field again");
        controller.redo();
        assertTrue(item.fields.get("glow").getAsBoolean());

        // A second edit of the same field is one more undo step; three rapid edits of one field
        // (a colour picker nudging values) collapse into a single step back to the first value.
        controller.setField(item, "glow", new com.google.gson.JsonPrimitive(false));
        controller.setField(item, "glow", new com.google.gson.JsonPrimitive(true));
        controller.undo();
        assertFalse(item.fields.containsKey("glow"),
            "coalesced edits undo straight back to the original state");
    }

    @Test
    void setStringIsUndoable() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        String key = controller.stringKey(item, "description");
        controller.setString(key, "en", "first");
        assertEquals("first", controller.project().strings.get(key, "en"));

        // Consecutive edits of the same cell coalesce into one undo step, like a typing run.
        controller.setString(key, "en", "second");
        controller.undo();
        assertNull(controller.project().strings.get(key, "en"),
            "one undo steps straight back to the original (absent) value");
        controller.redo();
        assertEquals("second", controller.project().strings.get(key, "en"));
    }

    @Test
    void renameIsUndoable() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        controller.rename(item, "renamed-item");
        assertEquals("renamed-item", item.name);

        controller.undo();
        assertEquals("new-item-1", item.name, "undo restores the original file name");
        controller.redo();
        assertEquals("renamed-item", item.name);
    }

    @Test
    void tidyLayoutIsUndoable() throws IOException {
        newProject();
        ContentRecord first = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord second = controller.addContent(ContentType.block, 500d, 300d);
        double firstX = nodeOf(first).x;
        double firstY = nodeOf(first).y;
        double secondX = nodeOf(second).x;
        double secondY = nodeOf(second).y;

        controller.tidyLayout();
        controller.undo();
        assertEquals(firstX, nodeOf(first).x, 0.001, "undo restores the pre-tidy layout");
        assertEquals(firstY, nodeOf(first).y, 0.001);
        assertEquals(secondX, nodeOf(second).x, 0.001);
        assertEquals(secondY, nodeOf(second).y, 0.001);
        controller.redo();
    }

    @Test
    void addBoardIsUndoable() throws IOException {
        newProject();
        Board original = controller.board();
        Board created = controller.addBoard("Second");
        assertEquals(created, controller.board());

        controller.undo();
        assertEquals(original, controller.board(), "undo leaves the original board in front");
        assertFalse(controller.project().boards.contains(created));
        controller.redo();
        assertEquals(created, controller.board());
    }

    @Test
    void batchDeleteRemovesAndRestoresSeveralRecords() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord block = controller.addContent(ContentType.block, 400d, 0d);
        controller.connect(nodeOf(item).id, nodeOf(block).id, "requirements");
        int recordsBefore = controller.project().contents.size();

        controller.deleteContents(java.util.List.of(item, block));
        assertEquals(recordsBefore - 2, controller.project().contents.size());
        assertTrue(controller.board().edges.isEmpty());

        controller.undo();
        assertEquals(recordsBefore, controller.project().contents.size());
        assertTrue(controller.project().contents.contains(item));
        assertTrue(controller.project().contents.contains(block));
        assertEquals(1, controller.board().edges.size(), "undo restores the edge too");
        assertEquals("new-item-1", block.fields.get("requirements").getAsJsonArray()
            .get(0).getAsJsonObject().get("item").getAsString());

        controller.redo();
        assertEquals(recordsBefore - 2, controller.project().contents.size());
    }

    @Test
    void dirtyFlagTracksEditsAndSaveClearsIt() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        assertTrue(controller.isDirty(), "adding content leaves unsaved work");
        controller.save();
        assertFalse(controller.isDirty(), "saving clears the dirty flag");

        controller.setField(item, "glow", new com.google.gson.JsonPrimitive(true));
        assertTrue(controller.isDirty(), "a field edit marks the project dirty");
        controller.save();
        assertFalse(controller.isDirty());

        controller.setString(controller.stringKey(item, "description"), "en", "text");
        assertTrue(controller.isDirty(), "a bundle edit marks the project dirty");
        controller.undo();
        controller.save();
        assertFalse(controller.isDirty(), "saving the undone state clears the flag again");
    }

    @Test
    void importRefusesToOverwriteAnExistingProjectWhenNotApproved() throws IOException {
        Path source = Files.createTempDirectory("mm-import-src");
        Files.createDirectories(source.resolve("content/blocks"));
        Files.writeString(source.resolve("mod.json"),
            "{\"name\":\"overwritetest\",\"displayName\":\"Overwrite Test\","
                + "\"author\":\"t\",\"description\":\"d\",\"version\":\"1.0\",\"minGameVersion\":\"126\"}");

        Path destination = Files.createTempDirectory("mm-import-dst");
        Files.createDirectories(destination.resolve("overwritetest"));
        Files.writeString(destination.resolve("overwritetest/precious.txt"), "keep me");

        ProjectController refusing = new ProjectController(registry);
        refusing.setOverwriteApprover(path -> false);
        assertThrows(java.io.IOException.class, () -> refusing.importPackage(source, destination));
        assertEquals("keep me", Files.readString(
            destination.resolve("overwritetest/precious.txt")),
            "the existing project folder must not be touched when the import is refused");

        ProjectController approving = new ProjectController(registry);
        approving.setOverwriteApprover(path -> true);
        assertDoesNotThrow(() -> approving.importPackage(source, destination));
    }

    @Test
    void anUnlistableDestinationIsPutToTheApproverInsteadOfBeingOverwritten() throws IOException {
        Path source = Files.createTempDirectory("mm-locked-src");
        Files.writeString(source.resolve("mod.json"),
            "{\"name\":\"lockedtest\",\"displayName\":\"Locked Test\",\"author\":\"t\","
                + "\"description\":\"d\",\"version\":\"1.0\",\"minGameVersion\":\"126\"}");

        Path destination = Files.createTempDirectory("mm-locked-dst");
        Path modFolder = destination.resolve("lockedtest");
        Files.createDirectories(modFolder);
        Files.writeString(modFolder.resolve("precious.txt"), "keep me");

        // A folder the process cannot list used to be reported as "empty", which skipped the
        // overwrite confirmation entirely and let the import write into a folder full of files.
        java.nio.file.attribute.AclFileAttributeView view = Files.getFileAttributeView(
            modFolder, java.nio.file.attribute.AclFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(view != null,
            "this filesystem has no ACL view to revoke read access with");
        List<java.nio.file.attribute.AclEntry> original = new java.util.ArrayList<>(view.getAcl());
        java.nio.file.attribute.AclEntry deny = java.nio.file.attribute.AclEntry.newBuilder()
            .setType(java.nio.file.attribute.AclEntryType.DENY)
            .setPrincipal(view.getOwner())
            .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA,
                java.nio.file.attribute.AclEntryPermission.LIST_DIRECTORY)
            .build();
        List<java.nio.file.attribute.AclEntry> denied = new java.util.ArrayList<>();
        denied.add(deny);
        denied.addAll(original);

        boolean consulted;
        try {
            view.setAcl(denied);
            org.junit.jupiter.api.Assumptions.assumeTrue(!canList(modFolder),
                "read access could not be revoked on this machine");
            java.util.concurrent.atomic.AtomicBoolean asked =
                new java.util.concurrent.atomic.AtomicBoolean();
            ProjectController locked = new ProjectController(registry);
            locked.setOverwriteApprover(path -> {
                asked.set(true);
                return false;
            });
            assertThrows(IOException.class, () -> locked.importPackage(source, destination));
            consulted = asked.get();
        } finally {
            view.setAcl(original);
        }

        assertTrue(consulted,
            "an unlistable destination must be put to the approver, not silently overwritten");
        assertEquals("keep me", Files.readString(modFolder.resolve("precious.txt")),
            "a refused import must leave the existing folder untouched");
    }

    private static boolean canList(Path folder) {
        try (var entries = Files.list(folder)) {
            entries.findAny();
            return true;
        } catch (IOException notListable) {
            return false;
        }
    }

    @Test
    void importRefusesToUnpackAFolderIntoItsOwnSubtree() throws IOException {
        // A folder import whose destination is the mod folder itself used to unpack
        // <mod>/<internalName>/ inside the mod - a silent self-copy that doubled the tree and broke
        // the golden round-trip tests reading that folder as their fixture. The overlap is refused
        // before anything is written.
        Path source = Files.createTempDirectory("mm-selfnest-src");
        Files.writeString(source.resolve("mod.json"),
            "{\"name\":\"selfnest\",\"displayName\":\"Self Nest\",\"author\":\"t\","
                + "\"description\":\"d\",\"version\":\"1.0\",\"minGameVersion\":\"126\"}");

        ProjectController importer = new ProjectController(registry);
        assertThrows(java.io.IOException.class, () -> importer.importPackage(source, source),
            "importing a folder into itself must be refused");
        assertThrows(java.io.IOException.class,
            () -> importer.importPackage(source, source.resolve("sub")),
            "importing a folder into its own subtree must be refused");
        assertFalse(Files.exists(source.resolve("selfnest")),
            "a refused import must not leave a nested copy behind");
    }

    @Test
    void carrierRebindIsUndoable() throws IOException {
        newProject();
        ContentRecord first = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord second = controller.addContent(ContentType.item, 300d, 0d);
        String carrierNodeId = nodeOf(first).id;

        controller.rebindCarrier(carrierNodeId, second.id, null);
        assertEquals(second.id, nodeById(carrierNodeId).contentId,
            "the carrier now presents the second record");
        assertEquals(0, countItemNodes(first), "the first record has no card of its own any more");

        controller.undo();
        assertEquals(first.id, nodeById(carrierNodeId).contentId, "undo restores the binding");
        controller.redo();
        assertEquals(second.id, nodeById(carrierNodeId).contentId);

        // rebinding onto a vanilla name clears the record binding and stores the reference
        controller.rebindCarrier(carrierNodeId, null, "copper");
        assertNull(nodeById(carrierNodeId).contentId);
        assertEquals("copper", nodeById(carrierNodeId).refName);
        controller.undo();
        assertEquals(second.id, nodeById(carrierNodeId).contentId);
        assertNull(nodeById(carrierNodeId).refName);
    }

    @Test
    void vanillaCarrierConnectWritesTheVanillaName() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord block = controller.addContent(ContentType.block, 400d, 0d);
        String carrierNodeId = nodeOf(item).id;
        String blockNodeId = nodeOf(block).id;
        controller.rebindCarrier(carrierNodeId, null, "copper");

        assertTrue(controller.connect(carrierNodeId, blockNodeId, "requirements"));
        var requirements = block.fields.get("requirements").getAsJsonArray();
        assertEquals("copper", requirements.get(0).getAsJsonObject().get("item").getAsString(),
            "a vanilla-bound carrier writes the vanilla name, not a record name");
        assertEquals("copper", controller.board().edges.get(0).label);

        controller.disconnect(controller.board().edges.get(0));
        assertFalse(block.fields.containsKey("requirements"));
    }

    @Test
    void detachRemovesCarrierNodesButKeepsRecords() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord block = controller.addContent(ContentType.block, 400d, 0d);
        String carrierNodeId = nodeOf(item).id;
        String blockNodeId = nodeOf(block).id;
        controller.connect(carrierNodeId, blockNodeId, "requirements");
        int recordsBefore = controller.project().contents.size();

        controller.detachNodes(List.of(carrierNodeId));
        assertEquals(recordsBefore, controller.project().contents.size(),
            "the record stays registered");
        assertTrue(controller.project().contents.contains(item));
        assertTrue(controller.board().nodes.stream()
            .noneMatch(node -> node.id.equals(carrierNodeId)), "the carrier node is gone");
        assertTrue(controller.board().edges.isEmpty(), "its edges go with it");

        controller.undo();
        assertTrue(controller.board().nodes.stream()
            .anyMatch(node -> node.id.equals(carrierNodeId)), "undo restores the carrier node");
        assertEquals(1, controller.board().edges.size());
        controller.redo();
        assertTrue(controller.board().nodes.stream()
            .noneMatch(node -> node.id.equals(carrierNodeId)));
    }

    private CanvasNode nodeById(String nodeId) {
        return controller.board().nodes.stream()
            .filter(node -> node.id.equals(nodeId))
            .findFirst().orElseThrow();
    }

    private long countItemNodes(ContentRecord record) {
        return controller.board().nodes.stream()
            .filter(node -> record.id.equals(node.contentId))
            .count();
    }

    // --- regression tests for the audit fixes ---------------------------------------------------

    @Test
    void addingABoardMarksTheProjectDirty() throws IOException {
        newProject();
        controller.save();
        assertFalse(controller.isDirty(), "test setup: freshly saved project is clean");

        controller.addBoard("extra");
        assertTrue(controller.isDirty(),
            "a new board must be written on the next save (or the exit guard lets it vanish)");
    }

    @Test
    void undoActsOnTheBoardTheCommandWasCreatedOn() throws IOException {
        newProject();
        Board first = controller.board();
        Board second = controller.addBoard("second");

        ContentRecord record = controller.addContent(ContentType.item, 10d, 10d);
        assertTrue(controller.board().nodes.stream().anyMatch(node -> record.id.equals(node.contentId)));

        controller.setBoard(first);
        controller.undo();
        // The add-content command must remove the node from "second" (where it was created),
        // not from the board that happens to be active when undo runs.
        assertTrue(second.nodes.stream().noneMatch(node -> record.id.equals(node.contentId)),
            "the node is removed from its own board");
        assertTrue(controller.project().contents.stream()
            .noneMatch(candidate -> candidate.id.equals(record.id)));
        assertTrue(first.nodes.stream().noneMatch(node -> record.id.equals(node.contentId)),
            "the active board is untouched");
    }

    @Test
    void deletingContentMarksBundlesDirtyAndUndoRestoresAllLocales() throws IOException {
        newProject();
        ContentRecord record = controller.addContent(ContentType.item, 0d, 0d);
        String key = controller.stringKey(record, "name");
        controller.setString(key, "zh_CN", "新物品一");

        controller.deleteContent(record);
        assertTrue(controller.project().strings.isBundleDirty("en"),
            "the deleted record's bundle line must be rewritten");
        assertTrue(controller.project().strings.isBundleDirty("zh_CN"));

        controller.undo();
        assertEquals("新物品一", controller.project().strings.get(key, "zh_CN"),
            "undo restores every locale, not just en");
        assertTrue(controller.project().strings.isBundleDirty("zh_CN"),
            "the restored value must reach the bundle on the next save");
        assertTrue(controller.project().contents.stream()
            .anyMatch(candidate -> candidate.id.equals(record.id)));
    }

    @Test
    void renameMigratesManagedBundleKeys() throws IOException {
        newProject();
        ContentRecord record = controller.addContent(ContentType.item, 0d, 0d);
        String oldKey = controller.stringKey(record, "name");
        controller.setString(oldKey, "en", "Old Name");
        controller.setString(oldKey, "zh_CN", "旧名");

        controller.rename(record, "steel-ingot");
        String newKey = controller.stringKey(record, "name");
        assertFalse(controller.project().strings.get(newKey, "en").isEmpty(),
            "the en value moved to the new key");
        assertEquals("旧名", controller.project().strings.get(newKey, "zh_CN"));
        assertNull(controller.project().strings.get(oldKey, "en"), "the old key is gone");

        controller.undo();
        assertEquals("Old Name", controller.project().strings.get(oldKey, "en"),
            "undo migrates the keys back");
        assertNull(controller.project().strings.get(newKey, "en"));
    }

    // --- undo must not leave the project dirty (U1) ---------------------------------------------

    @Test
    void undoneFieldEditLeavesTheProjectCleanAndKeepsTheOriginalFileText() throws IOException {
        Path project = Files.createTempDirectory("mm-undo-clean");
        Files.createDirectories(project.resolve("content/blocks"));
        Files.writeString(project.resolve("mod.json"),
            "{\"name\":\"undoclean\",\"displayName\":\"Undo Clean\",\"author\":\"t\","
                + "\"description\":\"d\",\"version\":\"1.0\",\"minGameVersion\":\"126\"}");
        // A hand-written file: the tool writes it back byte for byte while the record is untouched.
        Files.writeString(project.resolve("content/blocks/thing.json"),
            "{\n  // hand-written comment\n  size: 1\n}\n");

        controller = new ProjectController(registry);
        ModProject opened = controller.openProject(project);
        ContentRecord record = opened.contents.stream()
            .filter(candidate -> candidate.type == ContentType.block)
            .findFirst().orElseThrow();
        assertEquals(1, record.fields.get("size").getAsInt(), "the file parsed");
        assertTrue(record.raw.contains("hand-written comment"),
            "test setup: the original text is kept verbatim");
        controller.save();
        assertFalse(controller.isDirty(), "test setup: a saved project is clean");

        controller.setField(record, "size", new com.google.gson.JsonPrimitive(3));
        assertTrue(controller.isDirty(), "the edit is unsaved work");
        controller.undo();
        assertFalse(controller.isDirty(),
            "undo must put the dirty flag back, or the next save rewrites an unchanged file");

        controller.save();
        String text = Files.readString(project.resolve("content/blocks/thing.json"));
        assertTrue(text.contains("hand-written comment"),
            "the untouched file keeps its comment, saw: " + text);
        assertEquals(1, record.fields.get("size").getAsInt(), "the undone value stays undone");
    }

    @Test
    void undoneStringEditLeavesTheProjectClean() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        controller.save();
        assertFalse(controller.isDirty(), "test setup: a saved project is clean");

        controller.setString(controller.stringKey(item, "description"), "en", "text");
        assertTrue(controller.isDirty(), "a bundle edit is unsaved work");
        controller.undo();
        assertFalse(controller.isDirty(), "undoing it must clear the bundle flag again");
    }

    @Test
    void redoMarksTheProjectDirtyAgain() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        controller.save();

        controller.setField(item, "glow", new com.google.gson.JsonPrimitive(true));
        controller.undo();
        assertFalse(controller.isDirty(), "the project is back to the saved state");
        controller.redo();
        assertTrue(controller.isDirty(), "a redone edit has to be written on the next save");
        assertTrue(item.fields.get("glow").getAsBoolean());
    }

    @Test
    void undoingADeleteLeavesBundlesAsCleanAsTheyWere() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        controller.save();
        assertFalse(controller.isDirty(), "test setup: a saved project is clean");

        controller.deleteContent(item);
        assertTrue(controller.isDirty(), "deleting marks the project dirty");
        controller.undo();
        assertFalse(controller.isDirty(), "undoing the delete puts the flags back");
        assertTrue(controller.project().contents.contains(item));
    }

    // --- a click that moves nothing is not an edit (U2) ------------------------------------------

    @Test
    void commitMovesIgnoresAPressAndReleaseAtTheSameSpot() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 10d, 20d);
        CanvasNode node = nodeOf(item);
        controller.save();
        assertFalse(controller.isDirty(), "test setup: a saved project is clean");
        int depth = controller.undoStackDepth();

        Map<String, javafx.geometry.Point2D> before = new LinkedHashMap<>();
        before.put(node.id, new javafx.geometry.Point2D(node.x, node.y));
        Map<String, double[]> after = new LinkedHashMap<>();
        after.put(node.id, new double[] {node.x, node.y});
        controller.commitMoves(before, after);

        assertEquals(depth, controller.undoStackDepth(), "a no-op move must not push an undo step");
        assertFalse(controller.isDirty(), "and it must not dirty the project");
    }

    // --- the patch flag and the raw tree are undoable too (U3) -----------------------------------

    @Test
    void patchToggleIsUndoable() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        controller.save();
        int depth = controller.undoStackDepth();

        controller.setPatch(item, true);
        assertTrue(item.patch);
        assertTrue(controller.isDirty(), "the toggle is unsaved work");
        assertEquals(depth + 1, controller.undoStackDepth(), "the toggle is one undo step");

        controller.undo();
        assertFalse(item.patch, "undo restores the patch flag");
        assertFalse(controller.isDirty(), "and leaves the project as clean as it was");
        controller.redo();
        assertTrue(item.patch);
        assertTrue(controller.isDirty());
    }

    @Test
    void rawTreeApplyIsUndoable() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        controller.setField(item, "health", new com.google.gson.JsonPrimitive(100));
        controller.save();
        int depth = controller.undoStackDepth();

        controller.applyRawTree(item, dev.modmaker.core.json.Json.parseObject("{health: 250, glow: true}"));
        assertEquals(250, item.fields.get("health").getAsInt());
        assertTrue(item.fields.containsKey("glow"));
        assertEquals(depth + 1, controller.undoStackDepth(), "applying raw json is one undo step");

        controller.undo();
        assertEquals(100, item.fields.get("health").getAsInt(), "undo restores the previous tree");
        assertFalse(item.fields.containsKey("glow"));
        controller.redo();
        assertEquals(250, item.fields.get("health").getAsInt());
        assertTrue(item.fields.containsKey("glow"));

        int afterRedo = controller.undoStackDepth();
        controller.applyRawTree(item, item.tree());
        assertEquals(afterRedo, controller.undoStackDepth(), "an unchanged tree is not an undo step");
    }

    // --- board canvas file names must be unique (U8) ---------------------------------------------

    @Test
    void boardFileNamesDoNotCollide() throws IOException {
        newProject();
        Board first = controller.addBoard("Test Board");
        Board second = controller.addBoard("test board");
        Board third = controller.addBoard("第二区");
        Board fourth = controller.addBoard("第三区");

        assertEquals("test-board.canvas", first.file);
        assertFalse(second.file.equals(first.file),
            "names that differ only in case must not share one canvas file");
        assertFalse(third.file.equals(fourth.file),
            "non-ASCII names must not collapse onto one canvas file: " + third.file);
        assertFalse(third.file.equals("-.canvas"), "and must not become '-.canvas'");
        assertTrue(first.file.endsWith(".canvas"));
        assertTrue(second.file.endsWith(".canvas"));
        Set<String> used = new HashSet<>();
        for (Board board : controller.project().boards) {
            assertTrue(used.add(board.file),
                "two boards write the same canvas file: " + board.file);
        }
    }

    // --- validation is shared, not recomputed per pane --------------------------------------------

    @Test
    void validationIsCachedUntilTheProjectChanges() throws IOException {
        newProject();
        var block = controller.addContent(ContentType.block);
        // Make sure the result is non-empty: an unresolved reference is a finding.
        JsonObject reference = new JsonObject();
        reference.addProperty("item", "not-a-real-item-xyz");
        reference.addProperty("amount", 1);
        JsonArray requirements = new JsonArray();
        requirements.add(reference);
        controller.setField(block, "requirements", requirements);

        var first = controller.validateQuietly();
        assertFalse(first.isEmpty(), "precondition: the unresolved reference is reported");
        assertSame(first, controller.validateQuietly(),
            "the inspector, issues pane and both grids must share one result");

        // F5 must be able to force a pass even when a pane already cached one.
        var forced = controller.validate();
        assertNotSame(first, forced, "an explicit validation bypasses the cache");
        assertSame(forced, controller.validateQuietly(), "the forced pass repopulates the cache");

        controller.addContent(ContentType.item);
        var afterEdit = controller.validateQuietly();
        assertNotSame(forced, afterEdit, "a model change invalidates the cached findings");
    }

    @Test
    void deletingHostCascadesInlineChildren() throws IOException {
        newProject();
        ContentRecord host = controller.addContent(ContentType.block, 100d, 100d);
        ContentRecord child = controller.addContent(ContentType.bullet, 200d, 200d);
        child.inlineOwnerId = host.id;
        child.inlinePath = "ammoTypes.copper";

        controller.deleteContent(host);

        long orphans = controller.project().contents.stream()
            .filter(record -> host.id.equals(record.inlineOwnerId)).count();
        assertEquals(0, orphans, "deleting a host must remove its inline children");
        controller.undo();
        assertEquals(2, controller.project().contents.size(), "undo restores host and child");
    }

    @Test
    void tidyLayoutWithoutChangeDoesNotMarkDirty() throws IOException {
        newProject();
        controller.addContent(ContentType.item, 50d, 50d);
        controller.save();
        assertFalse(controller.isDirty(), "precondition: clean after save");

        controller.tidyLayout();
        int depth = controller.undoStackDepth();
        controller.tidyLayout();
        assertEquals(depth, controller.undoStackDepth(),
            "a second tidy with identical positions must not add an undo step");
    }

    // --- structured multi-reference groups -------------------------------------------------------

    @Test
    void connectingItemsAndLiquidsFillsConsumeGroups() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord liquid = controller.addContent(ContentType.liquid, 0d, 200d);
        ContentRecord crafter = controller.addContent(ContentType.block, 400d, 0d);
        crafter.setClassName("GenericCrafter");

        assertTrue(controller.connect(nodeOf(item).id, nodeOf(crafter).id, "consumes"));
        assertTrue(controller.connect(nodeOf(liquid).id, nodeOf(crafter).id, "consumes"));

        var consumes = crafter.fields.get("consumes").getAsJsonArray();
        assertEquals(1, consumes.size(), "one consume group holds the wired item and liquid");
        var group = consumes.get(0).getAsJsonObject();
        assertEquals(item.name, group.getAsJsonArray("items").get(0).getAsJsonObject().get("item").getAsString(),
            "the item lands in the group's items slot");
        assertEquals(liquid.name, group.getAsJsonArray("liquids").get(0).getAsJsonObject().get("liquid").getAsString(),
            "the liquid lands in the group's liquids slot");

        // Disconnecting the item edge strips exactly its stack from the group.
        CanvasEdge itemEdge = controller.board().edges.stream()
            .filter(edge -> nodeOf(item).id.equals(edge.from)).findFirst().orElseThrow();
        controller.disconnect(itemEdge);
        assertFalse(crafter.fields.get("consumes").getAsJsonArray().get(0)
            .getAsJsonObject().has("items"),
            "the emptied items slot is dropped from the group");
        assertEquals(liquid.name, crafter.fields.get("consumes").getAsJsonArray().get(0)
            .getAsJsonObject().getAsJsonArray("liquids").get(0).getAsJsonObject().get("liquid").getAsString(),
            "the liquid survives the item's removal");
    }

    @Test
    void connectingAFactoryThenItemsBuildsUnitRequirements() throws IOException {
        newProject();
        ContentRecord factory = controller.addContent(ContentType.block, 0d, 0d);
        ContentRecord item = controller.addContent(ContentType.item, 0d, 200d);
        ContentRecord unit = controller.addContent(ContentType.unit, 400d, 0d);
        // Units are the exception to "type is a class": the value is a movement kind, and the
        // schema class is always UnitType - drop the placeholder the constructor wrote.
        unit.fields.remove("type");

        assertTrue(controller.connect(nodeOf(factory).id, nodeOf(unit).id, "requirements"));
        var entry = unit.fields.get("requirements").getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(factory.name, entry.get("block").getAsString(),
            "wiring a factory block creates the UnitReq entry");

        assertTrue(controller.connect(nodeOf(item).id, nodeOf(unit).id, "requirements"));
        assertEquals(item.name, entry.getAsJsonArray("requirements").get(0)
            .getAsJsonObject().get("item").getAsString(), "wiring an item fills the entry's costs");

        // An item wired with no factory entry present is refused with a message, not written.
        ContentRecord loneItem = controller.addContent(ContentType.item, 0d, 400d);
        ContentRecord otherUnit = controller.addContent(ContentType.unit, 800d, 0d);
        otherUnit.fields.remove("type");
        assertFalse(controller.connect(nodeOf(loneItem).id, nodeOf(otherUnit).id, "requirements"));
        assertNull(otherUnit.fields.get("requirements"));
    }

    @Test
    void connectingAnItemAddsTheAmmoMapKey() throws IOException {
        newProject();
        ContentRecord item = controller.addContent(ContentType.item, 0d, 0d);
        ContentRecord turret = controller.addContent(ContentType.block, 400d, 0d);
        turret.setClassName("ItemTurret");

        assertTrue(controller.connect(nodeOf(item).id, nodeOf(turret).id, "ammoTypes"));
        assertTrue(turret.fields.get("ammoTypes").getAsJsonObject().has(item.name),
            "wiring an item adds its ammo map key");
        assertEquals(0, turret.fields.get("ammoTypes").getAsJsonObject()
            .get(item.name).getAsJsonObject().entrySet().size(),
            "the bullet object starts empty - the game defaults type to BasicBulletType");

        controller.undo();
        assertNull(turret.fields.get("ammoTypes"), "undo removes the created key");
    }
}

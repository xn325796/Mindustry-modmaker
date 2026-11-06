package dev.modmaker.core.io;

import dev.modmaker.core.schema.SchemaRegistry;
import dev.modmaker.ui.ProjectController;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The progress/cancel channel of the import pipeline: ticks are throttled to every 64th file plus
 * the last one, a cancel stops the reader before it touches another file, and a cancel during the
 * write phase removes the half-written project folder the import itself created.
 */
class ImportProgressTest {

    /** Records every progressed tick as {done, total} per stage. */
    private static final class Recorder implements ImportProgress {
        final List<Stage> stages = new ArrayList<>();
        final List<int[]> readTicks = new ArrayList<>();
        final List<int[]> writeTicks = new ArrayList<>();

        @Override
        public void stageStarted(Stage stage, int total) {
            stages.add(stage);
        }

        @Override
        public void progressed(Stage stage, int done, int total) {
            (stage == Stage.WRITING ? writeTicks : readTicks).add(new int[]{done, total});
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }

    private static Path modFolder(int files) throws IOException {
        Path folder = Files.createTempDirectory("mm-progress-");
        for (int i = 0; i < files; i++) {
            Files.write(folder.resolve("f" + i + ".png"), new byte[]{1, 2, 3, 4});
        }
        return folder;
    }

    @Test
    void folderReadReportsEvery64thFilePlusTheLastOne() throws IOException {
        Recorder recorder = new Recorder();
        ModPackage pkg = ModPackageReader.read(modFolder(150), recorder);
        assertEquals(150, pkg.assetFiles.size());
        assertEquals(List.of(ImportProgress.Stage.READING), recorder.stages,
            "one reading stage per package");
        assertEquals(3, recorder.readTicks.size(),
            "150 files must emit ticks at 64, 128 and 150, not 150 of them");
        assertArrayEqualsTick(recorder.readTicks.get(0), 64, 150);
        assertArrayEqualsTick(recorder.readTicks.get(1), 128, 150);
        assertArrayEqualsTick(recorder.readTicks.get(2), 150, 150);
    }

    @Test
    void aCancelledReaderStopsBeforeReadingAnotherFile() throws IOException {
        ImportProgress immediately = new ImportProgress() {
            @Override
            public void stageStarted(Stage stage, int total) {
            }

            @Override
            public void progressed(Stage stage, int done, int total) {
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        assertThrows(ImportCancelledException.class,
            () -> ModPackageReader.read(modFolder(10), immediately));
    }

    @Test
    void cancellingTheWritePhaseRemovesTheHalfWrittenProjectFolder() throws IOException {
        SchemaRegistry registry = SchemaRegistry.loadDefault();
        ProjectController controller = new ProjectController(registry);
        Path source = modFolder(200);
        Path destination = Files.createTempDirectory("mm-progress-dest-").resolve("out");

        AtomicInteger lastWritten = new AtomicInteger(-1);
        ImportProgress cancelAt128 = new ImportProgress() {
            @Override
            public void stageStarted(Stage stage, int total) {
            }

            @Override
            public void progressed(Stage stage, int done, int total) {
                if (stage == Stage.WRITING) {
                    lastWritten.set(done);
                }
            }

            @Override
            public boolean isCancelled() {
                return lastWritten.get() >= 128;
            }
        };

        assertThrows(ImportCancelledException.class,
            () -> controller.readImport(source, destination, cancelAt128));
        assertTrue(lastWritten.get() >= 128, "the write phase must have started before the cancel");
        assertFalse(Files.exists(destination),
            "a cancelled import into a fresh folder must clean up what it wrote");
    }

    private static void assertArrayEqualsTick(int[] tick, int done, int total) {
        assertEquals(done, tick[0]);
        assertEquals(total, tick[1]);
    }
}

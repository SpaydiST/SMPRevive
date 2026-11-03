package macronux.smprevive.data;

import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AtomicStateFileTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void repeatedSavesWriteOnceButRollbackStillPersists() throws Exception {
        var file = temporary.getRoot().toPath().resolve("players.yml");
        var store = new AtomicStateFile(file, null);
        for (int i = 0; i < 1000; i++) store.save("hearts: 10\n");
        assertEquals(1, store.writes());
        assertEquals(999, store.skipped());
        store.save("hearts: 11\n");
        store.save("hearts: 10\n");
        assertEquals(3, store.writes());
        assertEquals("hearts: 10\n", Files.readString(file));
    }

    @Test public void deletedOrExternallyModifiedFileIsRestored() throws Exception {
        var file = temporary.getRoot().toPath().resolve("players.yml");
        Files.writeString(file, "hearts: 10\n");
        var store = new AtomicStateFile(file, "hearts: 10\n");
        Files.delete(file);
        store.save("hearts: 10\n");
        Files.writeString(file, "externally modified content\n");
        store.save("hearts: 10\n");
        assertEquals(2, store.writes());
        assertEquals("hearts: 10\n", Files.readString(file));
    }

    @Test public void failedWritePreservesOriginalAndRetriesChangedState() throws Exception {
        var file = temporary.getRoot().toPath().resolve("players.yml");
        Files.writeString(file, "old");
        var store = new AtomicStateFile(file, "old");
        var blocked = Files.createDirectory(file.resolveSibling("players.yml.tmp"));
        Files.writeString(blocked.resolve("blocker"), "blocker");
        try { store.save("new"); fail("must fail with a blocked temp path"); }
        catch (java.io.IOException expected) { assertEquals("old", Files.readString(file)); }
        assertEquals(0, store.writes());
        Files.delete(blocked.resolve("blocker"));
        Files.delete(blocked);
        store.save("old");
        assertEquals("old", Files.readString(file));
        assertEquals(1, store.writes()); // A failed attempt must invalidate the skip fingerprint.
        store.save("new");
        assertEquals("new", Files.readString(file));
        assertEquals(2, store.writes());
    }
}

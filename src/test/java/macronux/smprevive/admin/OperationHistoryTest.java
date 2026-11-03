package macronux.smprevive.admin;

import java.nio.file.Files;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class OperationHistoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void survivesRestartAndFiltersByUuid() throws Exception {
        var file = temporary.getRoot().toPath().resolve("history.log");
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        var history = new OperationHistory(file);
        history.record("Admin\nforged\tentry", alice, "Alice", "REVIVAL", "tokens=160", "SUCCESS");
        history.record("Admin", bob, "Bob", "REVIVAL", "tokens=80", "CANCELLED_REFUND_REQUESTED");
        var reloaded = new OperationHistory(file);
        assertEquals(2, reloaded.recent(null).size());
        assertEquals(bob, reloaded.recent(null).getFirst().targetId());
        assertEquals(1, reloaded.recent(alice).size());
        assertEquals("Admin forged entry", reloaded.recent(alice).getFirst().actor());
        assertEquals("tokens=160", reloaded.recent(alice).getFirst().details());
    }

    @Test public void skipsCorruptLinesAndBoundsLoadedHistory() throws Exception {
        var file = temporary.getRoot().toPath().resolve("history.log");
        StringBuilder content = new StringBuilder("bad-date\tactor\t-\ttarget\taction\tdetails\tresult\n");
        for (int i = 0; i < 1100; i++) content.append("2026-10-06T00:00:00Z\tAdmin\t-\t-\tSET\t").append(i).append("\tSUCCESS\n");
        content.append("unfinished\tlast");
        Files.writeString(file, content);
        var history = new OperationHistory(file);
        assertEquals(1000, history.recent(null).size());
        assertEquals("1099", history.recent(null).getFirst().details());
        assertEquals("100", history.recent(null).getLast().details());
        history.record("Admin", null, null, "RELOAD", "new valid entry", "SUCCESS");
        assertEquals("new valid entry", new OperationHistory(file).recent(null).getFirst().details());
    }

    @Test public void rotatesLogAndRestartsWithLatestEntry() throws Exception {
        var file = temporary.getRoot().toPath().resolve("history.log");
        var history = new OperationHistory(file);
        Files.writeString(file, "x".repeat(512 * 1024) + "\n");
        history.record("Admin", null, null, "RELOAD", "config", "SUCCESS");
        assertTrue(Files.exists(file.resolveSibling("history.log.1")));
        assertTrue(Files.size(file) < 1024);
        assertEquals("RELOAD", new OperationHistory(file).recent(null).getFirst().action());
    }

    @Test public void logFailureIsVisibleAndMemoryEntryRemains() throws Exception {
        var file = temporary.newFolder("unwritable-log").toPath();
        var history = new OperationHistory(file);
        try {
            history.record("Admin", null, null, "REVIVAL", "0", "SUCCESS");
            fail("writing to a directory must fail");
        } catch (java.io.IOException expected) {
            assertFalse(history.isPersistent());
            assertEquals(1, history.recent(null).size());
        }
        Files.delete(file);
        history.record("Admin", null, null, "RELOAD", "config", "SUCCESS");
        assertFalse(history.isPersistent()); // Later successes must not hide an earlier unpersisted entry.
        assertEquals(2, history.recent(null).size());
        assertEquals(1, new OperationHistory(file).recent(null).size());
    }
}

package macronux.smprevive.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.BasicFileAttributes;

/** Skips unchanged snapshots while preserving synchronous atomic replacement and retry semantics. */
public final class AtomicStateFile {
    private final Path file;
    private String savedState;
    private FileTime modified;
    private long size;
    private long writes;
    private long skipped;

    public AtomicStateFile(Path file, String loadedState) throws IOException {
        this.file = file;
        this.savedState = loadedState;
        if (Files.isRegularFile(file)) captureAttributes();
    }

    public void save(String currentState) throws IOException {
        if (currentState.equals(savedState) && Files.isRegularFile(file)
            && Files.getLastModifiedTime(file).equals(modified) && Files.size(file) == size) {
            skipped++;
            return;
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        // A replacement may succeed before a later metadata read fails. A rollback must still write.
        savedState = null;
        modified = null;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(currentState);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            captureAttributes();
            savedState = currentState;
            writes++;
        } catch (IOException | RuntimeException exception) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException ignored) { /* Preserve the original even if temporary cleanup fails. */ }
            throw exception;
        }
    }

    private void captureAttributes() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        modified = attributes.lastModifiedTime();
        size = attributes.size();
    }

    public long writes() { return writes; }
    public long skipped() { return skipped; }
}

package macronux.smprevive.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded diagnostic history, separate from the payment recovery journal. */
public final class OperationHistory {
    private static final int LIMIT = 1000;
    private static final long MAX_BYTES = 512 * 1024;
    private final Path file;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private boolean persistent = true;

    public record Entry(Instant time, String actor, UUID targetId, String target,
                        String action, String details, String result) {}

    public OperationHistory(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        load(backup());
        load(file);
    }

    private Path backup() { return file.resolveSibling(file.getFileName() + ".1"); }

    private void load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return;
        // Decode leniently: a torn multi-byte tail must not make the whole history unavailable.
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        for (String line : text.split("\n", -1)) {
            String[] p = line.split("\t", -1);
            if (p.length != 7) continue;
            try {
                remember(new Entry(Instant.parse(p[0]), p[1], p[2].equals("-") ? null : UUID.fromString(p[2]),
                    p[3], p[4], p[5], p[6]));
            } catch (IllegalArgumentException | DateTimeException ignored) {
                // Ignore an incomplete last line after a process interruption.
            }
        }
    }

    private void remember(Entry entry) {
        entries.addLast(entry);
        while (entries.size() > LIMIT) entries.removeFirst();
    }

    public synchronized void record(String actor, UUID targetId, String target,
                                    String action, String details, String result) throws IOException {
        Entry entry = new Entry(Instant.now(), clean(actor), targetId, clean(target),
            clean(action), clean(details), clean(result));
        remember(entry);
        try {
            if (Files.exists(file) && Files.size(file) >= MAX_BYTES) {
                Files.move(file, backup(), StandardCopyOption.REPLACE_EXISTING);
            }
            String line = entry.time() + "\t" + entry.actor() + "\t" + (targetId == null ? "-" : targetId)
                + "\t" + entry.target() + "\t" + entry.action() + "\t" + entry.details() + "\t" + entry.result() + "\n";
            // A crash can leave an incomplete EOF line. Separate it from the next valid record.
            if (Files.isRegularFile(file) && Files.size(file) > 0) {
                try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                    ByteBuffer last = ByteBuffer.allocate(1);
                    channel.position(channel.size() - 1);
                    if (channel.read(last) == 1 && last.array()[0] != '\n') line = "\n" + line;
                }
            }
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            // Keep degradation visible for this session: an earlier memory-only entry is not repaired by this append.
        } catch (IOException exception) {
            persistent = false;
            throw exception;
        }
    }

    private static String clean(String value) {
        if (value == null) return "-";
        String clean = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        int end = Math.min(200, clean.length());
        // Never cut a surrogate pair in half: an unpaired surrogate cannot be written as UTF-8.
        if (end < clean.length() && Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end);
    }

    /** Newest first; UUID filtering also works for offline and renamed players. */
    public synchronized List<Entry> recent(UUID targetId) {
        List<Entry> result = new ArrayList<>();
        var iterator = entries.descendingIterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (targetId == null || targetId.equals(entry.targetId())) result.add(entry);
        }
        return List.copyOf(result);
    }

    public synchronized boolean isPersistent() { return persistent; }
}

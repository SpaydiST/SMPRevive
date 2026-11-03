package macronux.smprevive.protection;

import java.util.*;

/** One removable deadline per ID: no stale heap entries or delivery object references. */
final class DeadlineQueue {
    private record Entry(UUID id, long due, long order) {}
    private final NavigableSet<Entry> deadlines = new TreeSet<>(Comparator.comparingLong(Entry::due).thenComparingLong(Entry::order));
    private final Map<UUID, Entry> byId = new HashMap<>();
    private long order;

    void schedule(UUID id, long due) {
        cancel(id);
        Entry entry = new Entry(id, due, order++);
        byId.put(id, entry); deadlines.add(entry);
    }
    void earlier(UUID id, long due) {
        Entry entry = byId.get(id);
        if (entry == null || due < entry.due) schedule(id, due);
    }
    boolean contains(UUID id) { return byId.containsKey(id); }
    void cancel(UUID id) {
        Entry entry = byId.remove(id);
        if (entry != null) deadlines.remove(entry);
    }
    List<UUID> ready(long now, int limit) {
        List<UUID> result = new ArrayList<>();
        while (result.size() < limit && !deadlines.isEmpty() && deadlines.first().due <= now) {
            Entry entry = deadlines.pollFirst(); byId.remove(entry.id); result.add(entry.id);
        }
        return result;
    }
    void clear() { byId.clear(); deadlines.clear(); }
}

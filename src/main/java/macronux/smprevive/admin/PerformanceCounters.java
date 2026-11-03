package macronux.smprevive.admin;

import java.util.LinkedHashMap;
import java.util.Map;

/** Passive measurements: no background task or world scan is started for diagnostics. */
public final class PerformanceCounters {
    private final Map<String, Timing> timings = new LinkedHashMap<>();
    private final Map<String, Long> counters = new LinkedHashMap<>();

    public record Timing(long count, long totalNanos, long maxNanos) {
        public double averageMillis() { return count == 0 ? 0 : totalNanos / (double) count / 1_000_000; }
        public double maximumMillis() { return maxNanos / 1_000_000.0; }
    }

    public synchronized void record(String name, long startNanos) {
        long elapsed = Math.max(0, System.nanoTime() - startNanos);
        Timing before = timings.getOrDefault(name, new Timing(0, 0, 0));
        timings.put(name, new Timing(before.count() + 1, before.totalNanos() + elapsed, Math.max(before.maxNanos(), elapsed)));
    }

    public synchronized void increment(String name) { add(name, 1); }
    public synchronized void add(String name, long amount) { counters.merge(name, amount, Long::sum); }
    public synchronized Map<String, Long> counts() { return Map.copyOf(counters); }

    public synchronized Map<String, Timing> snapshot() { return Map.copyOf(timings); }
}

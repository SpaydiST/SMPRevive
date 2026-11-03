package macronux.smprevive.protection;

/** Pure boundaries shared by persistence, item checks and regression tests. */
public final class ProtectionRules {
    public static final long HEAD_RETURN_DELAY_MILLIS = 10 * 60 * 1000L;
    private ProtectionRules() { }

    public static boolean isDue(long deadline, long now) { return now >= deadline; }
}

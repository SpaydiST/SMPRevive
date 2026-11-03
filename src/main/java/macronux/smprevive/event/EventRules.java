package macronux.smprevive.event;

public final class EventRules {
    public static final int HEART_RISE_TICKS = 60;
    public static final int HEART_DELAY_MIN_TICKS = 10;
    public static final int HEART_DELAY_MAX_TICKS = 30;
    public static final int HEART_TRAVEL_TICKS = 30;
    public static final int PLAYER_DELAY_MIN_TICKS = 10;
    public static final int PLAYER_DELAY_MAX_TICKS = 30;
    public static final int PLAYER_RISE_TICKS = 70;
    public static final int BORDER_SLOW_TICKS = 40;
    public static final int BORDER_FAST_TICKS = 30;
    public static final int BURST_HEART_HOLD_TICKS = 8;
    public static final int BURST_HEART_FLIGHT_TICKS = 24;
    public static final int BURST_PLAYER_FLIGHT_TICKS = 40;
    public static final int BURST_TICKS = BURST_HEART_HOLD_TICKS
        + BURST_HEART_FLIGHT_TICKS
        + BURST_PLAYER_FLIGHT_TICKS;
    public static final int PLAYER_LAND_TICKS = 60;
    public static final int BURST_PREPARE_TIMEOUT_TICKS = 200;
    public static final double TARGET_BORDER_SIZE = 10_000.0;

    private EventRules() {
    }

    public enum Phase {
        HEARTS_RISE,
        HEARTS_TO_CENTER,
        PLAYERS_RISE,
        BORDER_EXPANSION,
        BURST,
        PLAYER_LAND
    }

    /** Correct only part of the error: client position reports can arrive several ticks late. */
    public static org.bukkit.util.Vector motionVelocity(org.bukkit.util.Vector offset) {
        org.bukkit.util.Vector velocity = offset.clone().multiply(0.15);
        double speed = velocity.length();
        if (speed > 2.5) velocity.multiply(2.5 / speed);
        return velocity;
    }

    public static double clamp01(double value) {
        if (!Double.isFinite(value)) return value < 0.0 ? 0.0 : 1.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static double smoothstep(double progress) {
        double t = clamp01(progress);
        return t * t * (3.0 - 2.0 * t);
    }

    public static double easeOutCubic(double progress) {
        double t = clamp01(progress);
        double remaining = 1.0 - t;
        return 1.0 - remaining * remaining * remaining;
    }

    public static double interpolate(double start, double end, double progress) {
        return start + (end - start) * clamp01(progress);
    }

    public static double cloudRadius(double progress) {
        return interpolate(0.45, 2.5, smoothstep(progress));
    }

    public static double cloudHalfHeight(double progress) {
        return interpolate(0.5, 1.8, smoothstep(progress));
    }

    public static double slowBorderTarget(double initialSize) {
        return Math.max(initialSize, 2_500.0);
    }

    public static int clampDelay(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

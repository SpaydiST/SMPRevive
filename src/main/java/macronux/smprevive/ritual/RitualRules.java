package macronux.smprevive.ritual;

/** Pure timing and geometry of the wither skeleton skull ritual. */
public final class RitualRules {
    public static final double RISE_HEIGHT = 10.0;
    public static final int RISE_TICKS = 70;
    public static final int SPIN_TICKS = 60;
    public static final int SPHERE_TICKS = 80;
    public static final int STAR_TICKS = 40;
    public static final int STORM_TICKS = 320;
    public static final int CALM_TICKS = 40;
    public static final int HEART_LIFT_TICKS = 30;
    public static final int HEART_FLIGHT_TICKS = 120;
    public static final int APPROACH_TICKS = 60;
    public static final int SHRINK_TICKS = 140;
    public static final int FADE_TICKS = 80;

    public static final double STORM_START_RADIUS = 28.0;
    public static final double STORM_END_RADIUS = 2.0;
    public static final double ORBIT_START_RADIUS = 3.5;
    public static final double ORBIT_END_RADIUS = 0.7;
    public static final double SPHERE_RADIUS = 1.35;
    public static final int SPHERE_POINTS = 110;
    /** Height of the nether star above the sphere centre. */
    public static final double STAR_HEIGHT = 3.2;
    /** Lightning only throws players that stand this close to the spot it hit. */
    public static final double STRIKE_KNOCKBACK_RADIUS = 3.0;
    /** A skull item model sits below the display origin, so it is lifted by a quarter of its size to be centred. */
    public static final float SKULL_SCALE = 1.2f;
    public static final float SKULL_LIFT = SKULL_SCALE * 0.25f;

    private static final double STRIKES_START = 0.08;
    private static final double STRIKES_END = 1.6;
    private static final double ORBIT_SPEED_START = 0.08;
    private static final double ORBIT_SPEED_END = 0.4;
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    private RitualRules() {
    }

    public static double smoothstep(double t) {
        double x = Math.max(0.0, Math.min(1.0, t));
        return x * x * (3.0 - 2.0 * x);
    }

    public static double progress(int tick, int total) {
        return total <= 1 ? 1.0 : Math.max(0.0, Math.min(1.0, tick / (double) (total - 1)));
    }

    /** Radius of the ring the lightning falls on; shrinks until it reaches the skull. */
    public static double strikeRadius(int tick, int total) {
        double t = progress(tick, total);
        return STORM_START_RADIUS + (STORM_END_RADIUS - STORM_START_RADIUS) * t;
    }

    /** Expected number of lightning strikes in one tick; grows quadratically over the storm. */
    public static double strikesPerTick(int tick, int total) {
        double t = progress(tick, total);
        return STRIKES_START + (STRIKES_END - STRIKES_START) * t * t;
    }

    public static double orbitRadius(int tick, int total) {
        return ORBIT_START_RADIUS + (ORBIT_END_RADIUS - ORBIT_START_RADIUS) * smoothstep(progress(tick, total));
    }

    /** Angular speed in radians per tick; the orbiting pair accelerates as it closes in. */
    public static double orbitSpeed(int tick, int total) {
        double t = progress(tick, total);
        return ORBIT_SPEED_START + (ORBIT_SPEED_END - ORBIT_SPEED_START) * t * t;
    }

    /** Evenly spread unit vector number {@code index} of {@code count}, turned around the vertical axis by {@code rotation}. */
    public static double[] spherePoint(int index, int count, double rotation) {
        double y = 1.0 - 2.0 * (index + 0.5) / count;
        double ring = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        double angle = index * GOLDEN_ANGLE + rotation;
        return new double[]{Math.cos(angle) * ring, y, Math.sin(angle) * ring};
    }
}

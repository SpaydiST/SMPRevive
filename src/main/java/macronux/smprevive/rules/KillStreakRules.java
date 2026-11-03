package macronux.smprevive.rules;

public final class KillStreakRules {
    public static final int PARTICLE_START_STREAK = 2;
    public static final int MAX_SKULL_PARTICLES = 16;
    public static final int BURST_TICKS = 10;
    public static final String SKULL_PARTICLE_CARRIER = "pause_mob_growth";
    public static final int KILL_CLOUD_PARTICLE_COUNT = 6;

    private KillStreakRules() {}

    public static int increment(int currentStreak) {
        if (currentStreak < 0) return 1;
        return currentStreak == Integer.MAX_VALUE ? Integer.MAX_VALUE : currentStreak + 1;
    }

    public static boolean shouldSpawnParticles(int streak) {
        return streak >= PARTICLE_START_STREAK;
    }

    public static int skullCount(int streak) {
        return shouldSpawnParticles(streak) ? Math.min(streak, MAX_SKULL_PARTICLES) : 0;
    }

    public static int skullsForBurstTick(int remainingSkulls, int remainingTicks) {
        if (remainingSkulls <= 0 || remainingTicks <= 0) return 0;
        return remainingSkulls / remainingTicks
            + (remainingSkulls % remainingTicks == 0 ? 0 : 1);
    }

    public static String skullParticleCarrier() {
        return SKULL_PARTICLE_CARRIER;
    }

    public static int killCloudParticleCount() {
        return KILL_CLOUD_PARTICLE_COUNT;
    }
}

package macronux.smprevive;

import macronux.smprevive.rules.KillStreakRules;

public final class KillStreakRulesChecks {
    private KillStreakRulesChecks() {}

    public static void main(String[] args) {
        check(KillStreakRules.increment(0) == 1, "first kill starts a streak");
        check(KillStreakRules.increment(1) == 2, "streak increments");
        check(KillStreakRules.increment(-1) == 1, "negative streak is sanitized");
        check(KillStreakRules.increment(Integer.MAX_VALUE) == Integer.MAX_VALUE, "streak does not overflow");

        check(!KillStreakRules.shouldSpawnParticles(1), "first kill has no particles");
        check(KillStreakRules.shouldSpawnParticles(2), "second kill starts particles");
        check(KillStreakRules.skullCount(1) == 0, "first kill has no skulls");
        check(KillStreakRules.skullCount(2) == 2, "skulls match the streak");
        check(KillStreakRules.skullCount(8) > KillStreakRules.skullCount(2), "more kills produce more skulls");
        check(KillStreakRules.skullCount(100) == 16, "skull count is capped");
        check(KillStreakRules.skullsForBurstTick(2, 10) == 1, "two-kill streak distributes two skulls");
        check(KillStreakRules.skullsForBurstTick(16, 10) == 2, "large streak distributes skulls");
        check(KillStreakRules.skullsForBurstTick(0, 10) == 0, "empty burst emits no skulls");
        check(totalBurstSkulls(2) == 2, "two-kill streak emits exactly two skulls");
        check(totalBurstSkulls(3) == 3, "three-kill streak emits exactly three skulls");
        check(totalBurstSkulls(16) == 16, "capped streak emits exactly its skull count");
        check(KillStreakRules.skullParticleCarrier().equals("pause_mob_growth"), "skull uses the custom particle carrier");
        check(KillStreakRules.killCloudParticleCount() == 6, "kill cloud has a fixed visible size");

        System.out.println("KillStreakRulesChecks: PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int totalBurstSkulls(int streak) {
        int remainingSkulls = KillStreakRules.skullCount(streak);
        int remainingTicks = KillStreakRules.BURST_TICKS;
        int total = 0;
        while (remainingSkulls > 0 && remainingTicks > 0) {
            int currentTick = KillStreakRules.skullsForBurstTick(remainingSkulls, remainingTicks);
            total += currentTick;
            remainingSkulls -= currentTick;
            remainingTicks--;
        }
        return total;
    }
}

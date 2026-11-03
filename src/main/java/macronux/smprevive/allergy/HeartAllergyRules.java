package macronux.smprevive.allergy;

import macronux.smprevive.rules.ReviveRules;

public final class HeartAllergyRules {
    private static final int MIN_REVIVAL_HEARTS = 7;
    private static final int MAX_REVIVAL_HEARTS = 13;
    private static final double MIN_LANDING_DISTANCE = 3.0;
    private static final double MAX_LANDING_DISTANCE = 4.0;
    private static final double MAX_FAILURE_DAMAGE = 0.5;
    private static final double MIN_FAILURE_KNOCKBACK = 1.5;
    private static final double MAX_FAILURE_KNOCKBACK_BONUS = 1.0;

    private HeartAllergyRules() {}

    public static boolean shouldDropHearts(boolean allergic) {
        return !allergic;
    }

    public static int revivalHearts(int randomOffset, int maximum) {
        if (randomOffset < -3 || randomOffset > 3) {
            throw new IllegalArgumentException("randomOffset must be between -3 and 3");
        }
        int value = 10 + randomOffset;
        return ReviveRules.clampPlayerHearts(
            Math.max(MIN_REVIVAL_HEARTS, Math.min(MAX_REVIVAL_HEARTS, value)),
            maximum
        );
    }

    public static boolean shouldStutterAnimation(boolean allergic, int tick) {
        return allergic && tick > 0 && tick % 4 == 0;
    }

    public static double failureDamage(double currentHealth) {
        if (!Double.isFinite(currentHealth)) return 0.0;
        return Math.min(MAX_FAILURE_DAMAGE, Math.max(0.0, currentHealth - 1.0));
    }

    public static double allergicLandingDistance(double randomUnit) {
        if (!Double.isFinite(randomUnit)) return MIN_LANDING_DISTANCE;
        double unit = Math.max(0.0, Math.min(1.0, randomUnit));
        return MIN_LANDING_DISTANCE + unit * (MAX_LANDING_DISTANCE - MIN_LANDING_DISTANCE);
    }

    public static double failureExplosionDamage(double distance, double radius) {
        if (!isWithinRadius(distance, radius)) return 0.0;
        return MAX_FAILURE_DAMAGE;
    }

    public static double failureExplosionKnockback(double distance, double radius) {
        if (!isWithinRadius(distance, radius)) return 0.0;
        double falloff = 1.0 - Math.min(1.0, distance / radius);
        return MIN_FAILURE_KNOCKBACK + falloff * MAX_FAILURE_KNOCKBACK_BONUS;
    }

    private static boolean isWithinRadius(double distance, double radius) {
        return Double.isFinite(distance)
            && Double.isFinite(radius)
            && distance >= 0.0
            && radius > 0.0
            && distance <= radius;
    }
}

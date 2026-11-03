package macronux.smprevive;

import macronux.smprevive.allergy.HeartAllergyRules;

public final class HeartAllergyRulesChecks {
    private HeartAllergyRulesChecks() {}

    public static void main(String[] args) {
        check(!HeartAllergyRules.shouldDropHearts(true), "allergic death keeps hearts");
        check(HeartAllergyRules.shouldDropHearts(false), "normal death drops hearts");

        check(HeartAllergyRules.revivalHearts(-3, 20) == 7, "lower revival roll");
        check(HeartAllergyRules.revivalHearts(0, 20) == 10, "middle revival roll");
        check(HeartAllergyRules.revivalHearts(3, 20) == 13, "upper revival roll");
        check(HeartAllergyRules.revivalHearts(-3, 5) == 5, "maximum clamp");
        check(HeartAllergyRules.revivalHearts(3, 1024) == 13, "wide maximum");

        check(HeartAllergyRules.shouldStutterAnimation(true, 4), "allergic tick stutters");
        check(!HeartAllergyRules.shouldStutterAnimation(true, 5), "non-stutter tick renders");
        check(!HeartAllergyRules.shouldStutterAnimation(false, 4), "normal animation never stutters");

        check(HeartAllergyRules.failureDamage(100.0) == 0.5, "damage upper bound");
        check(HeartAllergyRules.failureDamage(1.25) == 0.25, "damage preserves one HP");
        check(HeartAllergyRules.failureDamage(1.0) == 0.0, "low health is safe");
        check(HeartAllergyRules.failureDamage(Double.NaN) == 0.0, "invalid health is safe");

        checkAllergicFlightRules();

        System.out.println("HeartAllergyRulesChecks: PASS");
    }

    private static void checkAllergicFlightRules() {
        check(HeartAllergyRules.allergicLandingDistance(0.0) == 3.0, "allergic flight lower radius");
        check(HeartAllergyRules.allergicLandingDistance(0.5) == 3.5, "allergic flight middle radius");
        check(HeartAllergyRules.allergicLandingDistance(1.0) == 4.0, "allergic flight upper radius");
        check(HeartAllergyRules.failureExplosionDamage(0.0, 4.5) > 0.0, "failed heart explosion damages its center");
        check(HeartAllergyRules.failureExplosionDamage(0.0, 4.5) <= 0.5, "failed heart explosion damage is small");
        check(HeartAllergyRules.failureExplosionDamage(5.0, 4.5) == 0.0, "failed heart explosion has a bounded radius");
        check(HeartAllergyRules.failureExplosionKnockback(0.0, 4.5) > 1.0, "failed heart explosion has strong knockback");
        check(HeartAllergyRules.failureExplosionKnockback(5.0, 4.5) == 0.0, "knockback is zero outside the radius");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

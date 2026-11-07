package macronux.smprevive.ritual;

import junit.framework.TestCase;

public final class RitualRulesTest extends TestCase {
    public void testStormRingOnlyShrinksAndEndsAtSkull() {
        double previous = Double.MAX_VALUE;
        for (int tick = 0; tick < RitualRules.STORM_TICKS; tick++) {
            double radius = RitualRules.strikeRadius(tick, RitualRules.STORM_TICKS);
            assertTrue(radius <= previous);
            previous = radius;
        }
        assertEquals(RitualRules.STORM_START_RADIUS, RitualRules.strikeRadius(0, RitualRules.STORM_TICKS), 1e-9);
        assertEquals(RitualRules.STORM_END_RADIUS,
            RitualRules.strikeRadius(RitualRules.STORM_TICKS - 1, RitualRules.STORM_TICKS), 1e-9);
    }

    public void testLightningGetsDenserAndStaysBounded() {
        double previous = 0.0;
        for (int tick = 0; tick < RitualRules.STORM_TICKS; tick++) {
            double rate = RitualRules.strikesPerTick(tick, RitualRules.STORM_TICKS);
            assertTrue(rate >= previous);
            assertTrue(rate > 0.0 && rate < 2.0);
            previous = rate;
        }
    }

    public void testOrbitClosesInAndSpeedsUp() {
        assertEquals(RitualRules.ORBIT_START_RADIUS, RitualRules.orbitRadius(0, RitualRules.SHRINK_TICKS), 1e-9);
        assertEquals(RitualRules.ORBIT_END_RADIUS,
            RitualRules.orbitRadius(RitualRules.SHRINK_TICKS - 1, RitualRules.SHRINK_TICKS), 1e-9);
        assertTrue(RitualRules.orbitSpeed(RitualRules.SHRINK_TICKS - 1, RitualRules.SHRINK_TICKS)
            > RitualRules.orbitSpeed(0, RitualRules.SHRINK_TICKS));
    }

    public void testSpherePointsLieOnUnitSphere() {
        for (int i = 0; i < RitualRules.SPHERE_POINTS; i++) {
            double[] p = RitualRules.spherePoint(i, RitualRules.SPHERE_POINTS, 1.7);
            assertEquals(1.0, Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]), 1e-9);
        }
    }
}

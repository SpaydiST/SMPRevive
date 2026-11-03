package macronux.smprevive.event;

import junit.framework.TestCase;
import org.bukkit.util.Vector;

public final class EventMotionTest extends TestCase {
    public void testCorrectionIsBoundedAndDoesNotMutateTarget() {
        Vector offset = new Vector(100, -100, 100);
        Vector velocity = EventRules.motionVelocity(offset);
        assertEquals(2.5, velocity.length(), 1e-9);
        assertEquals(new Vector(100, -100, 100), offset);
        assertTrue(velocity.dot(offset) > 0);
        assertEquals(new Vector(), EventRules.motionVelocity(new Vector()));
    }

    public void testDelayedPositionsConvergeDuringRiseAndHold() {
        // A simple delayed-feedback model, not a substitute for a Minecraft client test.
        for (int lag = 0; lag <= 5; lag++) {
            Vector[] positions = new Vector[301];
            positions[0] = new Vector();
            for (int tick = 0; tick < 300; tick++) {
                Vector target = new Vector(4, 12 * EventRules.smoothstep(tick / 70.0), -3);
                Vector reported = positions[Math.max(0, tick - lag)];
                Vector velocity = EventRules.motionVelocity(target.subtract(reported));
                assertTrue(velocity.length() <= 2.5 + 1e-9);
                positions[tick + 1] = positions[tick].clone().add(velocity);
            }
            assertTrue("Failed to settle with lag " + lag,
                positions[300].distance(new Vector(4, 12, -3)) < 0.001);
        }
    }
}

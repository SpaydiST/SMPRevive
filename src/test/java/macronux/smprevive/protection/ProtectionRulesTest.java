package macronux.smprevive.protection;

import junit.framework.TestCase;

/** Pure rules also checked against the real Paper fixture by the runtime harness. */
public final class ProtectionRulesTest extends TestCase {
    public void testReturnUsesTenRealMinutes() {
        assertEquals(600_000L, ProtectionRules.HEAD_RETURN_DELAY_MILLIS);
        assertFalse(ProtectionRules.isDue(600_000L, 599_999L));
        assertTrue(ProtectionRules.isDue(600_000L, 600_000L));
        assertTrue(ProtectionRules.isDue(600_000L, 900_000L));
    }
}

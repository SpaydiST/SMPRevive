package macronux.smprevive;

import macronux.smprevive.rules.ReviveRules;

import junit.framework.TestCase;

/** Runs the existing executable checks in the standard Maven test lifecycle. */
public final class RegressionTest extends TestCase {
    public void testAssertionsEnabled() { assertTrue(LogicChecks.class.desiredAssertionStatus()); }
    public void testBuild() throws Exception { BuildContractChecks.main(new String[0]); }
    public void testLogic() { LogicChecks.main(new String[0]); }
    public void testIntegration() throws Exception { IntegrationContractChecks.main(new String[0]); }
    public void testEventContract() throws Exception { EventContractChecks.main(new String[0]); }
    public void testSpawnContract() throws Exception { EventSpawnLocatorContractChecks.main(new String[0]); }
    public void testEventRules() { EventRulesChecks.main(new String[0]); }
    public void testAllergyRules() { HeartAllergyRulesChecks.main(new String[0]); }
    public void testKillStreakRules() { KillStreakRulesChecks.main(new String[0]); }
    public void testRecoveryRules() { RecoveryRulesChecks.main(new String[0]); }

    public void testVanillaAirCancellationDoesNotBlockAnAllowedHeart() {
        assertTrue(ReviveRules.canUseHeart(true, false, false));
        assertFalse(ReviveRules.canUseHeart(true, true, false));
        assertFalse(ReviveRules.canUseHeart(true, false, true));
    }

    public void testOnlineRevivalQueuesAchievementUntilAwardConfirmed() {
        assertTrue(ReviveRules.shouldQueueRevivedAchievement(true, true));
        assertFalse(ReviveRules.shouldQueueRevivedAchievement(true, false));
    }
}

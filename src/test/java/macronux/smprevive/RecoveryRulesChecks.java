package macronux.smprevive;

import macronux.smprevive.recovery.RecoveryRules;
import macronux.smprevive.rules.ReviveRules;

/** Dependency-light checks for the recovery journal decision table. */
public final class RecoveryRulesChecks {
    private RecoveryRulesChecks() {
    }

    public static void main(String[] args) {
        assert RecoveryRules.shouldClearWithoutRefund("PREPARED", false, true, false);
        assert !RecoveryRules.shouldClearWithoutRefund("PREPARED", false, false, false);
        assert !RecoveryRules.shouldClearWithoutRefund("CONSUMED", false, false, false);
        assert RecoveryRules.shouldClearWithoutRefund("CONSUMED", true, false, false);
        assert RecoveryRules.shouldClearWithoutRefund("CONSUMED", false, false, true);
        assert !RecoveryRules.shouldRefund("PREPARED", false, true, false);
        assert RecoveryRules.shouldRefund("CONSUMED", false, false, false);
        assert !RecoveryRules.shouldRefund("CONSUMED", true, false, false);
        assert !RecoveryRules.shouldRefund("CONSUMED", false, false, true);
        assert !RecoveryRules.shouldClearWithoutRefund("PREPARED", false, true, false, true);
        assert RecoveryRules.shouldClearWithoutRefund("PREPARED", false, true, false, false);
        assert !RecoveryRules.shouldClearWithoutRefund("CONSUMED", false, false, false, false);
        assert RecoveryRules.shouldRefund("PREPARED", false, true, false, true);
        assert RecoveryRules.shouldClearWithoutRefund("PREPARED", false, false, false, false, true);
        assert !RecoveryRules.shouldClearWithoutRefund("PREPARED", false, false, false, true, true);
        assert RecoveryRules.shouldClearWithoutRefund("PREPARED", false, true, false, false, true);
        assert !RecoveryRules.shouldClearWithoutRefund("REFUNDING", true, false, false, true, true);
        assert RecoveryRules.shouldClearWithoutRefund("REFUNDING", false, false, false, false, true);
        assert RecoveryRules.shouldClearWithoutRefund("CONSUMED", false, false, false, false, true);
        assert ReviveRules.isDangerousSpawnMaterial("LAVA");
        assert ReviveRules.isDangerousSpawnMaterial("MAGMA_BLOCK");
        assert ReviveRules.isDangerousSpawnMaterial("SWEET_BERRY_BUSH");
        assert !ReviveRules.isDangerousSpawnMaterial("STONE");
        System.out.println("RecoveryRulesChecks: PASS");
    }
}

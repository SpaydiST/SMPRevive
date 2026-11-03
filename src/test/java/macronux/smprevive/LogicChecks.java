package macronux.smprevive;

import macronux.smprevive.advancement.Achievement;
import macronux.smprevive.rules.ReviveRules;

import java.util.List;
import java.util.UUID;

/**
 * Dependency-light regression checks. Run with assertions enabled after compiling
 * this file together with ReviveRules.java.
 */
public final class LogicChecks {
    private LogicChecks() {
    }

    public static void main(String[] args) {
        checkHeartRangeNormalization();
        checkCasinoTimingNormalization();
        checkTokenQuantityAndExactConsumption();
        checkInteractionRouting();
        checkAltarPlacementInterception();
        checkAltarDisplayRecovery();
        checkGameModeAndHeartGuards();
        checkTokenMaterialAndHeartBounds();
        checkInteractionCancellationAndCasinoGuard();
        checkAchievementRules();
        System.out.println("LogicChecks: PASS");
    }

    private static void checkHeartRangeNormalization() {
        ReviveRules.HeartRange range = ReviveRules.normalizeHearts(0, 2000, -4);
        assert range.min() == 1 : range;
        assert range.start() == 1 : range;
        assert range.max() == 1 : range;

        range = ReviveRules.normalizeHearts(15, 4, 10);
        assert range.min() == 10 : range;
        assert range.start() == 10 : range;
        assert range.max() == 10 : range;
    }

    private static void checkCasinoTimingNormalization() {
        ReviveRules.TimerRange timing = ReviveRules.normalizeCasinoTiming(0, 0);
        assert timing.durationTicks() == 1 : timing;
        assert timing.periodTicks() == 1 : timing;

        timing = ReviveRules.normalizeCasinoTiming(5, 50);
        assert timing.durationTicks() == 5 : timing;
        assert timing.periodTicks() == 5 : timing;
    }

    private static void checkTokenQuantityAndExactConsumption() {
        List<ReviveRules.TokenStack> stacks = List.of(
            new ReviveRules.TokenStack(11, "DIAMOND", 64),
            new ReviveRules.TokenStack(12, "DIAMOND", 3),
            new ReviveRules.TokenStack(13, "EMERALD", 1)
        );

        ReviveRules.TokenSummary summary = ReviveRules.inspectAndConsume(stacks, "DIAMOND", 8);
        assert summary.matchingCount() == 67 : summary;
        assert summary.wrongStackCount() == 1 : summary;
        assert total(summary.consumed()) == 8 : summary;
        assert total(summary.remaining()) == 60 : summary;
        assert amountAt(summary.consumed(), 11) == 8 : summary;
        assert amountAt(summary.remaining(), 11) == 56 : summary;
        assert amountAt(summary.remaining(), 12) == 3 : summary;
        assert amountAt(summary.remaining(), 13) == 1 : summary;
    }

    private static void checkInteractionRouting() {
        assert ReviveRules.routeInteraction(true, "RIGHT_CLICK_AIR", "OFF_HAND")
            == ReviveRules.InteractionRoute.HEART;
        assert ReviveRules.routeInteraction(true, "RIGHT_CLICK_BLOCK", "HAND")
            == ReviveRules.InteractionRoute.HEART;
        assert ReviveRules.routeInteraction(false, "RIGHT_CLICK_BLOCK", "OFF_HAND")
            == ReviveRules.InteractionRoute.NONE;
        assert ReviveRules.routeInteraction(false, "RIGHT_CLICK_BLOCK", "HAND")
            == ReviveRules.InteractionRoute.ALTAR_OPEN;
        assert ReviveRules.routeInteraction(true, "LEFT_CLICK_BLOCK", "HAND")
            == ReviveRules.InteractionRoute.ALTAR_BREAK;
    }

    private static void checkAltarPlacementInterception() {
        assert ReviveRules.shouldInterceptAltarPlacement(true, false, "RIGHT_CLICK_BLOCK");
        assert !ReviveRules.shouldInterceptAltarPlacement(true, true, "RIGHT_CLICK_BLOCK");
        assert !ReviveRules.shouldInterceptAltarPlacement(true, false, "RIGHT_CLICK_AIR");
        assert !ReviveRules.shouldInterceptAltarPlacement(false, false, "RIGHT_CLICK_BLOCK");
    }

    private static void checkAltarDisplayRecovery() {
        assert ReviveRules.shouldRestoreAltarDisplay(true, true, true);
        assert !ReviveRules.shouldRestoreAltarDisplay(false, true, true);
        assert !ReviveRules.shouldRestoreAltarDisplay(true, false, true);
        assert !ReviveRules.shouldRestoreAltarDisplay(true, true, false);
    }

    private static void checkGameModeAndHeartGuards() {
        assert !ReviveRules.allowsCustomAltarAction("ADVENTURE");
        assert !ReviveRules.allowsCustomAltarAction("SPECTATOR");
        assert ReviveRules.allowsCustomAltarAction("SURVIVAL");
        assert ReviveRules.allowsCustomAltarAction("CREATIVE");

        assert ReviveRules.canUseHeart(false, false);
        assert !ReviveRules.canUseHeart(true, false);
        assert !ReviveRules.canUseHeart(false, true);
    }

    private static void checkTokenMaterialAndHeartBounds() {
        assert ReviveRules.isValidTokenMaterialName("DIAMOND");
        assert ReviveRules.isValidTokenMaterialName(" minecraft:diamond ");
        assert !ReviveRules.isValidTokenMaterialName(null);
        assert !ReviveRules.isValidTokenMaterialName("");
        assert !ReviveRules.isValidTokenMaterialName("AIR");
        assert ReviveRules.clampPlayerHearts(10, 20) == 10;
        assert ReviveRules.clampPlayerHearts(0, 20) == 1;
        assert ReviveRules.clampPlayerHearts(-4, 20) == 1;
        assert ReviveRules.clampPlayerHearts(40, 20) == 20;
        assert ReviveRules.clampPlayerHearts(1024, 1024) == 512;
    }

    private static void checkInteractionCancellationAndCasinoGuard() {
        assert ReviveRules.canUseHeart(true, false, false);
        assert !ReviveRules.canUseHeart(false, true, false);
        assert !ReviveRules.canUseHeart(false, false, true);
        assert ReviveRules.canUseHeart(false, false, false);

        assert ReviveRules.shouldOpenCasino(true, false, false, false, false);
        assert !ReviveRules.shouldOpenCasino(false, false, false, false, false);
        assert !ReviveRules.shouldOpenCasino(true, true, false, false, false);
        assert !ReviveRules.shouldOpenCasino(true, false, true, false, false);
        assert !ReviveRules.shouldOpenCasino(true, false, false, true, false);
        assert !ReviveRules.shouldOpenCasino(true, false, false, false, true);
    }

    private static void checkAchievementRules() {
        assert Achievement.FIRST_HEART.id().equals("first_heart");
        assert Achievement.MAX_HEARTS.id().equals("max_hearts");
        assert Achievement.FIRST_DEATH.id().equals("first_death");
        assert Achievement.ALTAR_PLACED.id().equals("altar_placed");
        assert Achievement.REVIVE_OTHER.id().equals("revive_other");
        assert Achievement.REVIVED.id().equals("revived");
        assert Achievement.FAILED_LOVE.id().equals("failed_love");

        assert ReviveRules.shouldAwardFirstHeart(10, 11);
        assert !ReviveRules.shouldAwardFirstHeart(10, 10);
        assert !ReviveRules.shouldAwardFirstHeart(11, 10);

        assert ReviveRules.shouldAwardMaxHearts(19, 20, 20);
        assert !ReviveRules.shouldAwardMaxHearts(20, 20, 20);
        assert !ReviveRules.shouldAwardMaxHearts(19, 19, 20);
        assert !ReviveRules.shouldAwardMaxHearts(20, 21, 20);
        assert !ReviveRules.shouldAwardMaxHearts(1, 2, 0);

        assert ReviveRules.shouldAwardFirstDeath(false);
        assert !ReviveRules.shouldAwardFirstDeath(true);
        assert ReviveRules.shouldQueueRevivedAchievement(false, true);
        assert ReviveRules.shouldQueueRevivedAchievement(true, true);
        assert !ReviveRules.shouldQueueRevivedAchievement(false, false);

        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assert ReviveRules.isDifferentPlayer(first, second);
        assert !ReviveRules.isDifferentPlayer(first, first);
        assert !ReviveRules.isDifferentPlayer(null, second);
    }

    private static int total(List<ReviveRules.TokenStack> stacks) {
        return stacks.stream().mapToInt(ReviveRules.TokenStack::amount).sum();
    }

    private static int amountAt(List<ReviveRules.TokenStack> stacks, int slot) {
        return stacks.stream()
            .filter(stack -> stack.slot() == slot)
            .mapToInt(ReviveRules.TokenStack::amount)
            .sum();
    }
}

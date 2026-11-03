package macronux.smprevive;

import macronux.smprevive.event.EventRules;

public final class EventRulesChecks {
    private EventRulesChecks() {}

    public static void main(String[] args) {
        require(EventRules.HEART_RISE_TICKS == 60, "heart rise duration");
        require(EventRules.HEART_DELAY_MIN_TICKS == 10, "heart delay minimum");
        require(EventRules.HEART_DELAY_MAX_TICKS == 30, "heart delay maximum");
        require(EventRules.BURST_HEART_HOLD_TICKS == 8, "burst heart hold duration");
        require(EventRules.BURST_HEART_FLIGHT_TICKS == 24, "burst heart flight duration");
        require(EventRules.PLAYER_DELAY_MIN_TICKS == 10, "player delay minimum");
        require(EventRules.PLAYER_DELAY_MAX_TICKS == 30, "player delay maximum");
        require(EventRules.PLAYER_RISE_TICKS == 70, "player rise duration");
        require(EventRules.BURST_PLAYER_FLIGHT_TICKS == 40, "player burst flight duration");
        require(EventRules.BURST_TICKS == 72, "extended burst duration");
        require(EventRules.PLAYER_LAND_TICKS == 60, "landing duration");
        require(EventRules.BURST_PREPARE_TIMEOUT_TICKS > 0, "burst preparation timeout");
        require(EventRules.clampDelay(2, 10, 30) == 10, "delay lower clamp");
        require(EventRules.clampDelay(44, 10, 30) == 30, "delay upper clamp");
        require(EventRules.smoothstep(0.0) == 0.0, "smoothstep start");
        require(EventRules.smoothstep(1.0) == 1.0, "smoothstep end");
        require(EventRules.easeOutCubic(0.0) == 0.0, "landing start");
        require(EventRules.easeOutCubic(1.0) == 1.0, "landing end");
        require(EventRules.easeOutCubic(0.25) > 0.25, "landing starts fast");
        require(EventRules.cloudRadius(0.0) < EventRules.cloudRadius(1.0), "cloud grows horizontally");
        require(EventRules.cloudHalfHeight(0.0) < EventRules.cloudHalfHeight(1.0), "cloud grows vertically");
        require(EventRules.cloudRadius(1.0) >= 2.0 && EventRules.cloudRadius(1.0) <= 3.0, "cloud width range");
        require(EventRules.cloudHalfHeight(1.0) >= 1.5 && EventRules.cloudHalfHeight(1.0) <= 2.0, "cloud height range");
        require(EventRules.slowBorderTarget(100.0) == 2_500.0, "slow border target");
        require(EventRules.slowBorderTarget(3_000.0) == 3_000.0, "slow border never shrinks");
        require(EventRules.interpolate(10.0, 20.0, 0.0) == 10.0, "interpolation start");
        require(EventRules.interpolate(10.0, 20.0, 1.0) == 20.0, "interpolation end");
        require(EventRules.Phase.values().length == 6, "phase count");
        System.out.println("EventRulesChecks: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

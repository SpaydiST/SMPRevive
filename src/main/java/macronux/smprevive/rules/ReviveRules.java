package macronux.smprevive.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure rules shared by the runtime and dependency-light regression checks. */
public final class ReviveRules {
    public static final int MIN_HEARTS = 1;
    /** Bukkit's max-health attribute tops out at 1024 HP, i.e. 512 plugin hearts. */
    public static final int MAX_HEARTS = 512;
    /** Eight payment slots surround the altar's central head slot. */
    public static final int MAX_TOKEN_STACKS = 8;
    public static final int MAX_STACK_SIZE = 64;
    public static final int MAX_TOKENS_REQUIRED = MAX_TOKEN_STACKS * MAX_STACK_SIZE;
    public static final int MAX_CASINO_DURATION_TICKS = 20 * 60 * 60;

    private ReviveRules() {
    }

    public record HeartRange(int min, int start, int max) {
    }

    public record TimerRange(int durationTicks, int periodTicks) {
    }

    public enum InteractionRoute {
        NONE,
        HEART,
        ALTAR_OPEN,
        ALTAR_BREAK
    }

    public record TokenStack(int slot, String material, int amount) {
        public TokenStack {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must not be negative");
            }
            if (material == null || material.isBlank()) {
                throw new IllegalArgumentException("material is required");
            }
            if (amount <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
        }
    }

    public record TokenSummary(
        int matchingCount,
        int wrongStackCount,
        List<TokenStack> consumed,
        List<TokenStack> remaining
    ) {
        public TokenSummary {
            consumed = List.copyOf(consumed);
            remaining = List.copyOf(remaining);
        }
    }

    public static HeartRange normalizeHearts(int min, int start, int max) {
        int safeMax = clamp(max, MIN_HEARTS, MAX_HEARTS);
        int safeMin = clamp(min, MIN_HEARTS, safeMax);
        int safeStart = clamp(start, safeMin, safeMax);
        return new HeartRange(safeMin, safeStart, safeMax);
    }

    public static TimerRange normalizeCasinoTiming(int durationTicks, int periodTicks) {
        int duration = clamp(durationTicks, 1, MAX_CASINO_DURATION_TICKS);
        int period = clamp(periodTicks, 1, duration);
        return new TimerRange(duration, period);
    }

    public static int normalizeTokensRequired(int tokensRequired) {
        return clamp(tokensRequired, 1, MAX_TOKENS_REQUIRED);
    }

    public static int normalizeSpawnRadius(int radius) {
        return clamp(radius, 0, 128);
    }

    public static boolean shouldInterceptAltarPlacement(boolean hasAltarItem, boolean eventCancelled, String action) {
        return hasAltarItem
            && !eventCancelled
            && "RIGHT_CLICK_BLOCK".equalsIgnoreCase(action);
    }

    public static boolean allowsCustomAltarAction(String gameMode) {
        return gameMode != null
            && !"ADVENTURE".equalsIgnoreCase(gameMode)
            && !"SPECTATOR".equalsIgnoreCase(gameMode);
    }

    public static boolean canUseHeart(boolean itemUseDenied, boolean cooldownActive) {
        return !itemUseDenied && !cooldownActive;
    }

    public static boolean canUseHeart(
        boolean eventCancelled,
        boolean itemUseDenied,
        boolean cooldownActive
    ) {
        // Vanilla air interactions deny the block action even when item use is allowed.
        return canUseHeart(itemUseDenied, cooldownActive);
    }

    public static boolean shouldOpenCasino(
        boolean online,
        boolean dead,
        boolean hasOpenContainer,
        boolean mechanicsDisabled,
        boolean hasToken
    ) {
        return online && !dead && !hasOpenContainer && !mechanicsDisabled && !hasToken;
    }

    public static boolean isValidTokenMaterialName(String materialName) {
        if (materialName == null || materialName.isBlank()) return false;
        String normalized = materialName.trim();
        int separator = normalized.indexOf(':');
        if (separator >= 0 && separator + 1 < normalized.length()) {
            normalized = normalized.substring(separator + 1);
        }
        return !"AIR".equalsIgnoreCase(normalized);
    }

    public static int clampPlayerHearts(int hearts, int maximum) {
        int safeMaximum = clamp(maximum, MIN_HEARTS, MAX_HEARTS);
        return clamp(hearts, MIN_HEARTS, safeMaximum);
    }

    public static boolean isDangerousSpawnMaterial(String materialName) {
        if (materialName == null) return false;
        String normalized = materialName.trim().toUpperCase();
        int separator = normalized.indexOf(':');
        if (separator >= 0 && separator + 1 < normalized.length()) {
            normalized = normalized.substring(separator + 1);
        }
        return switch (normalized) {
            case "LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CACTUS",
                "CAMPFIRE", "SOUL_CAMPFIRE", "SWEET_BERRY_BUSH", "POWDER_SNOW",
                "WITHER_ROSE", "END_PORTAL", "END_GATEWAY" -> true;
            default -> false;
        };
    }

    public static boolean shouldRestoreAltarDisplay(
        boolean isAltarDisplay,
        boolean isRegisteredAltar,
        boolean hasAltarBlock
    ) {
        return isAltarDisplay && isRegisteredAltar && hasAltarBlock;
    }

    public static boolean shouldAwardFirstHeart(int before, int after) {
        return after > before;
    }

    public static boolean shouldAwardMaxHearts(int before, int after, int maximum) {
        return maximum > 0 && before < maximum && after >= maximum;
    }

    public static boolean shouldAwardFirstDeath(boolean alreadyDead) {
        return !alreadyDead;
    }

    public static boolean shouldQueueRevivedAchievement(boolean targetOnline, boolean revivalCompleted) {
        return revivalCompleted;
    }

    public static boolean isDifferentPlayer(UUID first, UUID second) {
        return first != null && second != null && !first.equals(second);
    }

    public static InteractionRoute routeInteraction(boolean hasHeartItem, String action, String hand) {
        boolean mainHand = "HAND".equalsIgnoreCase(hand);
        boolean usableHand = mainHand || "OFF_HAND".equalsIgnoreCase(hand);
        boolean rightClick = "RIGHT_CLICK_AIR".equalsIgnoreCase(action)
            || "RIGHT_CLICK_BLOCK".equalsIgnoreCase(action);
        if (hasHeartItem && rightClick && usableHand) {
            return InteractionRoute.HEART;
        }
        if (mainHand && "RIGHT_CLICK_BLOCK".equalsIgnoreCase(action)) {
            return InteractionRoute.ALTAR_OPEN;
        }
        if (mainHand && "LEFT_CLICK_BLOCK".equalsIgnoreCase(action)) {
            return InteractionRoute.ALTAR_BREAK;
        }
        return InteractionRoute.NONE;
    }

    public static TokenSummary inspectAndConsume(List<TokenStack> stacks, String requiredMaterial, int needed) {
        if (stacks == null) {
            throw new IllegalArgumentException("stacks are required");
        }
        if (requiredMaterial == null || requiredMaterial.isBlank()) {
            throw new IllegalArgumentException("required material is required");
        }

        int matching = 0;
        int wrong = 0;
        for (TokenStack stack : stacks) {
            if (requiredMaterial.equalsIgnoreCase(stack.material())) {
                matching += stack.amount();
            } else {
                wrong++;
            }
        }

        int toConsume = Math.max(0, needed);
        List<TokenStack> consumed = new ArrayList<>();
        List<TokenStack> remaining = new ArrayList<>();
        for (TokenStack stack : stacks) {
            if (requiredMaterial.equalsIgnoreCase(stack.material()) && toConsume > 0) {
                int amount = Math.min(stack.amount(), toConsume);
                consumed.add(new TokenStack(stack.slot(), stack.material(), amount));
                toConsume -= amount;
                if (stack.amount() > amount) {
                    remaining.add(new TokenStack(stack.slot(), stack.material(), stack.amount() - amount));
                }
            } else {
                remaining.add(stack);
            }
        }
        return new TokenSummary(matching, wrong, consumed, remaining);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

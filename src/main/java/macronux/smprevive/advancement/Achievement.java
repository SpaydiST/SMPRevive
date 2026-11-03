package macronux.smprevive.advancement;

public enum Achievement {
    FIRST_HEART("first_heart", "achievement.first_heart"),
    MAX_HEARTS("max_hearts", "achievement.max_hearts"),
    FIRST_DEATH("first_death", "achievement.first_death"),
    ALTAR_PLACED("altar_placed", "achievement.altar_placed"),
    REVIVE_OTHER("revive_other", "achievement.revive_other"),
    REVIVED("revived", "achievement.revived"),
    FAILED_LOVE("failed_love", "achievement.failed_love");

    private final String id;
    private final String messageKey;

    Achievement(String id, String messageKey) {
        this.id = id;
        this.messageKey = messageKey;
    }

    public String id() {
        return id;
    }

    public String messageKey() {
        return messageKey;
    }
}

package macronux.smprevive.advancement;

import macronux.smprevive.SMPRevive;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

public final class AdvancementManager {
    private static final String CRITERION = "unlock";
    private final SMPRevive plugin;
    private final Set<String> missingIds = new HashSet<>();

    public AdvancementManager(SMPRevive plugin) {
        this.plugin = plugin;
    }

    public boolean grant(Player player, Achievement achievement) {
        if (player == null || !player.isOnline() || achievement == null) return false;
        try {
            Advancement advancement = find(achievement.id());
            if (advancement == null) return false;

            awardRoot(player);
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            if (progress.isDone()) return true;

            boolean awarded = progress.awardCriteria(CRITERION);
            return awarded || progress.isDone();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning(
                "Could not grant advancement " + achievement.id() + ": " + exception.getMessage()
            );
            return false;
        }
    }

    private Advancement find(String id) {
        Advancement advancement = Bukkit.getAdvancement(new NamespacedKey(plugin, id));
        if (advancement == null && missingIds.add(id)) {
            plugin.getLogger().warning("Advancement is not loaded: smprevive:" + id);
        }
        return advancement;
    }

    private void awardRoot(Player player) {
        try {
            Advancement root = Bukkit.getAdvancement(new NamespacedKey(plugin, "root"));
            if (root == null) {
                if (missingIds.add("root")) {
                    plugin.getLogger().warning("Advancement is not loaded: smprevive:root");
                }
                return;
            }
            AdvancementProgress progress = player.getAdvancementProgress(root);
            if (!progress.isDone()) progress.awardCriteria(CRITERION);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not award SMPRevive advancement root: " + exception.getMessage());
        }
    }

}

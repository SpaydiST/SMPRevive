/**
 * Owns join, death, respawn, and quit transitions, including kill-streak task cleanup.
 */
package macronux.smprevive.listener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import macronux.smprevive.advancement.Achievement;
import macronux.smprevive.allergy.HeartAllergyRules;
import macronux.smprevive.DataManager;
import macronux.smprevive.gui.GuiEscrow;
import macronux.smprevive.item.ItemManager;
import macronux.smprevive.rules.KillStreakRules;
import macronux.smprevive.rules.ReviveRules;
import macronux.smprevive.SMPRevive;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.persistence.PersistentDataType;

public final class PlayerLifecycleListener implements Listener {
    private final SMPRevive plugin;
    private final HeartUseListener heartUseListener;
    private final java.util.Set<UUID> pendingFirstJoins = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> killStreakBursts = new HashMap<>();

    public PlayerLifecycleListener(SMPRevive plugin, HeartUseListener heartUseListener) {
        this.plugin = plugin;
        this.heartUseListener = heartUseListener;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        initializeOnlinePlayer(event.getPlayer());
    }

    public void initializeOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) initializeOnlinePlayer(player);
    }

    public void initializeOnlinePlayer(Player player) {
        if (player == null || !player.isOnline()) return;
        NamespacedKey newbieWaitingKey = new NamespacedKey(plugin, "newbie_waiting");
        UUID uuid = player.getUniqueId();
        DataManager data = plugin.getDataManager();
        if ((pendingFirstJoins.remove(uuid) || !player.hasPlayedBefore())
            && !player.getPersistentDataContainer().has(newbieWaitingKey, PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().set(newbieWaitingKey, PersistentDataType.BYTE, (byte) 1);
        }
        if (Byte.valueOf((byte)1).equals(player.getPersistentDataContainer().get(newbieWaitingKey, PersistentDataType.BYTE))
            && !data.isDead(uuid)) {
            // Admission must run before recipe, escrow, token or advancement initialization can fail.
            data.setDead(uuid, false);
            data.saveSafely();
            for (long delay : new long[]{1L, 20L, 60L}) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> sendNewbieToLimbo(player), delay);
            }
            return;
        }
        player.discoverRecipe(plugin.getItemManager().altarRecipeKey());
        GuiEscrow.restoreAndSave(plugin, player);
        if (plugin.getRecoveryJournal() != null) {
            plugin.getRecoveryJournal().recover(player, data);
        }
        boolean needsTokenChoice = !data.hasToken(uuid);
        if (needsTokenChoice && !plugin.isMechanicsDisabled(player.getWorld()) && data.ensureToken(uuid)) {
            player.sendMessage(plugin.msg(player, "casino.received").replace("%item%",
                plugin.getMaterialName(player, data.getTokenMaterial(uuid))));
        }
        int hearts = data.getHearts(uuid);
        player.setMaxHealth(plugin.maxHealthForHearts(hearts));
        if (!plugin.isMechanicsDisabled(player.getWorld())
            && hearts >= plugin.getMaxHearts()
            && plugin.getAdvancementManager() != null) {
            plugin.getAdvancementManager().grant(player, Achievement.MAX_HEARTS);
        }

        Location revivalLocation = data.getRevivalSpawnLocation(uuid);
        if (revivalLocation == null && data.hasPendingRevivalSpawn(uuid)) {
            retainPendingRevival(player, data);
            return;
        }
        if (revivalLocation != null) {
            // Keep the destination until the death screen produces PlayerRespawnEvent.
            if (player.isDead()) return;
            if (!completePendingRevival(player, data, revivalLocation)) return;
            player.sendMessage(plugin.msg(player, "revive.offline-welcome"));
            grantPendingRevivedAchievement(player, data);
            return;
        }

        if (data.isDead(uuid)) {
            player.setGameMode(GameMode.SPECTATOR);
            return;
        }

        if (player.getHealth() <= 0.0) {
            player.setHealth(player.getMaxHealth());
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(plugin.getDefaultGameMode());
        }
        grantPendingRevivedAchievement(player, data);
        if (!plugin.isMechanicsDisabled(player.getWorld()) && needsTokenChoice) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                boolean hasOpenContainer = player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING;
                if (ReviveRules.shouldOpenCasino(
                    player.isOnline(),
                    player.isDead() || data.isDead(uuid),
                    hasOpenContainer,
                    plugin.isMechanicsDisabled(player.getWorld()),
                    false
                )) {
                    plugin.getCasinoGui().open(player);
                }
            }, 10L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInitialSpawn(io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent event) {
        // Paper 26.3's legacy spawn event exposes a temporary player before saved data is loaded.
        // This event supplies the actual first-login decision; never inspect a temporary player's PDC.
        if (!event.isNewPlayer()) return;
        UUID uuid = event.getConnection().getProfile().getId();
        if (uuid != null) pendingFirstJoins.add(uuid);
        World limbo = Bukkit.getWorld(new NamespacedKey("spaydiscore", "limbo"));
        if (limbo != null) event.setSpawnLocation(limbo.getSpawnLocation());
        else plugin.getLogger().severe("Newcomer spawn is waiting for SpaydisCore dimension spaydiscore:limbo");
    }

    private void sendNewbieToLimbo(Player player) {
        NamespacedKey newbieWaitingKey = new NamespacedKey(plugin, "newbie_waiting");
        if (player == null || !player.isOnline()
            || !Byte.valueOf((byte)1).equals(player.getPersistentDataContainer().get(newbieWaitingKey, PersistentDataType.BYTE))
            || plugin.getEventManager().isNewbieParticipant(player.getUniqueId())
            || plugin.getDataManager().isDead(player.getUniqueId())) return;
        try {
            World limbo = Bukkit.getWorld(new NamespacedKey("spaydiscore", "limbo"));
            if (limbo == null) throw new IllegalStateException("SpaydisCore limbo dimension is unavailable");
            if (player.getWorld() == limbo) { player.saveData(); return; }
            Location spawn = limbo.getSpawnLocation();
            Location current = player.getLocation();
            spawn.setYaw(current.getYaw());
            spawn.setPitch(current.getPitch());
            if (!player.teleport(spawn)) throw new IllegalStateException("limbo teleport was rejected");
            player.saveData();
        } catch (RuntimeException error) {
            plugin.getLogger().severe("Could not send new player to limbo: " + error.getMessage());
        }
    }

    private void retainPendingRevival(Player player, DataManager data) {
        data.setDead(player.getUniqueId(), true);
        if (!data.saveSafely()) {
            plugin.getLogger().warning("Could not persist pending revival for " + player.getUniqueId());
        }
        player.setGameMode(GameMode.SPECTATOR);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event == null || event.isCancelled()) return;
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        DataManager data = plugin.getDataManager();
        resetKillStreak(uuid, data);
        plugin.getAltarGui().cancelForReviver(uuid, true);
        if (plugin.isMechanicsDisabled(player.getWorld())) {
            heartUseListener.cancelHeartAnimation(uuid, true, true);
            if (!data.saveSafely()) plugin.getLogger().warning("Failed to save kill-streak reset for " + uuid);
            return;
        }
        heartUseListener.cancelHeartAnimation(uuid, true, true);

        ItemManager items = plugin.getItemManager();
        if (!data.ensureToken(uuid)) {
            plugin.getLogger().warning("Could not persist revival material for " + uuid);
        }
        boolean allergic = plugin.getHeartAllergyManager() != null
            && plugin.getHeartAllergyManager().isAllergic(uuid);
        // Allergy prevents creating new heart loot; already owned heart items must survive death.
        Player killer = player.getKiller();
        boolean alreadyDead = data.isDead(uuid);
        UUID killerId = null;
        int previousKillerStreak = -1;
        if (!alreadyDead && killer != null && !killer.getUniqueId().equals(uuid)) {
            killerId = killer.getUniqueId();
            previousKillerStreak = data.getKillStreak(killerId);
            spawnKillCloud(killer);
            int streak = KillStreakRules.increment(previousKillerStreak);
            data.setKillStreak(killerId, streak);
            if (KillStreakRules.shouldSpawnParticles(streak)) {
                startKillStreakBurst(killer, streak);
            }
        }
        if (ReviveRules.shouldAwardFirstDeath(alreadyDead)
            && plugin.getAdvancementManager() != null) {
            plugin.getAdvancementManager().grant(player, Achievement.FIRST_DEATH);
        }
        if (alreadyDead) {
            if (!data.saveSafely()) plugin.getLogger().warning("Failed to save kill-streak reset for dead player " + uuid);
            return;
        }

        if (plugin.isFreeFirstDeath() && !data.hasDied(uuid)) {
            data.setHasDied(uuid, true);
            if (data.saveSafely()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && !data.isDead(uuid)) {
                        String message = plugin.msg(player, "free-revive");
                        player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(message));
                    }
                }, 5L);
                return;
            }
            data.setHasDied(uuid, false);
            plugin.getLogger().warning("Could not persist free-first-death state for " + uuid + "; continuing without custom death state");
        }

        int hearts = data.getHearts(uuid);
        boolean pvp = killer != null && !killer.getUniqueId().equals(uuid);
        int dropped = 0;
        int newHearts = hearts;
        int minimum = plugin.getMinHearts();
        int floor = Math.max(minimum, 9);

        if (HeartAllergyRules.shouldDropHearts(allergic)) {
            if (hearts > floor) {
                dropped = hearts - floor;
                newHearts = floor;
            } else if (hearts > minimum) {
                dropped = 1;
                newHearts = hearts - 1;
            }
        }

        java.util.List<org.bukkit.inventory.ItemStack> issued = new java.util.ArrayList<>();
        java.util.List<UUID> deliveries;
        try {
            issued.add(items.createHead(player));
            if (dropped > 0) {
                org.bukkit.inventory.ItemStack heartsStack = items.createHeart();
                // Stackable hearts are delivered as complete batches, then lose the temporary delivery tag.
                while (dropped > 0) {
                    var stack = heartsStack.clone();
                    stack.setAmount(Math.min(64, dropped));
                    issued.add(stack);
                    dropped -= stack.getAmount();
                }
            }
            deliveries = plugin.getProtectedItemService().stageDrops(player.getLocation(), issued);
        } catch (RuntimeException error) {
            if (killerId != null) { data.setKillStreak(killerId, previousKillerStreak); cancelKillStreakBurst(killerId); }
            plugin.getLogger().severe("Cannot stage death issuance: " + error.getMessage());
            return;
        }
        int droppedCount = issued.stream().filter(items::isHeart).mapToInt(org.bukkit.inventory.ItemStack::getAmount).sum();
        data.setHearts(uuid, newHearts);
        data.setDead(uuid, true);
        // Keep the death transition atomic: failed persistence restores the old heart count and streak.
        if (!data.saveSafely()) {
            data.setHearts(uuid, hearts);
            data.setDead(uuid, false);
            plugin.getProtectedItemService().unstage(deliveries);
            if (killerId != null) data.setKillStreak(killerId, previousKillerStreak);
            if (killerId != null) cancelKillStreakBurst(killerId);
            plugin.getLogger().warning("Could not persist death state for " + uuid + "; skipping custom death drops");
            return;
        }

        dropped = droppedCount;
        // The persisted queue owns issuance; the next service pulse creates protected drops idempotently.
        player.setGameMode(GameMode.SPECTATOR);

        String deathMessage;
        if (allergic) {
            deathMessage = plugin.msg(player, "death.allergic").replace("%player%", player.getName());
        } else if (pvp) {
            deathMessage = plugin.msg(player, "death.pvp")
                .replace("%player%", player.getName())
                .replace("%killer%", killer.getName())
                .replace("%hearts%", String.valueOf(dropped));
        } else if (dropped > 0) {
            deathMessage = plugin.msg(player, "death.dropped")
                .replace("%player%", player.getName())
                .replace("%hearts%", String.valueOf(dropped));
        } else {
            deathMessage = plugin.msg(player, "death.none").replace("%player%", player.getName());
        }
        player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(deathMessage));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        DataManager data = plugin.getDataManager();
        UUID uuid = player.getUniqueId();
        Location revivalLocation = data.getRevivalSpawnLocation(uuid);

        if (revivalLocation == null && data.hasPendingRevivalSpawn(uuid)) {
            retainPendingRevival(player, data);
            return;
        }
        if (revivalLocation != null) {
            if (!isSafePendingRevivalLocation(player, revivalLocation)) {
                retainPendingRevival(player, data);
                return;
            }
            event.setRespawnLocation(revivalLocation);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (completePendingRevival(player, data, revivalLocation)) {
                    grantPendingRevivedAchievement(player, data);
                }
            });
            return;
        }

        if (data.isDead(uuid)) {
            player.setGameMode(GameMode.SPECTATOR);
        }
    }

    private boolean isSafePendingRevivalLocation(Player player, Location revivalLocation) {
        if (plugin.getAltarGui() != null
            && !plugin.isMechanicsDisabled(revivalLocation)
            && plugin.getAltarGui().isSafeSpawnLocation(revivalLocation)) return true;
        plugin.getLogger().warning("Saved revival location for " + player.getName() + " is no longer safe");
        return false;
    }

    private boolean completePendingRevival(Player player, DataManager data, Location revivalLocation) {
        if (!isSafePendingRevivalLocation(player, revivalLocation)) {
            retainPendingRevival(player, data);
            return false;
        }
        UUID uuid = player.getUniqueId();
        boolean completed = applyPendingRevival(
            player,
            revivalLocation,
            plugin.getDefaultGameMode(),
            plugin.maxHealthForHearts(data.getHearts(uuid)),
            data,
            uuid,
            message -> plugin.getLogger().warning("Pending revival for " + uuid + ": " + message)
        );
        if (!completed) {
            plugin.getLogger().warning("Could not complete saved revival for " + player.getName() + "; it remains pending");
        }
        return completed;
    }

    static boolean applyPendingRevival(
        Player player,
        Location revivalLocation,
        GameMode defaultGameMode,
        double maxHealth,
        DataManager data,
        UUID uuid,
        Consumer<String> warning
    ) {
        Location origin = player.getLocation();
        origin = origin == null ? null : origin.clone();
        Location pendingDestination = revivalLocation.clone();
        try {
            // Core uses this flag to decide whether a teleport may leave limbo.
            data.setDead(uuid, false);
            player.setGameMode(defaultGameMode);
            player.setMaxHealth(maxHealth);
            player.setHealth(maxHealth);
            player.setFoodLevel(20);
            player.setSaturation(10.0f);
            if (!player.teleport(pendingDestination.clone())) {
                throw new IllegalStateException("teleport was rejected");
            }
            player.setFallDistance(0.0f);
            data.clearRevivalSpawnLocation(uuid);
            if (!data.saveSafely()) throw new IllegalStateException("players.yml could not be saved");
            return true;
        } catch (RuntimeException failure) {
            warn(warning, "revival failed: " + failure.getMessage());
            // Restore dead before teleporting back; Core must not treat rollback as an exit.
            try { data.setDead(uuid, true); }
            catch (RuntimeException rollbackFailure) { warn(warning, "could not restore dead state: " + rollbackFailure.getMessage()); }
            try { data.setRevivalSpawnLocation(uuid, pendingDestination.clone()); }
            catch (RuntimeException rollbackFailure) { warn(warning, "could not restore pending location: " + rollbackFailure.getMessage()); }
            try {
                if (!data.saveSafely()) warn(warning, "could not persist pending revival rollback");
            } catch (RuntimeException rollbackFailure) {
                warn(warning, "could not save pending revival rollback: " + rollbackFailure.getMessage());
            }
            try {
                if (origin != null && !player.teleport(origin)) warn(warning, "rollback teleport was rejected");
            } catch (RuntimeException rollbackFailure) {
                warn(warning, "rollback teleport failed: " + rollbackFailure.getMessage());
            }
            try { player.setGameMode(GameMode.SPECTATOR); }
            catch (RuntimeException rollbackFailure) { warn(warning, "could not restore spectator state: " + rollbackFailure.getMessage()); }
            try {
                player.setMaxHealth(maxHealth);
                player.setHealth(maxHealth);
                player.setFoodLevel(20);
                player.setSaturation(10.0f);
            } catch (RuntimeException rollbackFailure) {
                warn(warning, "could not restore player health: " + rollbackFailure.getMessage());
            }
            return false;
        }
    }

    private static void warn(Consumer<String> warning, String message) {
        if (warning == null) return;
        try { warning.accept(message); }
        catch (RuntimeException ignored) { }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cancelKillStreakBurst(uuid);
        heartUseListener.cancelHeartAnimation(uuid, true, true);
        plugin.getAltarGui().cancelForReviver(uuid, true);
    }

    private void grantPendingRevivedAchievement(Player player, DataManager data) {
        if (player == null || data == null || plugin.getAdvancementManager() == null) return;
        UUID uuid = player.getUniqueId();
        if (!data.hasPendingRevivedAchievement(uuid)) return;
        if (plugin.getAdvancementManager().grant(player, Achievement.REVIVED)) {
            data.setPendingRevivedAchievement(uuid, false);
            if (!data.saveSafely()) {
                data.setPendingRevivedAchievement(uuid, true);
                plugin.getLogger().warning("Could not persist completed revived achievement for " + uuid);
            }
        }
    }

    private void resetKillStreak(UUID uuid, DataManager data) {
        cancelKillStreakBurst(uuid);
        if (data != null) data.setKillStreak(uuid, 0);
    }

    private void startKillStreakBurst(Player player, int streak) {
        if (player == null || !player.isOnline() || !KillStreakRules.shouldSpawnParticles(streak)) return;

        UUID uuid = player.getUniqueId();
        cancelKillStreakBurst(uuid);
        int skullCount = KillStreakRules.skullCount(streak);

        BukkitTask task = new BukkitRunnable() {
            private int tick;
            private int remainingSkulls = skullCount;
            private int remainingTicks = KillStreakRules.BURST_TICKS;
            private int spawnedSkulls;

            @Override
            public void run() {
                if (!player.isOnline()
                    || player.isDead()
                    || plugin.isMechanicsDisabled(player.getWorld())) {
                    cancelKillStreakBurst(uuid);
                    return;
                }

                Location center = player.getLocation().clone().add(
                    0,
                    player.getHeight() + 0.35 + tick * 0.12,
                    0
                );
                if (center.getWorld() != null) {
                    int particlesThisTick = KillStreakRules.skullsForBurstTick(remainingSkulls, remainingTicks);
                    for (int i = 0; i < particlesThisTick; i++) {
                        int particleIndex = spawnedSkulls + i;
                        double angle = (Math.PI * 2.0 * particleIndex / Math.max(1, skullCount)) + tick * 0.25;
                        double radius = 0.18 + (particleIndex % 3) * 0.08;
                        Location skullLocation = center.clone().add(
                            Math.cos(angle) * radius,
                            (particleIndex % 2) * 0.06,
                            Math.sin(angle) * radius
                        );
                        center.getWorld().spawnParticle(
                            Particle.PAUSE_MOB_GROWTH,
                            skullLocation,
                            1,
                            0.0,
                            0.0,
                            0.0,
                            0.0
                        );
                    }
                    spawnedSkulls += particlesThisTick;
                    remainingSkulls -= particlesThisTick;
                }

                remainingTicks--;
                tick++;
                if (remainingSkulls <= 0 || remainingTicks <= 0) {
                    cancelKillStreakBurst(uuid);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        killStreakBursts.put(uuid, task);
    }

    private void spawnKillCloud(Player player) {
        if (player == null || !player.isOnline() || plugin.isMechanicsDisabled(player.getWorld())) return;
        Location cloud = player.getLocation().clone().add(0, player.getHeight() + 0.35, 0);
        if (cloud.getWorld() == null) return;
        cloud.getWorld().spawnParticle(
            Particle.SQUID_INK,
            cloud,
            KillStreakRules.killCloudParticleCount(),
            0.18,
            0.1,
            0.18,
            0.01
        );
    }

    private void cancelKillStreakBurst(UUID uuid) {
        if (uuid == null) return;
        BukkitTask task = killStreakBursts.remove(uuid);
        if (task != null) task.cancel();
    }

    public void shutdownRuntime() {
        // Cancel owned repeating tasks before the plugin tears down GUI and world state.
        for (BukkitTask task : new ArrayList<>(killStreakBursts.values())) {
            task.cancel();
        }
        killStreakBursts.clear();
    }
}

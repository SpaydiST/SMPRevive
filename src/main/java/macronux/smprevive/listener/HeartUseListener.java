/**
 * Owns heart item use, animation state, payment journals, refunds, and heart task cleanup.
 */
package macronux.smprevive.listener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import macronux.smprevive.advancement.Achievement;
import macronux.smprevive.advancement.AdvancementManager;
import macronux.smprevive.allergy.HeartAllergyRules;
import macronux.smprevive.DataManager;
import macronux.smprevive.recovery.RecoveryJournal;
import macronux.smprevive.rules.ReviveRules;
import macronux.smprevive.SMPRevive;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.GameMode;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class HeartUseListener implements Listener {
    private static final double HEART_BODY_OFFSET_Y = 1.0;
    private final SMPRevive plugin;
    private final Map<UUID, HeartAnimation> heartAnimations = new HashMap<>();

    public HeartUseListener(SMPRevive plugin) {
        this.plugin = plugin;
    }

    private Location heartBodyLocation(Player player) {
        Location body = player.getLocation().clone();
        body.setYaw(player.getBodyYaw());
        body.setPitch(0.0f);
        return body.add(0, HEART_BODY_OFFSET_Y, 0);
    }

    private boolean sameWorld(Location first, Location second) {
        return first != null
            && second != null
            && first.getWorld() != null
            && first.getWorld().equals(second.getWorld());
    }

    private Location createAllergyLandingLocation(Player player) {
        Location origin = player.getLocation().clone();
        double distance = HeartAllergyRules.allergicLandingDistance(ThreadLocalRandom.current().nextDouble());
        double angle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
        Location landing = origin.add(Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance);
        landing.setY(landing.getWorld().getHighestBlockYAt(landing.getBlockX(), landing.getBlockZ()) + 1.0);
        return landing;
    }

    public void useHeart(Player player, EquipmentSlot hand) {
        UUID uuid = player.getUniqueId();
        DataManager data = plugin.getDataManager();
        if (player.getGameMode() == GameMode.SPECTATOR || data.isDead(uuid)) return;
        int hearts = data.getHearts(uuid);
        int maximum = plugin.getMaxHearts();
        if (hearts >= maximum) {
            String message = plugin.msg(player, "heart.max").replace("%max%", String.valueOf(maximum));
            player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(message));
            return;
        }

        ItemStack inHand = hand == EquipmentSlot.OFF_HAND
            ? player.getInventory().getItemInOffHand()
            : player.getInventory().getItemInMainHand();
        if (inHand == null || !plugin.getItemManager().isHeart(inHand)) return;

        ItemStack sourceSnapshot = inHand.clone();
        ItemStack refund = inHand.clone();
        refund.setAmount(1);
        boolean allergic = plugin.getHeartAllergyManager() != null
            && plugin.getHeartAllergyManager().isAllergic(uuid);
        Location animationStart = heartBodyLocation(player);
        Location allergyLanding = allergic ? createAllergyLandingLocation(player) : null;
        Location refundLocation = allergyLanding == null ? player.getLocation() : allergyLanding;
        // Journal the payment before consuming it so shutdown or a failed save can refund exactly one heart.
        RecoveryJournal journal = plugin.getRecoveryJournal();
        RecoveryJournal.Operation journalOperation = journal == null ? null : journal.begin(
            RecoveryJournal.Type.HEART,
            uuid,
            uuid,
            hearts,
            data.isDead(uuid),
            null,
            refundLocation,
            List.of(new RecoveryJournal.Payment(
                hand == EquipmentSlot.OFF_HAND ? "OFF_HAND" : "MAIN_HAND",
                sourceSnapshot,
                refund
            ))
        );
        if (journal != null && journalOperation == null) {
            player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(
                plugin.msg(player, "heart.error")
            ));
            return;
        }
        if (inHand.getAmount() > 1) {
            inHand.setAmount(inHand.getAmount() - 1);
        } else if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(null);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
        if (journal != null && !journal.markConsumed(journalOperation, player)) {
            journal.abort(journalOperation, false, player, player.getLocation());
            player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(
                plugin.msg(player, "heart.error")
            ));
            return;
        }

        HeartAnimation animation = new HeartAnimation(
            uuid,
            player,
            hearts,
            refund,
            refundLocation,
            journalOperation,
            allergic,
            animationStart,
            allergyLanding
        );
        heartAnimations.put(uuid, animation);

        try {
            ItemDisplay display = player.getWorld().spawn(animation.startLocation.clone(), ItemDisplay.class);
            display.setItemStack(plugin.getItemManager().createHeart());
            display.setPersistent(false);
            display.setViewRange(10.0f);
            animation.display = display;
            plugin.playSound(display.getLocation(), "heart.start");
            plugin.playSoundToPlayer(player, "heart.start");

            animation.task = new BukkitRunnable() {
                int tick;
                final int totalTicks = 50;
                final int riseTicks = 35;
                final int flyInTicks = 15;

                @Override
                public void run() {
                    if (!isActive(animation) || !player.isOnline() || player.isDead()
                        || player.getGameMode() == GameMode.SPECTATOR
                        || plugin.isMechanicsDisabled(player.getWorld())
                        || data.isDead(uuid)
                        || data.getHearts(uuid) != animation.startHearts
                        || !sameWorld(player.getLocation(), animation.startLocation)
                        || (animation.allergyLandingLocation != null
                            && !sameWorld(animation.startLocation, animation.allergyLandingLocation))
                        || display.isDead()) {
                        cancel();
                        finishHeart(animation, true, false);
                        return;
                    }

                    tick++;
                    if (animation.allergic && tick <= totalTicks) {
                        double progress = Math.min(1.0, (double) tick / totalTicks);
                        double eased = progress * progress * (3.0 - 2.0 * progress);
                        Location start = animation.startLocation;
                        Location landing = animation.allergyLandingLocation;
                        double x = start.getX() + (landing.getX() - start.getX()) * eased;
                        double y = start.getY() + (landing.getY() - start.getY()) * eased
                            + Math.sin(Math.PI * progress) * 2.8;
                        double z = start.getZ() + (landing.getZ() - start.getZ()) * eased;
                        Location position = new Location(start.getWorld(), x, y, z);
                        if (!HeartAllergyRules.shouldStutterAnimation(animation.allergic, tick)) {
                            float angle = (float) (progress * Math.PI * 2.0);
                            float scale = 0.8f * (1.0f - (float) progress * 0.25f);
                            display.setTransformation(new Transformation(
                                new Vector3f(),
                                new Quaternionf(0, (float) Math.sin(angle), 0, (float) Math.cos(angle)),
                                new Vector3f(scale, scale, scale),
                                new Quaternionf()
                            ));
                            display.teleport(position);
                        }
                        if (tick % 2 == 0) {
                            player.getWorld().spawnParticle(Particle.ENCHANT, position.clone().add(0, 0.3, 0), 6, 0.3, 0.3, 0.3, 0.08);
                        }
                        if (tick % 3 == 0) {
                            plugin.playSound(position, "heart.rise");
                            plugin.playSoundToPlayer(player, "heart.rise");
                        }
                        if (tick == 1) {
                            plugin.playSound(position, "heart.start_pickup");
                            plugin.playSoundToPlayer(player, "heart.start_pickup");
                        }
                    } else if (!animation.allergic && tick <= riseTicks) {
                        Location base = heartBodyLocation(player);
                        float progress = (float) tick / riseTicks;
                        float ease = 0.5f - 0.5f * (float) Math.cos(Math.PI * progress);
                        float height = ease * 3.0f;
                        float angle = (float) (ease * Math.PI * 2.0);
                        if (!HeartAllergyRules.shouldStutterAnimation(animation.allergic, tick)) {
                            display.setTransformation(new Transformation(
                                new Vector3f(0, height, 0),
                                new Quaternionf(0, (float) Math.sin(angle), 0, (float) Math.cos(angle)),
                                new Vector3f(0.8f, 0.8f, 0.8f),
                                new Quaternionf()
                            ));
                            display.teleport(base);
                        }
                        if (tick % 3 == 0) {
                            Location particle = base.clone().add(0, height + 0.5, 0);
                            player.getWorld().spawnParticle(Particle.ENCHANT, particle, 4, 0.3, 0.3, 0.3, 0.05);
                            plugin.playSound(base, "heart.rise");
                            plugin.playSoundToPlayer(player, "heart.rise");
                        }
                        if (tick == 1) {
                            plugin.playSound(base, "heart.start_pickup");
                            plugin.playSoundToPlayer(player, "heart.start_pickup");
                        }
                    } else if (!animation.allergic && tick <= totalTicks) {
                        Location base = heartBodyLocation(player);
                        float progress = (float) (tick - riseTicks) / flyInTicks;
                        float ease = progress * progress;
                        float height = 3.0f * (1.0f - ease);
                        float scale = 0.8f * (1.0f - ease * 0.5f);
                        if (!HeartAllergyRules.shouldStutterAnimation(animation.allergic, tick)) {
                            display.setTransformation(new Transformation(
                                new Vector3f(0, height, 0),
                                new Quaternionf(),
                                new Vector3f(scale, scale, scale),
                                new Quaternionf()
                            ));
                            display.teleport(base);
                        }
                        if (tick % 2 == 0) {
                            player.getWorld().spawnParticle(Particle.ENCHANT, base.clone().add(0, height + 0.3, 0), 6, 0.3, 0.3, 0.3, 0.08);
                        }
                        } else {
                        cancel();
                        if (!isActive(animation) || data.isDead(uuid) || data.getHearts(uuid) != animation.startHearts) {
                            finishHeart(animation, true, false);
                            return;
                        }
                        if (animation.allergic) {
                            ItemStack failedHeart = animation.refundItem == null ? null : animation.refundItem.clone();
                            var journal = plugin.getRecoveryJournal();
                            UUID operationId = animation.journalOperation == null ? null : animation.journalOperation.id();
                            boolean previousCompletion = data.hasCompletedOperation(operationId);
                            java.util.List<UUID> deliveries = java.util.List.of();
                            try {
                                if (failedHeart != null) deliveries = plugin.getProtectedItemService().stageDrops(
                                    animation.allergyLandingLocation, java.util.List.of(failedHeart));
                                data.setCompletedOperation(operationId, true);
                                if (!data.saveSafely()) throw new IllegalStateException("Could not save allergic heart delivery");
                            } catch (RuntimeException error) {
                                plugin.getProtectedItemService().unstage(deliveries);
                                data.setCompletedOperation(operationId, previousCompletion);
                                finishHeart(animation, true, false);
                                return;
                            }
                            if (journal != null && animation.journalOperation != null) journal.commit(animation.journalOperation);
                            animation.refundItem = null;
                            finishHeart(animation, false, false);
                            try {
                                plugin.getHeartAllergyManager().triggerFailedHeartUse(player, animation.allergyLandingLocation, null);
                            } catch (RuntimeException error) {
                                plugin.getLogger().warning("Allergic heart effect failed; item delivery is safely queued: " + error.getMessage());
                            }
                            return;
                        }

                        int newHearts = animation.startHearts + 1;
                        UUID completionId = animation.journalOperation == null ? null : animation.journalOperation.id();
                        boolean beforeCompletion = data.hasCompletedOperation(completionId);
                        boolean durableSuccess = false;
                        try {
                            player.setMaxHealth(plugin.maxHealthForHearts(newHearts));
                            data.setHearts(uuid, newHearts);
                            data.setCompletedOperation(completionId, true);
                            if (!data.saveSafely()) {
                                throw new IllegalStateException("players.yml was not saved");
                            }
                            durableSuccess = true;
                            if (plugin.getRecoveryJournal() != null && animation.journalOperation != null) {
                                if (!plugin.getRecoveryJournal().commit(animation.journalOperation)) {
                                    plugin.getLogger().warning("Heart succeeded but recovery journal cleanup was deferred for " + uuid);
                                }
                            }
                            finishHeart(animation, false, false);
                        } catch (RuntimeException ex) {
                            plugin.getLogger().warning("Failed to commit heart operation: " + ex.getMessage());
                            if (durableSuccess) {
                                finishHeart(animation, false, false);
                                return;
                            }
                            data.setHearts(uuid, animation.startHearts);
                            data.setCompletedOperation(completionId, beforeCompletion);
                            if (!data.saveSafely()) {
                                plugin.getLogger().warning("Failed to roll back heart data for " + uuid);
                            }
                            try {
                                player.setMaxHealth(plugin.maxHealthForHearts(animation.startHearts));
                            } catch (RuntimeException rollbackError) {
                                plugin.getLogger().warning("Failed to roll back heart max health: " + rollbackError.getMessage());
                            }
                            finishHeart(animation, true, false);
                            return;
                        }

                        AdvancementManager advancements = plugin.getAdvancementManager();
                        if (advancements != null) {
                            if (ReviveRules.shouldAwardFirstHeart(animation.startHearts, newHearts)) {
                                advancements.grant(player, Achievement.FIRST_HEART);
                            }
                            if (ReviveRules.shouldAwardMaxHearts(
                                animation.startHearts,
                                newHearts,
                                plugin.getMaxHearts()
                            )) {
                                advancements.grant(player, Achievement.MAX_HEARTS);
                            }
                        }

                        Location chest = heartBodyLocation(player);
                        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, chest, 30, 0.6, 0.6, 0.6, 0.8);
                        player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().clone().add(0, 1, 0), 50, 0.8, 1.5, 0.8, 0.5);
                        player.getWorld().spawnParticle(Particle.HEART, chest, 15, 0.5, 0.5, 0.5);
                        plugin.playSound(chest, "heart.finish_levelup");
                        plugin.playSound(chest, "heart.finish_totem");
                        plugin.playSoundToPlayer(player, "heart.finish_levelup");
                        plugin.playSoundToPlayer(player, "heart.finish_totem");
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);
            player.setCooldown(plugin.getHeartMaterial(), 100);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Failed to start heart animation: " + ex.getMessage());
            finishHeart(animation, true, false);
        }
    }

    public void cancelHeartAnimation(UUID uuid, boolean refund) {
        cancelHeartAnimation(uuid, refund, false);
    }

    public void cancelHeartAnimation(UUID uuid, boolean refund, boolean forceDrop) {
        HeartAnimation animation = heartAnimations.get(uuid);
        if (animation != null) finishHeart(animation, refund, forceDrop);
    }

    private boolean isActive(HeartAnimation animation) {
        return animation != null && !animation.cleaned && heartAnimations.get(animation.uuid) == animation;
    }

    public boolean isHeartAnimationActive(UUID uuid) {
        return uuid != null && this.heartAnimations.containsKey(uuid);
    }

    private void finishHeart(HeartAnimation animation, boolean refund, boolean forceDrop) {
        if (animation == null || animation.cleaned) return;
        // Claim cleanup before cancelling tasks or refunding; callbacks can re-enter this path.
        animation.cleaned = true;
        if (animation.task != null) animation.task.cancel();
        if (animation.display != null && !animation.display.isDead()) animation.display.remove();
        heartAnimations.remove(animation.uuid, animation);

        if (!refund || animation.refundItem == null) return;
        Player player = animation.player;
        if (player != null) player.setCooldown(plugin.getHeartMaterial(), 0);
        if (plugin.getRecoveryJournal() != null && animation.journalOperation != null) {
            Player refundPlayer = forceDrop ? null : player;
            Location dropLocation = player == null ? animation.refundLocation : player.getLocation();
            plugin.getRecoveryJournal().abort(
                animation.journalOperation,
                false,
                refundPlayer,
                dropLocation
            );
            animation.refundItem = null;
            return;
        }
        if (forceDrop && player != null && player.getWorld() != null) {
            player.getWorld().dropItemNaturally(player.getLocation(), animation.refundItem.clone());
            animation.refundItem = null;
            return;
        }
        if (player != null && player.isOnline()) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(animation.refundItem.clone());
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        } else if (animation.refundLocation != null && animation.refundLocation.getWorld() != null) {
            animation.refundLocation.getWorld().dropItemNaturally(animation.refundLocation, animation.refundItem.clone());
        }
        animation.refundItem = null;
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;
        if (plugin.getItemManager().isHeart(item.getItemStack())) {
            plugin.getItemManager().makeHeartPersistent(item);
        }
    }

    private static final class HeartAnimation {
        private final UUID uuid;
        private final Player player;
        private final int startHearts;
        private ItemStack refundItem;
        private final Location refundLocation;
        private final RecoveryJournal.Operation journalOperation;
        private final boolean allergic;
        private final Location startLocation;
        private final Location allergyLandingLocation;
        private ItemDisplay display;
        private BukkitTask task;
        private boolean cleaned;

        private HeartAnimation(
            UUID uuid,
            Player player,
            int startHearts,
            ItemStack refundItem,
            Location refundLocation,
            RecoveryJournal.Operation journalOperation,
            boolean allergic,
            Location startLocation,
            Location allergyLandingLocation
        ) {
            this.uuid = uuid;
            this.player = player;
            this.startHearts = startHearts;
            this.refundItem = refundItem;
            this.refundLocation = refundLocation;
            this.journalOperation = journalOperation;
            this.allergic = allergic;
            this.startLocation = startLocation.clone();
            this.allergyLandingLocation = allergyLandingLocation == null ? null : allergyLandingLocation.clone();
        }
    }

    public void shutdownRuntime() {
        for (HeartAnimation animation : new ArrayList<>(heartAnimations.values())) {
            finishHeart(animation, true, false);
        }
        heartAnimations.clear();
    }
}

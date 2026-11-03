package macronux.smprevive.allergy;

import macronux.smprevive.ReviveListener;
import macronux.smprevive.SMPRevive;
import macronux.smprevive.advancement.Achievement;
import macronux.smprevive.advancement.AdvancementManager;
import macronux.smprevive.data.AllergyStore;
import macronux.smprevive.item.ItemManager;
import macronux.smprevive.rules.ReviveRules;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Owns the opt-in heart-allergy state and its periodic inventory enforcement.
 * The state is deliberately separate from the reference plugin's allergies.yml
 * schema so both plugins can safely coexist in one server folder.
 */
public final class HeartAllergyManager implements Listener {
    private static final double FAILURE_EXPLOSION_RADIUS = 4.5;
    private static final double FAILURE_EXPLOSION_VERTICAL_RADIUS = 3.0;
    private final SMPRevive plugin;
    private final AllergyStore store;
    private BukkitTask scanTask;
    private boolean listenersRegistered;
    private final Set<UUID> onlineAllergic = new HashSet<>();

    public HeartAllergyManager(SMPRevive plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        this.store = new AllergyStore(new File(plugin.getDataFolder(), "allergies.yml"), plugin.getLogger());
    }

    public void start() {
        if (this.scanTask != null && !this.scanTask.isCancelled()) return;
        if (!listenersRegistered) { Bukkit.getPluginManager().registerEvents(this, plugin); listenersRegistered = true; }
        onlineAllergic.clear();
        for (Player player : Bukkit.getOnlinePlayers()) if (isAllergic(player.getUniqueId())) onlineAllergic.add(player.getUniqueId());
        this.scanTask = Bukkit.getScheduler().runTaskTimer(
            this.plugin,
            this::scanOnlinePlayers,
            10L,
            10L
        );
    }

    public void shutdown() {
        if (this.scanTask != null) {
            this.scanTask.cancel();
            this.scanTask = null;
        }
        onlineAllergic.clear();
        store.save();
    }

    public boolean isAllergic(UUID playerId) {
        return playerId != null && store.isAllergic(playerId);
    }

    public Set<UUID> allergicPlayers() {
        return store.players();
    }

    public boolean setAllergic(UUID playerId, boolean allergic) {
        if (!store.setAllergic(playerId, allergic)) return false;
        Player player = Bukkit.getPlayer(playerId);
        if (allergic && player != null && player.isOnline()) onlineAllergic.add(playerId);
        else onlineAllergic.remove(playerId);
        return true;
    }

    public int heartsForRevival(UUID playerId, int currentHearts, int maximum) {
        if (!isAllergic(playerId)) {
            return ReviveRules.clampPlayerHearts(currentHearts, maximum);
        }
        int offset = ThreadLocalRandom.current().nextInt(-3, 4);
        return HeartAllergyRules.revivalHearts(offset, maximum);
    }

    public void scanPlayer(Player player) {
        if (player == null || !player.isOnline() || plugin.isMechanicsDisabled(player.getWorld())) return;
        UUID playerId = player.getUniqueId();
        if (!isAllergic(playerId)) return;
        ReviveListener listener = plugin.getReviveListener();
        if (listener != null && listener.isHeartAnimationActive(playerId)) return;

        List<ItemStack> hearts = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (plugin.getItemManager().isHeart(item)) hearts.add(item.clone());
        }
        if (!hearts.isEmpty() && plugin.getProtectedItemService().transferFromPlayer(
            player, hearts, () -> removeHeartItems(player))) sneeze(player, hearts);
    }

    public void triggerFailedHeartUse(Player player, Location impact, ItemStack heart) {
        if (player == null || !player.isOnline() || plugin.isMechanicsDisabled(player.getWorld())) return;
        Location location = impact == null ? player.getLocation().clone() : impact.clone();
        World world = location.getWorld();
        if (world == null || !world.equals(player.getWorld())) {
            world = player.getWorld();
            location = player.getLocation().clone();
        }

        if (heart != null && heart.getAmount() > 0) {
            ItemStack droppedStack = heart.clone();
            droppedStack.setAmount(1);
            if (!plugin.getProtectedItemService().queueDrops(location, List.of(droppedStack)))
                throw new IllegalStateException("Could not queue failed heart use");
        }

        world.strikeLightningEffect(location);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, location, 1);
        world.spawnParticle(Particle.SMOKE, location, 10, 0.35, 0.45, 0.35, 0.02);

        for (Entity entity : world.getNearbyEntities(
            location,
            FAILURE_EXPLOSION_RADIUS,
            FAILURE_EXPLOSION_VERTICAL_RADIUS,
            FAILURE_EXPLOSION_RADIUS
        )) {
            if (!(entity instanceof LivingEntity target) || target.isDead()) continue;
            if (target == player) continue;
            double distance = target.getLocation().distance(location);
            double damage = HeartAllergyRules.failureExplosionDamage(distance, FAILURE_EXPLOSION_RADIUS);
            if (damage > 0.0) {
                double safeDamage = target instanceof Player
                    ? HeartAllergyRules.failureDamage(target.getHealth())
                    : Math.min(damage, Math.max(0.0, target.getHealth()));
                if (safeDamage > 0.0) target.damage(safeDamage);
                if (target instanceof Player affectedPlayer
                    && affectedPlayer.isOnline()
                    && !affectedPlayer.isDead()
                    && affectedPlayer.getHealth() < 1.0) {
                    affectedPlayer.setHealth(1.0);
                }
            }

            if (target.isDead()) continue;
            double knockback = HeartAllergyRules.failureExplosionKnockback(distance, FAILURE_EXPLOSION_RADIUS);
            if (knockback <= 0.0) continue;
            Vector direction = target.getLocation().toVector().subtract(location.toVector()).setY(0.0);
            if (direction.lengthSquared() < 0.0001) direction = new Vector(1.0, 0.0, 0.0);
            direction.normalize().multiply(knockback).setY(0.55);
            target.setVelocity(target.getVelocity().multiply(0.25).add(direction));
        }

        double playerDistance = player.getLocation().distance(location);
        double playerDamage = HeartAllergyRules.failureExplosionDamage(playerDistance, FAILURE_EXPLOSION_RADIUS);
        if (playerDamage > 0.0) {
            double safeDamage = HeartAllergyRules.failureDamage(player.getHealth());
            if (safeDamage > 0.0) player.damage(safeDamage);
            if (player.isOnline() && !player.isDead() && player.getHealth() < 1.0) {
                player.setHealth(1.0);
            }
        }
        double playerKnockback = HeartAllergyRules.failureExplosionKnockback(playerDistance, FAILURE_EXPLOSION_RADIUS);
        if (playerKnockback > 0.0 && !player.isDead()) {
            Vector direction = player.getLocation().toVector().subtract(location.toVector()).setY(0.0);
            if (direction.lengthSquared() < 0.0001) direction = new Vector(1.0, 0.0, 0.0);
            direction.normalize().multiply(playerKnockback).setY(0.55);
            player.setVelocity(player.getVelocity().multiply(0.25).add(direction));
        }

        AdvancementManager advancements = plugin.getAdvancementManager();
        if (advancements != null) advancements.grant(player, Achievement.FAILED_LOVE);
        player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(
            plugin.msg(player, "heart-allergy.failure")
        ));
    }

    private void scanOnlinePlayers() {
        long start = System.nanoTime();
        try {
            for (UUID id : new ArrayList<>(onlineAllergic)) {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()) { onlineAllergic.remove(id); continue; }
                plugin.getPerformanceCounters().increment("allergy-player-visits");
                scanPlayer(player);
            }
        } finally { plugin.getPerformanceCounters().record("allergy-scan", start); }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (isAllergic(id)) onlineAllergic.add(id);
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { onlineAllergic.remove(event.getPlayer().getUniqueId()); }

    private List<ItemStack> removeHeartItems(Player player) {
        ItemManager items = plugin.getItemManager();
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> removed = new ArrayList<>();

        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (!items.isHeart(item)) continue;
            removed.add(item.clone());
            storage[slot] = null;
        }
        inventory.setStorageContents(storage);

        ItemStack offHand = inventory.getItemInOffHand();
        if (items.isHeart(offHand)) {
            removed.add(offHand.clone());
            inventory.setItemInOffHand(null);
        }

        ItemStack[] armor = inventory.getArmorContents();
        boolean armorChanged = false;
        for (int slot = 0; slot < armor.length; slot++) {
            if (!items.isHeart(armor[slot])) continue;
            removed.add(armor[slot].clone());
            armor[slot] = null;
            armorChanged = true;
        }
        if (armorChanged) inventory.setArmorContents(armor);
        return removed;
    }

    private void sneeze(Player player, List<ItemStack> hearts) {
        ItemManager items = plugin.getItemManager();
        Location face = player.getLocation().clone().add(0.0, Math.max(1.2, player.getHeight() * 0.9), 0.0);
        face.getWorld().spawnParticle(Particle.SNEEZE, face, 12, 0.25, 0.2, 0.25, 0.01);
        face.getWorld().spawnParticle(Particle.HEART, face, 4, 0.2, 0.2, 0.2, 0.02);
        plugin.playSound(face, "allergy.heart_sneeze");

        Vector direction = player.getLocation().getDirection().setY(0.0);
        if (direction.lengthSquared() < 0.0001) direction = new Vector(0.0, 0.0, 1.0);
        direction.normalize();
        Vector impulse = direction.multiply(-0.35);
        impulse.setY(0.18);
        player.setVelocity(player.getVelocity().multiply(0.35).add(impulse));

        player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(
            plugin.msg(player, "heart-allergy.sneeze")
        ));
    }

}

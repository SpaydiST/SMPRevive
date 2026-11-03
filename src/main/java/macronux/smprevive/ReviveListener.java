package macronux.smprevive;

import java.util.UUID;
import macronux.smprevive.listener.AltarListener;
import macronux.smprevive.listener.HeartUseListener;
import macronux.smprevive.listener.InventoryListener;
import macronux.smprevive.listener.PlayerLifecycleListener;
import macronux.smprevive.rules.ReviveRules;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;

/** Registers focused runtime handlers and keeps the shared interaction route in one place. */
public class ReviveListener implements Listener {
    private final SMPRevive plugin;
    private final PlayerLifecycleListener playerLifecycle;
    private final AltarListener altarListener;
    private final HeartUseListener heartUseListener;
    private final InventoryListener inventoryListener;

    public ReviveListener(SMPRevive plugin) {
        this.plugin = plugin;
        this.heartUseListener = new HeartUseListener(plugin);
        this.playerLifecycle = new PlayerLifecycleListener(plugin, this.heartUseListener);
        this.altarListener = new AltarListener(plugin);
        this.inventoryListener = new InventoryListener(plugin);
    }

    public void registerHandlers() {
        PluginManager manager = Bukkit.getPluginManager();
        manager.registerEvents(this, plugin);
        manager.registerEvents(this.playerLifecycle, plugin);
        manager.registerEvents(this.altarListener, plugin);
        manager.registerEvents(this.heartUseListener, plugin);
        manager.registerEvents(this.inventoryListener, plugin);
    }

    public void initializeOnlinePlayers() {
        this.playerLifecycle.initializeOnlinePlayers();
    }

    public void initializeOnlinePlayer(Player player) {
        this.playerLifecycle.initializeOnlinePlayer(player);
    }

    public void cancelHeartAnimation(UUID uuid, boolean refund) {
        this.heartUseListener.cancelHeartAnimation(uuid, refund);
    }

    public boolean isHeartAnimationActive(UUID uuid) {
        return this.heartUseListener.isHeartAnimationActive(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if ((event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK
            && event.getAction() != Action.LEFT_CLICK_BLOCK)) return;
        Player player = event.getPlayer();
        if (plugin.isMechanicsDisabled(player.getWorld()) || event.getHand() == null) return;
        boolean customAltarActionAllowed = ReviveRules.allowsCustomAltarAction(player.getGameMode().name());

        ItemStack item = event.getItem();
        if (item == null) {
            item = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        }
        if (!customAltarActionAllowed && plugin.getItemManager().isAltarItem(item)) return;

        // Altar placement takes precedence so vanilla block interaction cannot consume its item first.
        if (ReviveRules.shouldInterceptAltarPlacement(
            plugin.getItemManager().isAltarItem(item),
            event.isCancelled(),
            event.getAction().name()
        ) && event.getClickedBlock() != null && customAltarActionAllowed) {
            DataManager data = plugin.getDataManager();
            Location clickedLocation = event.getClickedBlock().getLocation();
            boolean registered = data.isRegisteredAltar(clickedLocation);
            if (event.getClickedBlock().getType().isInteractable() && !registered) return;

            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            this.altarListener.handleAltarPlacement(player, event, data, registered);
            return;
        }

        ReviveRules.InteractionRoute route = ReviveRules.routeInteraction(
            item != null && plugin.getItemManager().isHeart(item),
            event.getAction().name(),
            event.getHand().name()
        );

        if (route == ReviveRules.InteractionRoute.HEART) {
            boolean itemUseDenied = event.useItemInHand() == Event.Result.DENY;
            boolean cooldownActive = player.hasCooldown(plugin.getHeartMaterial());
            if (!ReviveRules.canUseHeart(event.isCancelled(), itemUseDenied, cooldownActive)) {
                if (cooldownActive) {
                    event.setCancelled(true);
                    String message = plugin.msg(player, "heart.cooldown");
                    player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(message));
                }
                return;
            }
            event.setCancelled(true);
            if (this.heartUseListener.isHeartAnimationActive(player.getUniqueId())) {
                String message = plugin.msg(player, "heart.cooldown");
                player.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(message));
                return;
            }
            this.heartUseListener.useHeart(player, event.getHand());
            return;
        }

        if ((route == ReviveRules.InteractionRoute.ALTAR_OPEN
            || route == ReviveRules.InteractionRoute.ALTAR_BREAK)
            && !customAltarActionAllowed) return;

        if (event.isCancelled()) return;

        if (route == ReviveRules.InteractionRoute.ALTAR_BREAK && event.getClickedBlock() != null) {
            this.altarListener.handleAltarBreak(player, event);
            return;
        }

        if (route != ReviveRules.InteractionRoute.ALTAR_OPEN || event.getClickedBlock() == null) return;
        this.altarListener.handleAltarOpen(player, event);
    }

    public void shutdown() {
        stopRuntime(true);
    }

    public void shutdownForReload() {
        stopRuntime(false);
    }

    private void stopRuntime(boolean finalShutdown) {
        this.altarListener.setShuttingDown(true);
        try {
            this.playerLifecycle.shutdownRuntime();
            this.heartUseListener.shutdownRuntime();
            plugin.getAltarGui().shutdown();
        } finally {
            this.altarListener.setShuttingDown(finalShutdown);
        }
    }

    public void syncAltarDisplays() {
        plugin.getAltarGui().syncPlacedDisplays();
    }

    public void syncMissingAltarDisplays() {
        plugin.getAltarGui().syncMissingAltarDisplays();
    }
}

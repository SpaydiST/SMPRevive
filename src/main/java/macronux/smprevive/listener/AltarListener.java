/**
 * Owns altar block events, placement transactions, and display recovery triggers.
 */
package macronux.smprevive.listener;

import java.util.ArrayList;
import java.util.List;
import macronux.smprevive.advancement.Achievement;
import macronux.smprevive.DataManager;
import macronux.smprevive.rules.ReviveRules;
import macronux.smprevive.SMPRevive;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.Bukkit;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockCanBuildEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.Material;

public final class AltarListener implements Listener {
    private final SMPRevive plugin;
    private boolean manualAltarPlacement;
    private boolean shuttingDown;

    public AltarListener(SMPRevive plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.getDataManager().isUnbreakableAltar(event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
            return;
        }
        if (manualAltarPlacement) return;
        if (plugin.isMechanicsDisabled(event.getBlockPlaced().getWorld())) return;
        ItemStack item = event.getItemInHand();
        if (!plugin.getItemManager().isAltarItem(item)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlaceCommit(BlockPlaceEvent event) {
        if (event.isCancelled() || manualAltarPlacement
            || plugin.isMechanicsDisabled(event.getBlockPlaced().getWorld())) return;
        Location location = event.getBlockPlaced().getLocation();
        DataManager data = plugin.getDataManager();
        if (data.isRegisteredAltar(location)
            && !plugin.isAltarBlockMaterial(event.getBlockPlaced().getType())) {
            data.removeAltarSafely(location);
            plugin.getAltarGui().removeAltarDisplay(location);
        }
    }

    public void handleAltarPlacement(Player player, PlayerInteractEvent event, DataManager data, boolean registered) {
        Location clickedLocation = event.getClickedBlock().getLocation();
        if (registered && event.getHand() == EquipmentSlot.HAND) {
            if (data.isAltar(clickedLocation)) {
                plugin.getAltarGui().open(player, clickedLocation);
            } else {
                data.removeAltarSafely(clickedLocation);
                plugin.getAltarGui().removeAltarDisplay(clickedLocation);
            }
            return;
        }

        if (event.getBlockFace() == null) return;
        Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        if (!canReplaceAltarTarget(target)) return;
        if (data.isRegisteredAltar(target.getLocation())) return;
        placeAltar(player, event.getClickedBlock(), target, event.getHand());
    }

    private boolean canReplaceAltarTarget(Block target) {
        return target != null && (target.isEmpty() || (!target.isLiquid() && target.isPassable()));
    }

    private void placeAltar(Player player, Block against, Block target, EquipmentSlot hand) {
        ItemStack held = getHeldItem(player, hand);
        if (!plugin.getItemManager().isAltarItem(held)) return;

        BlockState replacedState = target.getState();
        BlockData altarData = plugin.getAltarHitboxMaterial().createBlockData();
        BlockCanBuildEvent canBuildEvent = new BlockCanBuildEvent(target, player, altarData, true, hand);
        Bukkit.getPluginManager().callEvent(canBuildEvent);
        if (!canBuildEvent.isBuildable()) return;

        BlockPlaceEvent placeEvent = new BlockPlaceEvent(
            target,
            replacedState,
            against,
            held.clone(),
            player,
            true,
            hand
        );
        // The synthetic BlockPlaceEvent must pass other plugins without blocking this manual placement.
        manualAltarPlacement = true;
        try {
            Bukkit.getPluginManager().callEvent(placeEvent);
        } finally {
            manualAltarPlacement = false;
        }

        if (placeEvent.isCancelled() || !placeEvent.canBuild()) return;

        ItemStack current = getHeldItem(player, hand);
        List<ItemStack> replacedDrops = new ArrayList<>(replacedState.getDrops(held, player));
        if (!plugin.getItemManager().isAltarItem(current)) return;
        target.setBlockData(altarData, false);
        Location location = target.getLocation();
        if (!plugin.getDataManager().addAltarSafely(location, plugin.getItemManager().isUnbreakableAltarItem(current))) {
            target.setBlockData(replacedState.getBlockData(), false);
            return;
        }
        if (!consumeAltarItem(player, hand)) {
            plugin.getDataManager().removeAltarSafely(location);
            target.setBlockData(replacedState.getBlockData(), false);
            return;
        }
        plugin.getAltarGui().spawnAltarDisplay(location);
        for (ItemStack drop : replacedDrops) {
            target.getWorld().dropItemNaturally(location, drop);
        }
        if (plugin.getDataManager().isAltar(location) && plugin.getAdvancementManager() != null) {
            plugin.getAdvancementManager().grant(player, Achievement.ALTAR_PLACED);
        }
    }

    private ItemStack getHeldItem(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
            ? player.getInventory().getItemInOffHand()
            : player.getInventory().getItemInMainHand();
    }

    private boolean consumeAltarItem(Player player, EquipmentSlot hand) {
        ItemStack held = getHeldItem(player, hand);
        if (!plugin.getItemManager().isAltarItem(held)) return false;
        if (held.getAmount() <= 1) {
            if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(null);
            else player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(held.getAmount() - 1);
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void prepareBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        DataManager data = plugin.getDataManager();
        Location location = block.getLocation();
        if (!data.isRegisteredAltar(location)) return;
        if (!plugin.isAltarBlockMaterial(block.getType())) return;

        if (data.isUnbreakableAltar(location)) {
            event.setCancelled(true);
            return;
        }
        if (!ReviveRules.allowsCustomAltarAction(event.getPlayer().getGameMode().name())) return;
        if (plugin.isMechanicsDisabled(block.getWorld())) return;

        event.setDropItems(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> plugin.getDataManager().isUnbreakableAltar(block.getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> plugin.getDataManager().isUnbreakableAltar(block.getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void commitBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        Block block = event.getBlock();
        if (!ReviveRules.allowsCustomAltarAction(event.getPlayer().getGameMode().name())) return;
        if (plugin.isMechanicsDisabled(block.getWorld())) return;
        DataManager data = plugin.getDataManager();
        Location location = block.getLocation();
        if (!data.isRegisteredAltar(location)) return;
        boolean validAltar = plugin.isAltarBlockMaterial(block.getType());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || !data.isRegisteredAltar(location)) return;
            if (!validAltar) {
                data.removeAltarSafely(location);
                plugin.getAltarGui().removeAltarDisplay(location);
                return;
            }
            if (plugin.isAltarBlockMaterial(block.getType())) return;
            if (!data.removeAltarSafely(location)) {
                block.setType(plugin.getAltarHitboxMaterial(), false);
                return;
            }
            plugin.getAltarGui().removeAltarDisplay(location);
            block.getWorld().dropItemNaturally(location, plugin.getItemManager().createAltarItem());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        prepareExplosion(event, event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        prepareExplosion(event, event.blockList());
    }

    private void prepareExplosion(Cancellable event, List<Block> blocks) {
        if (event.isCancelled() || blocks == null || blocks.isEmpty()) return;
        List<Block> altars = new ArrayList<>();
        for (Block block : new ArrayList<>(blocks)) {
            Location location = block.getLocation();
            DataManager data = plugin.getDataManager();
            if (!data.isRegisteredAltar(location)) continue;
            if (plugin.isAltarBlockMaterial(block.getType())) {
                if (data.isUnbreakableAltar(location)) {
                    blocks.remove(block);
                    continue;
                }
                if (plugin.isMechanicsDisabled(block.getWorld())) continue;
                altars.add(block);
                blocks.remove(block);
            }
        }
        if (altars.isEmpty()) return;

        // Remove tracked altars only after the event's final cancellation state
        // is known; removing them from blockList prevents a vanilla duplicate drop.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled()) return;
            for (Block block : altars) {
                Location location = block.getLocation();
                DataManager data = plugin.getDataManager();
                if (!data.isRegisteredAltar(location)) continue;
                if (!plugin.isAltarBlockMaterial(block.getType())) {
                    data.removeAltarSafely(location);
                    plugin.getAltarGui().removeAltarDisplay(location);
                    continue;
                }
                Material previousType = block.getType();
                block.setType(Material.AIR, false);
                if (!data.removeAltarSafely(location)) {
                    block.setType(previousType, false);
                    continue;
                }
                plugin.getAltarGui().removeAltarDisplay(location);
                block.getWorld().dropItemNaturally(location, plugin.getItemManager().createAltarItem());
            }
        });
    }

    private void breakAltar(Player player, Block block) {
        BlockBreakEvent breakEvent = new BlockBreakEvent(block, player);
        Bukkit.getPluginManager().callEvent(breakEvent);
        if (breakEvent.isCancelled()) return;
        breakEvent.setDropItems(false);
        block.setType(Material.AIR, false);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.isMechanicsDisabled(event.getChunk().getWorld())) {
            plugin.getAltarGui().syncPlacedDisplaysInChunk(event.getChunk());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (shuttingDown || event.getCause() == EntityRemoveEvent.Cause.UNLOAD
            || !(event.getEntity() instanceof ItemDisplay display)
            || !plugin.getItemManager().isAltarDisplay(display)) return;

        Location location = display.getLocation().clone();
        if (!ReviveRules.shouldRestoreAltarDisplay(
            true,
            plugin.getDataManager().isRegisteredAltar(location),
            plugin.isAltarBlockMaterial(location.getBlock().getType())
        )) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (shuttingDown || plugin.isMechanicsDisabled(location)
                || !plugin.getDataManager().isRegisteredAltar(location)
                || !plugin.isAltarBlockMaterial(location.getBlock().getType())) return;
            plugin.getAltarGui().spawnAltarDisplay(location);
        });
    }

    /** Marks entity cleanup caused by plugin shutdown so displays are not recreated. */
    public void setShuttingDown(boolean shuttingDown) {
        this.shuttingDown = shuttingDown;
    }

    public void handleAltarBreak(Player player, PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;
        Location location = block.getLocation();
        DataManager data = plugin.getDataManager();
        if (!data.isRegisteredAltar(location)) return;
        if (!data.isAltar(location)) {
            data.removeAltarSafely(location);
            plugin.getAltarGui().removeAltarDisplay(location);
            return;
        }
        event.setCancelled(true);
        if (data.isUnbreakableAltar(location)) return;
        breakAltar(player, block);
    }

    public void handleAltarOpen(Player player, PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;
        Location location = block.getLocation();
        DataManager data = plugin.getDataManager();
        if (!data.isRegisteredAltar(location)) return;
        if (!data.isAltar(location)) {
            data.removeAltarSafely(location);
            plugin.getAltarGui().removeAltarDisplay(location);
            return;
        }
        event.setCancelled(true);
        plugin.getAltarGui().open(player, location);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        plugin.getAltarGui().forgetDisplaysInChunk(event.getChunk());
    }
}

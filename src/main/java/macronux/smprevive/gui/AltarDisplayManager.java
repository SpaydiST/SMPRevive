package macronux.smprevive.gui;

import macronux.smprevive.SMPRevive;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Reconciles saved altar blocks with their temporary display entities. */
public final class AltarDisplayManager {
    private final SMPRevive plugin;
    private final Map<AltarKey, ItemDisplay> displays = new HashMap<>();
    private record AltarKey(UUID world, int x, int y, int z) {
        static AltarKey of(Location loc) { return new AltarKey(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()); }
    }

    public AltarDisplayManager(SMPRevive plugin) {
        this.plugin = plugin;
    }

    public void syncPlacedDisplays() {
        removeAllAltarDisplays();
        for (Location location : plugin.getDataManager().getRegisteredAltars()) {
            // Chunk-load events reconcile the rest without forcing dormant chunks to load.
            if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) continue;
            Material blockType = location.getBlock().getType();
            if (blockType == plugin.getAltarBlockMaterial()
                && blockType != plugin.getAltarHitboxMaterial()) {
                location.getBlock().setType(plugin.getAltarHitboxMaterial(), false);
                blockType = plugin.getAltarHitboxMaterial();
            }
            if (plugin.isAltarBlockMaterial(blockType)) {
                spawnAltarDisplay(location);
            } else {
                plugin.getDataManager().removeAltarSafely(location);
                removeAltarDisplay(location);
            }
        }
    }

    public void syncPlacedDisplaysInChunk(Chunk chunk) {
        if (chunk == null) return;
        for (Location location : plugin.getDataManager().getRegisteredAltarsInChunk(chunk)) {
            Material blockType = location.getBlock().getType();
            if (blockType == plugin.getAltarBlockMaterial()
                && blockType != plugin.getAltarHitboxMaterial()) {
                location.getBlock().setType(plugin.getAltarHitboxMaterial(), false);
                blockType = plugin.getAltarHitboxMaterial();
            }
            if (plugin.isAltarBlockMaterial(blockType)) {
                spawnAltarDisplay(location);
            } else {
                plugin.getDataManager().removeAltarSafely(location);
                removeAltarDisplay(location);
            }
        }
    }

    public void syncMissingAltarDisplays() {
        long start = System.nanoTime();
        try {
            for (Location location : plugin.getDataManager().getRegisteredAltars()) {
                if (location.getWorld() == null
                    || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) continue;
                if (plugin.isAltarBlockMaterial(location.getBlock().getType())) {
                    spawnAltarDisplay(location);
                } else {
                    plugin.getDataManager().removeAltarSafely(location);
                    removeAltarDisplay(location);
                }
            }
        } finally {
            plugin.getPerformanceCounters().record("altar-sync", start);
        }
    }

    public void spawnAltarDisplay(Location location) {
        if (location == null || location.getWorld() == null || plugin.isMechanicsDisabled(location)) return;
        if (!plugin.isAltarBlockMaterial(location.getBlock().getType())) return;
        AltarKey key = AltarKey.of(location);
        ItemDisplay cached = displays.get(key);
        if (cached != null && cached.isValid() && plugin.getItemManager().isAltarDisplay(cached)
            && sameBlock(cached.getLocation(), location)) return;
        displays.remove(key);
        Location center = location.clone().add(0.5, 0.5, 0.5);
        for (Entity entity : location.getWorld().getNearbyEntities(center, 0.45, 1.5, 0.45)) {
            if (entity instanceof ItemDisplay display
                && plugin.getItemManager().isAltarDisplay(display)
                && sameBlock(display.getLocation(), location)) {
                displays.put(key, display);
                return;
            }
        }
        ItemDisplay display = location.getWorld().spawn(center, ItemDisplay.class);
        display.setItemStack(plugin.getItemManager().createAltarItem());
        display.setPersistent(false);
        display.setViewRange(32.0f);
        plugin.getItemManager().markAltarDisplay(display);
        displays.put(key, display);
    }

    public void removeAltarDisplay(Location location) {
        if (location == null || location.getWorld() == null) return;
        ItemDisplay cached = displays.remove(AltarKey.of(location));
        if (cached != null && cached.isValid()) cached.remove();
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        for (Entity entity : location.getWorld().getNearbyEntities(location.clone().add(0.5, 0.5, 0.5), 1.0, 2.0, 1.0)) {
            if (entity instanceof ItemDisplay display
                && plugin.getItemManager().isAltarDisplay(display)
                && sameBlock(display.getLocation(), location)) {
                display.remove();
            }
        }
    }

    public void shutdown() {
        removeAllAltarDisplays();
    }

    /** Drop entity references on unload; the chunk-load handler will reconcile them again. */
    public void forgetChunk(Chunk chunk) {
        UUID world = chunk.getWorld().getUID();
        displays.keySet().removeIf(key -> key.world().equals(world)
            && (key.x() >> 4) == chunk.getX() && (key.z() >> 4) == chunk.getZ());
    }

    private void removeAllAltarDisplays() {
        displays.clear();
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (plugin.getItemManager().isAltarDisplay(display)) display.remove();
            }
        }
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getWorld() != null
            && second.getWorld() != null
            && first.getWorld().equals(second.getWorld())
            && first.getBlockX() == second.getBlockX()
            && first.getBlockY() == second.getBlockY()
            && first.getBlockZ() == second.getBlockZ();
    }
}

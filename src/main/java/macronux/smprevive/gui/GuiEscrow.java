package macronux.smprevive.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Durable, one-shot storage for editable GUI items across a crash. */
public final class GuiEscrow {
    private static final long WRITE_TOLERANCE_MILLIS = 2_000L;
    private static volatile Function<Player, Path> dataFileLocator = GuiEscrow::defaultDataFile;

    private GuiEscrow() {
    }

    public static void checkpoint(Plugin plugin, Player player, Collection<ItemStack> items) {
        if (plugin == null || player == null || items == null) return;
        List<Map<String, Object>> serial = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) continue;
            serial.add(new LinkedHashMap<>(item.serialize()));
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items", serial);
        player.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE_ARRAY,
            yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    public static List<ItemStack> read(Plugin plugin, Player player) {
        if (plugin == null || player == null) return List.of();
        byte[] bytes = player.getPersistentDataContainer().get(key(plugin), PersistentDataType.BYTE_ARRAY);
        if (bytes == null) return List.of();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot read saved altar items", ex);
        }
        List<ItemStack> result = new ArrayList<>();
        for (Map<?, ?> raw : yaml.getMapList("items")) {
            Map<String, Object> serial = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) serial.put(String.valueOf(entry.getKey()), entry.getValue());
            try {
                ItemStack item = ItemStack.deserialize(serial);
                if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) result.add(item);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Cannot decode saved altar item", exception);
            }
        }
        return result;
    }

    public static boolean hasItems(Plugin plugin, Player player) {
        return !read(plugin, player).isEmpty();
    }

    /** Inventory delivery and the remaining escrow share one playerdata save. */
    public static boolean restoreAndSave(Plugin plugin, Player player) {
        if (player == null || !player.isOnline() || player.isDead()) return false;
        byte[] previous = player.getPersistentDataContainer().get(key(plugin), PersistentDataType.BYTE_ARRAY);
        if (previous == null) return true;
        ItemStack[] inventory = player.getInventory().getStorageContents();
        for (int i = 0; i < inventory.length; i++) {
            if (inventory[i] != null) inventory[i] = inventory[i].clone();
        }
        try {
            List<ItemStack> remaining = new ArrayList<>();
            for (ItemStack item : read(plugin, player)) {
                remaining.addAll(player.getInventory().addItem(item.clone()).values());
            }
            if (remaining.isEmpty()) clear(plugin, player);
            else checkpoint(plugin, player, remaining);
            savePlayerData(player);
            return remaining.isEmpty();
        } catch (RuntimeException ex) {
            player.getInventory().setStorageContents(inventory);
            player.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE_ARRAY, previous);
            plugin.getLogger().warning("Could not restore saved altar items: " + ex.getMessage());
            return false;
        }
    }

    /** Use Paper's save API; the journal and inventory markers handle recovery.
     * Altar escrow owns its cursor, so exclude it from the ordinary player snapshot. */
    public static void savePlayerData(Player player) {
        Path file = dataFileLocator.apply(player);
        long started = System.currentTimeMillis();
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder
            && holder.getType() == MenuHolder.Type.ALTAR) {
            ItemStack cursor = player.getItemOnCursor();
            try { player.setItemOnCursor(null); player.saveData(); }
            finally { player.setItemOnCursor(cursor); }
        } else player.saveData();
        // Paper's PlayerDataStorage.save logs "Failed to save player data" and returns normally on I/O errors,
        // so a successful return proves nothing. Without this check every caller's rollback is unreachable.
        if (file != null && !writtenSince(file, started)) {
            throw new IllegalStateException("Server did not write player data for " + player.getUniqueId());
        }
    }

    /** Resolves the playerdata file, or null when the layout cannot be confirmed (then the write is not verified). */
    private static Path defaultDataFile(Player player) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return null;
        Path directory = worlds.get(0).getWorldFolder().toPath().resolve("players").resolve("data");
        return Files.isDirectory(directory) ? directory.resolve(player.getUniqueId() + ".dat") : null;
    }

    private static boolean writtenSince(Path file, long startedMillis) {
        try {
            // The tolerance only hides a failed write that follows a very recent good one; it never invents a failure.
            return Files.getLastModifiedTime(file).toMillis() >= startedMillis - WRITE_TOLERANCE_MILLIS;
        } catch (IOException exception) {
            return false;
        }
    }

    static void useDataFileLocator(Function<Player, Path> locator) { dataFileLocator = locator; }

    static void resetDataFileLocator() { dataFileLocator = GuiEscrow::defaultDataFile; }

    public static void clear(Plugin plugin, Player player) {
        if (plugin != null && player != null) player.getPersistentDataContainer().remove(key(plugin));
    }

    private static NamespacedKey key(Plugin plugin) {
        return new NamespacedKey(plugin, "recovery_gui_escrow");
    }
}

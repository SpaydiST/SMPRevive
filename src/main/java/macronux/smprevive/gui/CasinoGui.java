package macronux.smprevive.gui;

import macronux.smprevive.DataManager;
import macronux.smprevive.SMPRevive;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

public class CasinoGui {
    private final SMPRevive plugin;
    private final int[] slots = new int[]{11, 12, 13, 14, 15};
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();
    private final Map<UUID, Boolean> finished = new HashMap<>();
    private final Map<UUID, Material[]> candidates = new HashMap<>();

    public CasinoGui(SMPRevive plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        BukkitTask[] ref = new BukkitTask[1];
        UUID uuid = player.getUniqueId();
        this.cancel(player);
        MenuHolder holder = new MenuHolder(MenuHolder.Type.CASINO);
        String configTitle = this.plugin.getConfig().getString("casino-title", "");
        // Keep the texture visible with older configs where casino-title was empty.
        String title = configTitle.isEmpty() ? "§f󼰁ꚇ󼰂" : configTitle;
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inv);
        for (int s : this.slots) {
            inv.setItem(s, this.randomItem());
        }
        player.openInventory(inv);
        this.finished.put(uuid, false);
        int[] elapsedTicks = new int[]{0};
        int duration = this.plugin.getCasinoDurationTicks();
        int period = this.plugin.getCasinoPeriodTicks();
        ref[0] = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inv) {
                this.cancel(player);
                return;
            }
            for (int s : this.slots) {
                Material next = this.randomMaterial();
                ItemStack current = inv.getItem(s);
                // The visible state is identical when the random material repeats.
                if (current == null || current.getType() != next) inv.setItem(s, new ItemStack(next));
            }
            elapsedTicks[0] = Math.min(duration, elapsedTicks[0] + period);
            if (elapsedTicks[0] >= duration) {
                Material[] cand = new Material[this.slots.length];
                for (int i = 0; i < this.slots.length; ++i) {
                    ItemStack it = inv.getItem(this.slots[i]);
                    cand[i] = it == null ? Material.DIAMOND : it.getType();
                }
                this.candidates.put(uuid, cand);
                this.finished.put(uuid, true);
                this.tasks.remove(uuid);
                ref[0].cancel();
                plugin.playSoundToPlayer(player, "casino.select");
                player.sendMessage(this.plugin.msg(player, "casino.select"));
            }
        }, 0L, period);
        this.tasks.put(uuid, ref[0]);
    }

    private ItemStack randomItem() {
        return new ItemStack(randomMaterial());
    }

    private Material randomMaterial() {
        List<Material> pool = this.plugin.getCasinoPool();
        if (pool.isEmpty()) {
            return Material.DIAMOND_BLOCK;
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    public void handleClick(InventoryClickEvent e) {
        Player p = (Player) e.getWhoClicked();
        UUID uuid = p.getUniqueId();
        e.setCancelled(true);
        if (!this.finished.getOrDefault(uuid, false)) {
            p.sendMessage(this.plugin.msg(p, "casino.wait"));
            return;
        }
        int raw = e.getRawSlot();
        int idx = -1;
        for (int i = 0; i < this.slots.length; ++i) {
            if (this.slots[i] == raw) {
                idx = i;
                break;
            }
        }
        if (idx != -1) {
            this.select(p, idx);
        }
    }

    public void handleDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder holder
            && holder.getType() == MenuHolder.Type.CASINO) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    private void select(Player p, int index) {
        UUID uuid = p.getUniqueId();
        Material[] cand = this.candidates.get(uuid);
        if (cand == null || index < 0 || index >= cand.length) {
            return;
        }
        Material m = cand[index];
        DataManager data = this.plugin.getDataManager();
        String previous = data.getTokenMaterial(uuid);
        data.setTokenMaterial(uuid, m.name());
        if (!data.saveSafely()) {
            data.setTokenMaterial(uuid, previous);
            p.sendMessage(this.plugin.msg(p, "casino.error"));
            return;
        }
        p.closeInventory();
        plugin.playSoundToPlayer(p, "casino.received_sound");
        p.sendMessage(this.plugin.msg(p, "casino.received").replace("%item%", this.plugin.getMaterialName(p, m.name())));
        this.cancel(p);
    }

    public void cancel(Player player) {
        UUID uuid = player.getUniqueId();
        BukkitTask task = this.tasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        this.finished.remove(uuid);
        this.candidates.remove(uuid);
    }

    public void shutdown() {
        closeOpenInventories();
        for (BukkitTask task : this.tasks.values()) {
            task.cancel();
        }
        this.tasks.clear();
        this.finished.clear();
        this.candidates.clear();
    }

    private void closeOpenInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof MenuHolder holder && holder.getType() == MenuHolder.Type.CASINO) {
                player.closeInventory();
                cancel(player);
            }
        }
    }
}

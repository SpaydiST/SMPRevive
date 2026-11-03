/**
 * Routes casino and altar inventory clicks, closes, and drags to their GUI owners.
 */
package macronux.smprevive.listener;

import macronux.smprevive.gui.MenuHolder;
import macronux.smprevive.SMPRevive;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.Listener;

public final class InventoryListener implements Listener {
    private final SMPRevive plugin;

    public InventoryListener(SMPRevive plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (event.isCancelled() || !(event.getWhoClicked() instanceof Player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        if (holder.getType() == MenuHolder.Type.CASINO) {
            plugin.getCasinoGui().handleClick(event);
        } else if (holder.getType() == MenuHolder.Type.ALTAR) {
            plugin.getAltarGui().handleClick(event);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        HumanEntity human = event.getPlayer();
        if (!(human instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        if (holder.getType() == MenuHolder.Type.CASINO) {
            plugin.getCasinoGui().cancel(player);
        } else if (holder.getType() == MenuHolder.Type.ALTAR) {
            plugin.getAltarGui().handleClose(player, event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        if (holder.getType() == MenuHolder.Type.CASINO) {
            plugin.getCasinoGui().handleDrag(event);
        } else if (holder.getType() == MenuHolder.Type.ALTAR) {
            plugin.getAltarGui().handleDrag(event);
        }
    }
}

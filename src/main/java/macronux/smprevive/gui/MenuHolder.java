package macronux.smprevive.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.Location;

public class MenuHolder implements InventoryHolder {
    public enum Type { CASINO, ALTAR }

    private final Type type;
    private final Location altarLocation;
    private Inventory inventory;
    private boolean revivalStarted;
    private boolean contentsReturned;

    public MenuHolder(Type type) {
        this(type, null);
    }

    public MenuHolder(Type type, Location altarLocation) {
        this.type = type;
        this.altarLocation = altarLocation == null ? null : altarLocation.clone();
    }

    public Type getType() {
        return this.type;
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public Location getAltarLocation() {
        return this.altarLocation == null ? null : this.altarLocation.clone();
    }

    public boolean isRevivalStarted() {
        return this.revivalStarted;
    }

    public void setRevivalStarted(boolean revivalStarted) {
        this.revivalStarted = revivalStarted;
    }

    public boolean markContentsReturned() {
        if (this.contentsReturned) return false;
        this.contentsReturned = true;
        return true;
    }
}

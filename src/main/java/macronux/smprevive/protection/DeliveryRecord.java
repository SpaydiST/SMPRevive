package macronux.smprevive.protection;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

/** Durable ownership of one complete stack while it is outside a player's saved inventory. */
final class DeliveryRecord {
    enum Kind { DROP, BLOCK, FRAME, RETURN, PLAYER }
    final UUID id;
    final ItemStack item;
    private String encodedItem; // The owned stack is immutable; every physical item is a clone.
    final UUID worldId;
    final double x, y, z;
    final long due;
    final UUID frameId;
    Kind kind;
    UUID recipient;
    UUID claimant;
    UUID debitOwner;
    UUID debitReceipt;

    DeliveryRecord(UUID id, ItemStack item, Location location, Kind kind, long due, UUID frameId) {
        this.id = id; this.item = item.clone(); this.worldId = location.getWorld().getUID();
        this.x = location.getX(); this.y = location.getY(); this.z = location.getZ();
        this.kind = kind; this.due = due; this.frameId = frameId;
    }

    private DeliveryRecord(UUID id, ItemStack item, UUID worldId, double x, double y, double z,
                           Kind kind, long due, UUID frameId, UUID recipient, UUID claimant) {
        this.id=id; this.item=item; this.worldId=worldId; this.x=x; this.y=y; this.z=z;
        this.kind=kind; this.due=due; this.frameId=frameId; this.recipient=recipient; this.claimant=claimant;
    }

    Location location() {
        World world = Bukkit.getWorld(worldId);
        return world == null ? null : new Location(world, x, y, z);
    }

    Map<String, Object> serialize() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (encodedItem == null) encodedItem = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        map.put("item", encodedItem);
        map.put("world", worldId.toString()); map.put("x",x); map.put("y",y); map.put("z",z);
        map.put("kind",kind.name()); map.put("due",due);
        if(frameId!=null) map.put("frame",frameId.toString());
        if(recipient!=null) map.put("recipient",recipient.toString());
        if(claimant!=null) map.put("claimant",claimant.toString());
        if(debitOwner!=null) map.put("debit-owner",debitOwner.toString());
        if(debitReceipt!=null) map.put("debit-receipt",debitReceipt.toString());
        return map;
    }

    static DeliveryRecord deserialize(UUID id, Map<String,Object> map) {
        ItemStack item=ItemStack.deserializeBytes(Base64.getDecoder().decode((String)map.get("item")));
        if(item.getType().isAir() || item.getAmount()<1) throw new IllegalArgumentException("Empty delivery");
        double x=((Number)map.get("x")).doubleValue(), y=((Number)map.get("y")).doubleValue(), z=((Number)map.get("z")).doubleValue();
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)) throw new IllegalArgumentException("Invalid delivery location");
        DeliveryRecord record = new DeliveryRecord(id,item,UUID.fromString((String)map.get("world")),x,y,z,
            Kind.valueOf((String)map.get("kind")),((Number)map.get("due")).longValue(),
            uuid(map.get("frame")),uuid(map.get("recipient")),uuid(map.get("claimant")));
        record.encodedItem = (String) map.get("item");
        if (record.kind==Kind.PLAYER && record.recipient==null) throw new IllegalArgumentException("Missing delivery recipient");
        if (record.kind==Kind.FRAME && record.frameId==null) throw new IllegalArgumentException("Missing frame source");
        record.debitOwner=uuid(map.get("debit-owner")); record.debitReceipt=uuid(map.get("debit-receipt"));
        if ((record.debitOwner==null)!=(record.debitReceipt==null)) throw new IllegalArgumentException("Incomplete debit receipt");
        return record;
    }

    private static UUID uuid(Object value) { return value==null ? null : UUID.fromString((String)value); }
}

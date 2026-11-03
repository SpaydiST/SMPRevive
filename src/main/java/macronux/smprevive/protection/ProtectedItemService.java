package macronux.smprevive.protection;

import macronux.smprevive.SMPRevive;
import macronux.smprevive.gui.GuiEscrow;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/** Protects physical items; durable delivery records, not animation entities, own pending stacks. */
public final class ProtectedItemService implements Listener {
    private final SMPRevive plugin;
    private final NamespacedKey deliveryKey;
    private final NamespacedKey placedHeadKey;
    private final NamespacedKey voidRescueKey;
    private final Map<UUID, DeliveryRecord> records = new LinkedHashMap<>();
    private final Map<UUID, Item> drops = new HashMap<>();
    private final Map<UUID, Item> protectedDrops = new HashMap<>();
    private final Map<UUID, Flight> flights = new HashMap<>();
    // Live debits wait for a successful playerdata save. After restart only the persisted receipt proves a debit.
    private final Map<UUID, UUID> pendingDebits = new HashMap<>();
    private final Map<UUID, BlockPlaceEvent> placementEvents = new HashMap<>();
    private final Set<UUID> creativeDebits = new HashSet<>();
    private final Set<UUID> creativePlacements = new HashSet<>();
    private final Map<UUID, UUID> pendingRollbacks = new HashMap<>();
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private static final int MAX_FLIGHTS = 16;
    private static final int MAX_QUEUE_VISITS = 64;
    private static final int MAX_QUEUE_ACTIONS = 8;
    private static final long QUEUE_BUDGET_NANOS = 3_000_000L;
    private final DeadlineQueue work = new DeadlineQueue();
    private final Map<UUID, Set<UUID>> playerWaiters = new HashMap<>();
    private final Map<UUID, Set<UUID>> worldWaiters = new HashMap<>();
    private final Map<ChunkKey, Set<UUID>> chunkWaiters = new HashMap<>();
    private final Map<UUID, Wait> waits = new HashMap<>();
    private final Set<UUID> pendingWakes = new HashSet<>();
    private final Set<UUID> pendingWorldWakes = new LinkedHashSet<>();
    private record ChunkKey(UUID world, int x, int z) {}
    private record Wait(UUID player, UUID world, ChunkKey chunk) {}
    private BukkitTask pulse;
    private boolean stopped;
    private boolean loadFailed;

    public ProtectedItemService(SMPRevive plugin) {
        this.plugin=plugin;
        deliveryKey=new NamespacedKey(plugin,"protected_delivery");
        placedHeadKey=new NamespacedKey(plugin,"placed_head_item");
        voidRescueKey=new NamespacedKey(plugin,"waiting_void_rescue");
        Map<String,Map<String,Object>> saved;
        try { saved=plugin.getDataManager().getProtectedDeliveries(); }
        catch(RuntimeException error) { loadFailed=true; plugin.getLogger().severe(error.getMessage()); return; }
        for(var entry:saved.entrySet()) {
            try {
                UUID id=UUID.fromString(entry.getKey());
                trackRecord(DeliveryRecord.deserialize(id,entry.getValue()));
            } catch(RuntimeException error) {
                loadFailed=true;
                plugin.getLogger().severe("Cannot decode protected delivery "+entry.getKey()+": "+error.getMessage());
            }
        }
    }

    public boolean isLoadFailed() { return loadFailed; }
    public int pendingCount() { return records.size(); }
    public int activeFlightCount() { return flights.size(); }

    public void start() {
        stopped=false;
        for(World world:Bukkit.getWorlds()) for(Chunk chunk:world.getLoadedChunks()) scan(chunk);
        pulse=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1L,1L);
    }

    public void shutdown() {
        // Complete receipts before normal server player saves during shutdown.
        for(var entry:new ArrayList<>(pendingRollbacks.entrySet())) confirmRollback(Bukkit.getPlayer(entry.getValue()),entry.getKey(),true);
        for(var entry:new ArrayList<>(pendingDebits.entrySet())) {
            Player player=Bukkit.getPlayer(entry.getValue());
            if(player==null) continue;
            BlockPlaceEvent placement=placementEvents.get(entry.getKey());
            DeliveryRecord record=records.values().stream().filter(r->entry.getKey().equals(r.debitReceipt)).findFirst().orElse(null);
            if(placement!=null && record!=null) { retryAfter.remove(entry.getKey()); settlePlacement(record,player,entry.getKey(),placement); }
            else { retryAfter.remove(entry.getKey()); sealDebit(player,entry.getKey()); }
        }
        stopped=true;
        if(pulse!=null) { pulse.cancel(); pulse=null; }
        for(Flight flight:new ArrayList<>(flights.values())) flight.stop();
        flights.clear(); protectedDrops.clear(); drops.clear(); pendingDebits.clear(); placementEvents.clear(); pendingRollbacks.clear(); retryAfter.clear(); creativeDebits.clear(); creativePlacements.clear();
        work.clear(); waits.clear(); playerWaiters.clear(); worldWaiters.clear(); chunkWaiters.clear(); pendingWakes.clear(); pendingWorldWakes.clear();
    }

    /** Caller stages these records together with a state transition, then saves players.yml once. */
    public List<UUID> stageDrops(Location location, Collection<ItemStack> items) {
        if(loadFailed || location==null || location.getWorld()==null) throw new IllegalStateException("Protected delivery unavailable");
        List<UUID> ids=new ArrayList<>();
        try {
            for(ItemStack stack:items) {
                if(stack==null || stack.getType().isAir() || stack.getAmount()<1) continue;
                DeliveryRecord record=new DeliveryRecord(UUID.randomUUID(),stack,location,DeliveryRecord.Kind.DROP,0,null);
                Map<String,Object> saved=record.serialize();
                plugin.getDataManager().stageProtectedDelivery(record.id,saved);
                trackRecord(record); ids.add(record.id);
            }
        } catch(RuntimeException error) {
            unstage(ids);
            throw error;
        }
        return ids;
    }

    public void unstage(Collection<UUID> ids) {
        for(UUID id:ids) { records.remove(id); work.cancel(id); clearWait(id); plugin.getDataManager().stageProtectedDelivery(id,null); }
    }

    public boolean queueDrops(Location location, Collection<ItemStack> items) {
        List<UUID> ids=stageDrops(location,items);
        if(plugin.getDataManager().saveSafely()) return true;
        unstage(ids); return false;
    }

    public boolean queueForPlayer(Player player, ItemStack item) {
        DeliveryRecord record=new DeliveryRecord(UUID.randomUUID(),item,player.getLocation(),DeliveryRecord.Kind.PLAYER,0,null);
        record.recipient=player.getUniqueId(); trackRecord(record);
        plugin.getDataManager().stageProtectedDelivery(record.id,record.serialize());
        if(plugin.getDataManager().saveSafely()) return true;
        unstage(List.of(record.id)); return false;
    }

    /** Write-ahead queue plus a playerdata debit receipt keeps a sneeze from minting or losing hearts. */
    public boolean transferFromPlayer(Player player, Collection<ItemStack> items, Runnable removeItems) {
        if(pendingRollbacks.containsValue(player.getUniqueId())) return false;
        ItemStack[] inventory=cloneItems(player.getInventory().getContents());
        UUID debit=UUID.randomUUID();
        List<UUID> ids=stageDrops(player.getLocation(),items);
        for(UUID id:ids) {
            DeliveryRecord record=records.get(id); record.debitOwner=player.getUniqueId(); record.debitReceipt=debit;
            plugin.getDataManager().stageProtectedDelivery(id,record.serialize());
            scheduleRecord(record);
        }
        if(!plugin.getDataManager().saveSafely()) { unstage(ids); return false; }
        NamespacedKey key=debitReceipt(debit);
        try {
            removeItems.run(); player.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1); GuiEscrow.savePlayerData(player);
        } catch(RuntimeException error) {
            player.getInventory().setContents(inventory); player.getPersistentDataContainer().remove(key);
            try { GuiEscrow.savePlayerData(player); }
            catch(RuntimeException ignored) { pendingRollbacks.put(debit,player.getUniqueId()); }
            plugin.getLogger().warning("Heart transfer is awaiting a durable debit: "+error.getMessage());
            return false;
        }
        // Keep a successful debit even if queue persistence needs another attempt.
        pendingDebits.put(debit, player.getUniqueId());
        if (releaseDebit(debit)) pendingDebits.remove(debit);
        return true;
    }

    /** Delivery receipt and inventory are saved in the same playerdata snapshot. */
    private boolean give(DeliveryRecord record, Player player) {
        if(record.debitOwner!=null || !eligible(player) || !hasRoom(player,record.item)) return false;
        if(record.claimant!=null && !record.claimant.equals(player.getUniqueId())) return false;
        if(record.claimant==null) {
            Map<String,Object> before=record.serialize(); record.claimant=player.getUniqueId();
            if(!persist(record,before)) return false;
        }
        NamespacedKey receipt=receipt(record.id);
        if(player.getPersistentDataContainer().has(receipt,PersistentDataType.BYTE)) return finish(record);
        ItemStack[] before=cloneItems(player.getInventory().getContents());
        try {
            ItemStack item=record.item.clone(); stripDelivery(item);
            if(!player.getInventory().addItem(item).isEmpty()) {
                player.getInventory().setContents(before); return false;
            }
            player.getPersistentDataContainer().set(receipt,PersistentDataType.BYTE,(byte)1);
            GuiEscrow.savePlayerData(player);
        } catch(RuntimeException error) {
            player.getInventory().setContents(before); player.getPersistentDataContainer().remove(receipt);
            plugin.getLogger().warning("Protected delivery remains queued: "+error.getMessage());
            return false;
        }
        return finish(record);
    }

    private boolean finish(DeliveryRecord record) {
        boolean blockSource=record.frameId==null && record.due>0 && record.debitOwner==null
            && plugin.getItemManager().isPlayerHead(record.item);
        boolean wasRetiredBlock=blockSource && plugin.getDataManager().isRetiredHeadBlock(record.worldId,(int)record.x,(int)record.y,(int)record.z);
        boolean wasRetired=record.frameId!=null && plugin.getDataManager().isRetiredHeadFrame(record.frameId);
        if(blockSource) plugin.getDataManager().setRetiredHeadBlock(record.worldId,(int)record.x,(int)record.y,(int)record.z,true);
        if(record.frameId!=null) plugin.getDataManager().setRetiredHeadFrame(record.frameId,true);
        plugin.getDataManager().stageProtectedDelivery(record.id,null);
        if(!plugin.getDataManager().saveSafely()) {
            if(blockSource) plugin.getDataManager().setRetiredHeadBlock(record.worldId,(int)record.x,(int)record.y,(int)record.z,wasRetiredBlock);
            if(record.frameId!=null) plugin.getDataManager().setRetiredHeadFrame(record.frameId,wasRetired);
            plugin.getDataManager().stageProtectedDelivery(record.id,record.serialize()); return false;
        }
        records.remove(record.id); work.cancel(record.id); clearWait(record.id);
        Player owner=record.claimant==null ? null : Bukkit.getPlayer(record.claimant);
        if(owner!=null && owner.isOnline()) owner.getPersistentDataContainer().remove(receipt(record.id));
        clearSource(record);
        Item drop=drops.remove(record.id);
        if(drop!=null) { protectedDrops.remove(drop.getUniqueId()); drop.remove(); }
        return true;
    }

    private boolean persist(DeliveryRecord record, Map<String,Object> previous) {
        plugin.getDataManager().stageProtectedDelivery(record.id,record.serialize());
        if(plugin.getDataManager().saveSafely()) { scheduleRecord(record); return true; }
        plugin.getDataManager().stageProtectedDelivery(record.id,previous);
        trackRecord(DeliveryRecord.deserialize(record.id,previous));
        return false;
    }

    public boolean recoverHead(UUID target, Player recipient) {
        if(loadFailed || !plugin.getDataManager().isDead(target) || plugin.getAltarGui().hasPendingRevival(target) || recipient==null) return false;
        var data=plugin.getDataManager();
        DeliveryRecord record=new DeliveryRecord(UUID.randomUUID(),plugin.getItemManager().createHead(Bukkit.getOfflinePlayer(target)),
            recipient.getLocation(),DeliveryRecord.Kind.PLAYER,0,null);
        record.recipient=recipient.getUniqueId(); trackRecord(record);
        data.stageProtectedDelivery(record.id,record.serialize());
        if(data.saveSafely()) return true;
        unstage(List.of(record.id)); return false;
    }

    public List<String> describePending() {
        List<String> lines=new ArrayList<>();
        for(DeliveryRecord record:records.values()) {
            lines.add(record.id+" | "+record.item.getType()+" x"+record.item.getAmount()+" | "+record.kind
                +(record.claimant==null ? "" : " | awaiting "+record.claimant));
            if(lines.size()==20) break;
        }
        return lines;
    }

    private void trackRecord(DeliveryRecord record) {
        records.put(record.id, record);
        scheduleRecord(record);
    }

    /** Scheduling is only a RAM index; the persisted record remains the sole ownership authority. */
    private void scheduleRecord(DeliveryRecord record) {
        clearWait(record.id);
        long now = System.currentTimeMillis();
        boolean placed = record.kind == DeliveryRecord.Kind.BLOCK || record.kind == DeliveryRecord.Kind.FRAME;
        long due = record.debitOwner == null && record.claimant == null && placed
            ? (plugin.getItemManager().hasHeadSkin(record.item) ? Math.max(record.due, now) : Long.MAX_VALUE) : now;
        work.schedule(record.id, due);
    }

    private void waitRecord(DeliveryRecord record, long delay, UUID player, UUID world, ChunkKey chunk) {
        clearWait(record.id);
        waits.put(record.id, new Wait(player, world, chunk));
        if (player != null) playerWaiters.computeIfAbsent(player, ignored -> new LinkedHashSet<>()).add(record.id);
        if (world != null) worldWaiters.computeIfAbsent(world, ignored -> new LinkedHashSet<>()).add(record.id);
        if (chunk != null) chunkWaiters.computeIfAbsent(chunk, ignored -> new LinkedHashSet<>()).add(record.id);
        work.schedule(record.id, System.currentTimeMillis() + Math.max(1L, delay));
    }

    private void clearWait(UUID id) {
        Wait wait = waits.remove(id);
        if (wait == null) return;
        removeWaiter(playerWaiters, wait.player, id);
        removeWaiter(worldWaiters, wait.world, id);
        removeWaiter(chunkWaiters, wait.chunk, id);
    }

    private <K> void removeWaiter(Map<K, Set<UUID>> index, K key, UUID id) {
        Set<UUID> ids = key == null ? null : index.get(key);
        if (ids != null && ids.remove(id) && ids.isEmpty()) index.remove(key);
    }

    private void wake(Set<UUID> ids) { wake(ids, MAX_QUEUE_VISITS); }

    /** Wake signals are hints; untouched IDs retain their existing retry deadlines. */
    private int wake(Set<UUID> ids, int limit) {
        if (ids == null) return 0;
        long now = System.currentTimeMillis();
        int visited = 0;
        for (UUID id : ids) {
            if (visited >= limit) break;
            visited++;
            if (records.containsKey(id) && !flights.containsKey(id)) work.earlier(id, now);
        }
        return visited;
    }

    private void wakeWorld(UUID world) {
        if (worldWaiters.containsKey(world)) pendingWorldWakes.add(world);
    }

    /** Many moving players share one bounded world refresh per queue pass. */
    private void flushWorldWakes() {
        int remaining = MAX_QUEUE_VISITS;
        List<UUID> repeat = new ArrayList<>();
        Iterator<UUID> worlds = pendingWorldWakes.iterator();
        while (worlds.hasNext() && remaining > 0) {
            UUID world = worlds.next(); worlds.remove();
            Set<UUID> ids = worldWaiters.get(world);
            int visited = wake(ids, remaining);
            remaining -= visited;
            // Put a large waiting world behind the others; never copy its complete ID set.
            if (ids != null && ids.size() > visited) repeat.add(world);
        }
        pendingWorldWakes.addAll(repeat);
        plugin.getPerformanceCounters().add("world-wake-visits", MAX_QUEUE_VISITS - remaining);
    }

    private int pulses;
    private void tick() {
        if(stopped) return;
        if (!flights.isEmpty()) {
            long start = System.nanoTime();
            try {
                for (Flight flight : new ArrayList<>(flights.values())) {
                    try { flight.tick(); }
                    catch (RuntimeException error) {
                        try { flight.stop(); } catch (RuntimeException ignored) {}
                        plugin.getLogger().warning("Head flight remains queued after animation failure: " + error.getMessage());
                    }
                }
            } finally { plugin.getPerformanceCounters().record("head-flights", start); }
        }
        // Rescue below the build limit, never from the lowest playable layers of the world.
        if (++pulses % 2 == 0) for(Item item:new ArrayList<>(protectedDrops.values())) {
            if(!item.isValid()) { protectedDrops.remove(item.getUniqueId()); continue; }
            rescueFromVoid(item);
        }
        if(pulses % 10 != 0) return;
        processQueue();
    }

    private void processQueue() {
        long start = System.nanoTime();
        flushWorldWakes();
        long now = System.currentTimeMillis();
        List<UUID> ready = work.ready(now, MAX_QUEUE_VISITS);
        int visited = 0, actions = 0;
        try {
            for (UUID id : ready) {
                if (actions >= MAX_QUEUE_ACTIONS || (visited > 0 && System.nanoTime() - start >= QUEUE_BUDGET_NANOS)) break;
                visited++;
                DeliveryRecord record = records.get(id);
                if (record == null) continue;
                work.cancel(id); clearWait(id);
                plugin.getPerformanceCounters().increment("queue-visited");
                try { if (processRecord(record)) actions++; }
                finally {
                    // persist may replace an object on rollback: always reschedule its current canonical record.
                    DeliveryRecord current = records.get(id);
                    if (current != null && !flights.containsKey(id) && !work.contains(id))
                        waitRecord(current, 500L, null, null, null);
                }
            }
        } finally {
            if (visited < ready.size()) plugin.getPerformanceCounters().increment("queue-budget-yields");
            for (int i = visited; i < ready.size(); i++) {
                UUID id = ready.get(i);
                if (records.containsKey(id) && !flights.containsKey(id)) work.earlier(id, now);
            }
            plugin.getPerformanceCounters().record("protected-queue", start);
        }
    }

    private boolean processRecord(DeliveryRecord record) {
        if (record.debitOwner != null) {
            UUID debit = record.debitReceipt, ownerId = record.debitOwner;
            Player owner = Bukkit.getPlayer(ownerId);
            if (pendingRollbacks.containsKey(debit)) confirmRollback(owner, debit, false);
            if (pendingRollbacks.containsKey(debit)) {
                waitRecord(record, Math.max(500L, retryAfter.getOrDefault(debit,0L)-System.currentTimeMillis()),ownerId,null,null);
                return false;
            }
            if (pendingDebits.containsKey(debit)) {
                BlockPlaceEvent placement = placementEvents.get(debit);
                if (placement != null && owner != null) settlePlacement(record, owner, debit, placement);
                else sealDebit(owner, debit);
                record = records.get(record.id);
                if (record == null) return true;
                if (pendingDebits.containsKey(debit)) {
                    waitRecord(record, Math.max(500L,retryAfter.getOrDefault(debit,0L)-System.currentTimeMillis()),ownerId,null,null);
                    return true;
                }
            }
            if (record.debitOwner != null) {
                // After a restart, read the marker through Paper when the owner joins.
                // Normal live transfers release their debit immediately; no player-file polling is needed.
                if (owner == null || !owner.isOnline()) {
                    waitRecord(record, 60_000L, ownerId, null, null);
                    return false;
                }
                boolean paid = owner.getPersistentDataContainer().has(debitReceipt(debit), PersistentDataType.BYTE);
                if (!paid) { finish(record); return true; }
                if (!releaseDebit(debit)) return true;
            }
        }
        if(record.claimant!=null) {
            Player player=Bukkit.getPlayer(record.claimant);
            if(player!=null && player.isOnline() && player.getPersistentDataContainer().has(receipt(record.id),PersistentDataType.BYTE)) {
                finish(record); return true;
            }
            if(player!=null && eligible(player) && hasRoom(player,record.item)) { give(record,player); return true; }
            waitRecord(record,player==null ? 5_000L : 1_000L,record.claimant,null,null); return false;
        }
        if(record.kind==DeliveryRecord.Kind.PLAYER) {
            Player player=Bukkit.getPlayer(record.recipient);
            if(player!=null && eligible(player) && hasRoom(player,record.item)) { give(record,player); return true; }
            waitRecord(record,player==null ? 5_000L : 1_000L,record.recipient,null,null); return false;
        }
        Location location=record.location();
        if(location==null) { waitRecord(record,10_000L,null,record.worldId,null); return false; }
        if(record.kind==DeliveryRecord.Kind.DROP) {
            boolean inVoid=belowWorld(location);
            if(!loaded(location) && (!inVoid || nearestVoidPlayer(location)==null)) {
                waitRecord(record,5_000L,null,inVoid ? record.worldId : null,
                    new ChunkKey(record.worldId,location.getBlockX()>>4,location.getBlockZ()>>4)); return false;
            }
            Item item=drops.get(record.id);
            if(item==null || !item.isValid()) { spawnDrop(record); return true; }
            waitRecord(record,2_000L,null,null,null); return false;
        }
        // Old versions may have already queued an empty head. Keep its source without an automatic return.
        if((record.kind==DeliveryRecord.Kind.BLOCK || record.kind==DeliveryRecord.Kind.FRAME)
            && !plugin.getItemManager().hasHeadSkin(record.item)) { work.schedule(record.id,Long.MAX_VALUE); return false; }
        if(flights.containsKey(record.id)) return false;
        if(!ProtectionRules.isDue(record.due,System.currentTimeMillis())) { work.schedule(record.id,record.due); return false; }
        Player nearest=nearest(location);
        if(nearest==null || !hasRoom(nearest,record.item)) {
            waitRecord(record,nearest==null ? 2_000L : 1_000L,nearest==null ? null : nearest.getUniqueId(),record.worldId,null); return false;
        }
        if(flights.size()>=MAX_FLIGHTS) { waitRecord(record,500L,null,null,null); return false; }
        if(record.kind!=DeliveryRecord.Kind.RETURN) {
            Map<String,Object> before=record.serialize(); record.kind=DeliveryRecord.Kind.RETURN;
            if(!persist(record,before)) return true;
        }
        clearSource(record); launch(record,nearest); return true;
    }

    private void spawnDrop(DeliveryRecord record) {
        spawnDrop(record,record.location());
    }

    private void spawnDrop(DeliveryRecord record, Location location) {
        if(record.debitOwner!=null) return;
        if(location==null) return;
        boolean inVoid=belowWorld(location);
        if(inVoid) location=voidDestination(location);
        ItemStack item=record.item.clone(); markDelivery(item,record.id);
        Item entity=location.getWorld().dropItem(location,item,drop->{
            plugin.getItemManager().makeItemPersistent(drop);
            if(inVoid) holdForVoidRescue(drop);
        });
        if(!entity.isValid()) return; // A cancelled spawn leaves the saved delivery available for retry.
        plugin.getItemManager().makeItemPersistent(entity);
        drops.put(record.id,entity); protectedDrops.put(entity.getUniqueId(),entity);
        // ItemSpawn schedules rescue after entity registration; the pulse also covers loaded drops.
    }

    private void launch(DeliveryRecord record, Player player) {
        if (flights.size() >= MAX_FLIGHTS || flights.containsKey(record.id)) return;
        try {
            Location origin=loaded(record.location()) ? record.location().clone().add(0.5,0.75,0.5) : player.getLocation().add(0,3,0);
            ItemDisplay display=origin.getWorld().spawn(origin,ItemDisplay.class);
            display.setPersistent(false); display.setItemStack(record.item.clone());
            display.setTeleportDuration(2); display.setViewRange(2.0f);
            Flight flight=new Flight(record,player,origin,display); flights.put(record.id,flight);
            work.cancel(record.id); clearWait(record.id);
            plugin.getPerformanceCounters().increment("flights-started");
        } catch(RuntimeException error) {
            plugin.getLogger().warning("Head flight remains queued: "+error.getMessage());
        }
    }

    private final class Flight {
        final DeliveryRecord record; final Player player; final Location origin; final ItemDisplay display;
        int tick;
        Flight(DeliveryRecord record,Player player,Location origin,ItemDisplay display) {
            this.record=record; this.player=player; this.origin=origin; this.display=display;
        }
        void tick() {
            if(stopped || !eligible(player) || !player.getWorld().equals(origin.getWorld()) || !display.isValid()
                || !hasRoom(player,record.item) || records.get(record.id)!=record) { stop(); return; }
            double progress=Math.min(1.0,++tick/60.0);
            double ease=progress*progress*(3-2*progress);
            Location end=player.getLocation().add(0,1.1,0);
            Vector offset=end.toVector().subtract(origin.toVector()).multiply(ease);
            Location point=origin.clone().add(offset).add(0,Math.sin(progress*Math.PI)*2.0,0);
            // A distant target never causes terrain generation just for a visual effect.
            if(loaded(point)) {
                if(!display.teleport(point)) { stop(); return; }
                if(tick%4==0) point.getWorld().spawnParticle(Particle.ENCHANT,point,3,0.1,0.1,0.1,0.02);
            }
            if(progress>=1.0) { give(record,player); stop(); }
        }
        void stop() {
            try { display.remove(); }
            finally {
                flights.remove(record.id,this);
                DeliveryRecord current=records.get(record.id);
                if(!stopped && current!=null) scheduleRecord(current);
            }
        }
    }

    private Player nearest(Location location) {
        return location.getWorld().getPlayers().stream().filter(this::eligible)
            .min(java.util.Comparator.comparingDouble(player->player.getLocation().distanceSquared(location))).orElse(null);
    }

    private boolean belowWorld(Location location) {
        return location.getY()<location.getWorld().getMinHeight()-8;
    }

    private Player nearestVoidPlayer(Location origin) {
        return origin.getWorld().getPlayers().stream().filter(this::eligible)
            // A player falling into the void is not a safe destination for another falling item.
            .filter(player->player.getLocation().getY()>=origin.getWorld().getMinHeight())
            .min(java.util.Comparator.comparingDouble(player->player.getLocation().distanceSquared(origin))).orElse(null);
    }

    private Location voidDestination(Location origin) {
        Player player=nearestVoidPlayer(origin);
        if(player!=null) return player.getLocation().add(0,0.5,0);
        Location holding=origin.clone(); holding.setY(origin.getWorld().getMinHeight()+2);
        return holding;
    }

    private void holdForVoidRescue(Item item) {
        var pdc=item.getPersistentDataContainer();
        // Persist the waiting state on the native entity, without assigning a serial number to the head.
        if(!pdc.has(voidRescueKey,PersistentDataType.BYTE))
            pdc.set(voidRescueKey,PersistentDataType.BYTE,(byte)(item.hasGravity() ? 1 : 0));
        item.setGravity(false); item.setVelocity(new Vector());
    }

    private void rescueFromVoid(Item item) {
        if(!item.isValid()) return;
        Location origin=item.getLocation();
        var pdc=item.getPersistentDataContainer();
        if(!belowWorld(origin) && !pdc.has(voidRescueKey,PersistentDataType.BYTE)) return;
        holdForVoidRescue(item);
        Player player=nearestVoidPlayer(origin);
        if(player==null) {
            if(belowWorld(origin)) item.teleport(voidDestination(origin));
            return; // Keep floating until a living player in this world is available, even after a reload.
        }
        if(item.teleport(player.getLocation().add(0,0.5,0))) {
            item.setVelocity(new Vector());
            item.setGravity(pdc.getOrDefault(voidRescueKey,PersistentDataType.BYTE,(byte)1)==1);
            pdc.remove(voidRescueKey);
        }
    }

    private boolean eligible(Player player) {
        return player!=null && player.isOnline() && !player.isDead() && player.getGameMode()!=GameMode.SPECTATOR
            && !plugin.getDataManager().isDead(player.getUniqueId());
    }

    private boolean hasRoom(Player player,ItemStack stack) {
        int remaining=stack.getAmount();
        for(ItemStack current:player.getInventory().getStorageContents()) {
            if(current==null || current.getType().isAir()) remaining-=Math.min(stack.getMaxStackSize(),player.getInventory().getMaxStackSize());
            else if(current.isSimilar(stack)) remaining-=Math.max(0,Math.min(current.getMaxStackSize(),player.getInventory().getMaxStackSize())-current.getAmount());
            if(remaining<=0) return true;
        }
        return false;
    }

    private void clearSource(DeliveryRecord record) {
        Location location=record.location();
        if(location==null || !loaded(location)) return;
        BlockState state=location.getBlock().getState();
        if(state instanceof Skull skull && record.id.equals(readId(skull.getPersistentDataContainer().get(deliveryKey,PersistentDataType.STRING)))) {
            location.getBlock().setType(Material.AIR,false);
        }
        if(record.frameId!=null) {
            Entity entity=Bukkit.getEntity(record.frameId);
            if(entity instanceof ItemFrame frame && record.id.equals(itemId(frame.getItem()))) frame.setItem(null);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item=event.getItemInHand();
        if(!event.canBuild()) return;
        if(plugin.getItemManager().isHeart(item)) { event.setCancelled(true); return; }
        if(!plugin.getItemManager().isPlayerHead(item)) return;
        if(!(event.getBlockPlaced().getState() instanceof Skull skull) || loadFailed) { event.setCancelled(true); return; }
        ItemStack head=item.clone(); head.setAmount(1); stripDelivery(head);
        if(!plugin.getItemManager().hasHeadSkin(head) && !plugin.getItemManager().isProtectedHead(head)) {
            // Empty heads need no timer/return record. Keep their full metadata on the block for breaking.
            skull.getPersistentDataContainer().set(placedHeadKey,PersistentDataType.BYTE_ARRAY,head.serializeAsBytes());
            if(!skull.update(true,false)) event.setCancelled(true);
            return;
        }
        DeliveryRecord record=new DeliveryRecord(UUID.randomUUID(),head,event.getBlockPlaced().getLocation(),DeliveryRecord.Kind.BLOCK,
            System.currentTimeMillis()+ProtectionRules.HEAD_RETURN_DELAY_MILLIS,null);
        record.debitOwner=event.getPlayer().getUniqueId(); record.debitReceipt=UUID.randomUUID();
        trackRecord(record); plugin.getDataManager().stageProtectedDelivery(record.id,record.serialize());
        if(!plugin.getDataManager().saveSafely()) { unstage(List.of(record.id)); event.setCancelled(true); return; }
        UUID debitId=record.debitReceipt;
        pendingDebits.put(debitId,event.getPlayer().getUniqueId());
        placementEvents.put(debitId,event);
        if(event.getPlayer().getGameMode()==GameMode.CREATIVE) creativePlacements.add(debitId);
        skull.getPersistentDataContainer().set(deliveryKey,PersistentDataType.STRING,record.id.toString());
        if(!skull.update(true,false)) event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin,()->settlePlacement(record,event.getPlayer(),debitId,event));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onPlaceResolved(BlockPlaceEvent event) {
        for(var entry:placementEvents.entrySet()) if(entry.getValue()==event) {
            NamespacedKey key=debitReceipt(entry.getKey());
            if(event.isCancelled() || !event.canBuild()) event.getPlayer().getPersistentDataContainer().remove(key);
            else {
                if(creativePlacements.contains(entry.getKey()) && !creativeDebits.contains(entry.getKey())) {
                    // Creative normally creates a block without a debit. Protected heads still move one real item.
                    ItemStack held=event.getPlayer().getInventory().getItem(event.getHand());
                    if(held==null || !held.isSimilar(event.getItemInHand()) || held.getAmount()<1) {
                        event.setCancelled(true); continue;
                    }
                    ItemStack remaining=held.clone(); remaining.setAmount(held.getAmount()-1);
                    event.getPlayer().getInventory().setItem(event.getHand(),remaining.getAmount()==0 ? null : remaining);
                    creativeDebits.add(entry.getKey());
                }
                event.getPlayer().getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);
            }
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onBreak(BlockBreakEvent event) {
        if(!protectedSkull(event.getBlock()) || !(event.getBlock().getState() instanceof Skull skull)) return;
        UUID id=readId(skull.getPersistentDataContainer().get(deliveryKey,PersistentDataType.STRING));
        event.setCancelled(true);
        DeliveryRecord record=id==null ? null : records.get(id);
        // A stamped source without its operation has already been delivered, including from an unloaded chunk.
        if(id!=null && record==null) { event.getBlock().setType(Material.AIR,false); return; }
        if(record==null) record=registerSkull(skull);
        if(record!=null) {
            // A failed save leaves the real head in its block, including in Creative.
            turnIntoDrop(record);
        } else if(protectedSkull(event.getBlock())) {
            ItemStack head=headFromSkull(skull);
            if(head==null) return;
            if(!plugin.getItemManager().hasHeadSkin(head)) {
                // Plain heads stay physical and need neither a return timer nor an issuance record.
                Item item=skull.getWorld().dropItem(skull.getLocation().add(0.5,0.5,0.5),head);
                if(!item.isValid()) return;
                try {
                    plugin.getItemManager().makeItemPersistent(item);
                    skull.getBlock().setType(Material.AIR,false);
                } catch(RuntimeException error) { item.remove(); throw error; }
                return;
            }
        }
    }

    private void turnIntoDrop(DeliveryRecord record) {
        Map<String,Object> before=record.serialize(); record.kind=DeliveryRecord.Kind.DROP;
        if(!persist(record,before)) return;
        Flight flight=flights.get(record.id); if(flight!=null) flight.stop();
        clearSource(record); spawnDrop(record);
    }

    private boolean protectedSkull(Block block) {
        return block.getType()==Material.PLAYER_HEAD || block.getType()==Material.PLAYER_WALL_HEAD;
    }

    /** Reuse the durable delivery queue only for a timed return or an actual manual break. */
    private DeliveryRecord registerSkull(Skull skull) {
        if(loadFailed) return null;
        ItemStack head=headFromSkull(skull);
        if(head==null) return null;
        stripDelivery(head);
        var pdc=skull.getPersistentDataContainer();
        // A save can complete before the source stamp. Reattach that operation instead of issuing a second head.
        for(DeliveryRecord existing:records.values()) {
            // A newly placed empty head is a new physical source, even while an older return is still flying.
            if(pdc.has(placedHeadKey,PersistentDataType.BYTE_ARRAY)) break;
            if(existing.frameId==null && existing.due>0 && existing.worldId.equals(skull.getWorld().getUID())
                && existing.x==skull.getX() && existing.y==skull.getY() && existing.z==skull.getZ()) {
                pdc.set(deliveryKey,PersistentDataType.STRING,existing.id.toString());
                if(!skull.update(true,false)) return null;
                if(existing.kind!=DeliveryRecord.Kind.BLOCK) { clearSource(existing); return null; }
                return existing;
            }
        }
        if(!pdc.has(placedHeadKey,PersistentDataType.BYTE_ARRAY)
            && plugin.getDataManager().isRetiredHeadBlock(skull.getWorld().getUID(),skull.getX(),skull.getY(),skull.getZ())) {
            skull.getBlock().setType(Material.AIR,false); return null;
        }
        if(!plugin.getItemManager().hasHeadSkin(head)) return null;
        DeliveryRecord record=new DeliveryRecord(UUID.randomUUID(),head,skull.getLocation(),DeliveryRecord.Kind.BLOCK,
            System.currentTimeMillis()+ProtectionRules.HEAD_RETURN_DELAY_MILLIS,null);
        trackRecord(record); plugin.getDataManager().stageProtectedDelivery(record.id,record.serialize());
        if(!plugin.getDataManager().saveSafely()) { unstage(List.of(record.id)); return null; }
        pdc.set(deliveryKey,PersistentDataType.STRING,record.id.toString());
        return skull.update(true,false) ? record : null;
    }

    private ItemStack headFromSkull(Skull skull) {
        byte[] saved=skull.getPersistentDataContainer().get(placedHeadKey,PersistentDataType.BYTE_ARRAY);
        if(saved!=null) {
            try {
                ItemStack head=ItemStack.deserializeBytes(saved);
                if(plugin.getItemManager().isPlayerHead(head)) { head.setAmount(1); return head; }
            } catch(RuntimeException error) {
                plugin.getLogger().warning("Placed head metadata could not be read; leaving its block intact: "+error.getMessage());
                return null;
            }
        }
        for(ItemStack drop:skull.getBlock().getDrops()) if(plugin.getItemManager().isPlayerHead(drop)) {
            ItemStack head=drop.clone(); head.setAmount(1); return head;
        }
        return null;
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onExplosion(EntityExplodeEvent event) { event.blockList().removeIf(this::protectedSkull); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onBlockExplosion(BlockExplodeEvent event) { event.blockList().removeIf(this::protectedSkull); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onPhysics(BlockPhysicsEvent event) { if(protectedSkull(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onHeadBurn(BlockBurnEvent event) { if(protectedSkull(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onHeadFade(BlockFadeEvent event) { if(protectedSkull(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onHeadFlow(BlockFromToEvent event) { if(protectedSkull(event.getToBlock())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onHeadBlockChange(EntityChangeBlockEvent event) { if(protectedSkull(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onPiston(BlockPistonExtendEvent event) { if(event.getBlocks().stream().anyMatch(this::protectedSkull)) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onPistonPull(BlockPistonRetractEvent event) { if(event.getBlocks().stream().anyMatch(this::protectedSkull)) event.setCancelled(true); }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onFrameInteraction(PlayerInteractEntityEvent event) {
        if(!(event.getRightClicked() instanceof ItemFrame frame) || !frame.getItem().getType().isAir()) return;
        Player player=event.getPlayer();
        ItemStack held=player.getInventory().getItem(event.getHand());
        if(!plugin.getItemManager().hasHeadSkin(held) && !plugin.getItemManager().isProtectedHead(held)) return;
        event.setCancelled(true);
        if(loadFailed) return;
        ItemStack head=held.clone(); head.setAmount(1); stripDelivery(head);
        DeliveryRecord record=new DeliveryRecord(UUID.randomUUID(),head,frame.getLocation(),DeliveryRecord.Kind.FRAME,
            System.currentTimeMillis()+ProtectionRules.HEAD_RETURN_DELAY_MILLIS,frame.getUniqueId());
        record.debitOwner=player.getUniqueId(); record.debitReceipt=UUID.randomUUID();
        boolean wasRetired=plugin.getDataManager().isRetiredHeadFrame(frame.getUniqueId());
        plugin.getDataManager().setRetiredHeadFrame(frame.getUniqueId(),false);
        trackRecord(record); plugin.getDataManager().stageProtectedDelivery(record.id,record.serialize());
        if(!plugin.getDataManager().saveSafely()) { plugin.getDataManager().setRetiredHeadFrame(frame.getUniqueId(),wasRetired); unstage(List.of(record.id)); return; }
        // The frame insertion is handled here, so queue, item debit and receipt exist before another tick/quit.
        markDelivery(head,record.id); frame.setItem(head);
        ItemStack remaining=held.clone(); remaining.setAmount(held.getAmount()-1);
        player.getInventory().setItem(event.getHand(),remaining.getAmount()==0 ? null : remaining);
        pendingDebits.put(record.debitReceipt,player.getUniqueId());
        player.getPersistentDataContainer().set(debitReceipt(record.debitReceipt),PersistentDataType.BYTE,(byte)1);
        sealDebit(player,record.debitReceipt);
    }

    /** Existing saved frames have no new player debit; retain their item in the same durable return queue. */
    private void registerFrame(ItemFrame frame) {
        if(!frame.isValid() || !plugin.getItemManager().hasHeadSkin(frame.getItem()) || itemId(frame.getItem())!=null || loadFailed) return;
        DeliveryRecord existing=records.values().stream().filter(r->frame.getUniqueId().equals(r.frameId)).findFirst().orElse(null);
        if(existing!=null) {
            if(existing.kind!=DeliveryRecord.Kind.FRAME) frame.setItem(null);
            else { ItemStack restored=existing.item.clone(); markDelivery(restored,existing.id); frame.setItem(restored); }
            return;
        }
        if(plugin.getDataManager().isRetiredHeadFrame(frame.getUniqueId())) { frame.setItem(null); return; }
        ItemStack head=frame.getItem().clone(); head.setAmount(1);
        DeliveryRecord record=new DeliveryRecord(UUID.randomUUID(),head,frame.getLocation(),DeliveryRecord.Kind.FRAME,
            System.currentTimeMillis()+ProtectionRules.HEAD_RETURN_DELAY_MILLIS,frame.getUniqueId());
        trackRecord(record); plugin.getDataManager().stageProtectedDelivery(record.id,record.serialize());
        if(!plugin.getDataManager().saveSafely()) { unstage(List.of(record.id)); return; }
        markDelivery(head,record.id); frame.setItem(head);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onFrameBreak(HangingBreakEvent event) {
        if(event.getEntity() instanceof ItemFrame frame && plugin.getItemManager().containsProtectedItem(frame.getItem())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onDamage(EntityDamageEvent event) {
        if(event.getEntity() instanceof Item item && plugin.getItemManager().containsProtectedItem(item.getItemStack())) event.setCancelled(true);
        if(event.getEntity() instanceof ItemFrame frame && plugin.getItemManager().containsProtectedItem(frame.getItem())) {
            event.setCancelled(true);
            if(event instanceof EntityDamageByEntityEvent attack && attack.getDamager() instanceof Player) {
                UUID id=itemId(frame.getItem());
                if(id!=null && records.containsKey(id)) turnIntoDrop(records.get(id));
                else {
                    ItemStack stack=frame.getItem();
                    // Untimed contents stay physical. A cancelled spawn leaves the original safely in the frame.
                    Item item=frame.getWorld().dropItem(frame.getLocation(),stack);
                    if(!item.isValid()) return;
                    try {
                        plugin.getItemManager().makeItemPersistent(item);
                        frame.setItem(null);
                    } catch(RuntimeException error) { item.remove(); throw error; }
                }
            }
        }
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onSpawn(ItemSpawnEvent event) {
        protect(event.getEntity());
        // Spawn/added events run during registration, when Paper cannot safely teleport the entity.
        if(plugin.getItemManager().containsProtectedItem(event.getEntity().getItemStack()))
            Bukkit.getScheduler().runTask(plugin,()->{
                Item item=event.getEntity();
                rescueFromVoid(item);
            });
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Item item=event.getItemDrop();
        UUID id=itemId(item.getItemStack());
        if(id!=null && !records.containsKey(id) && plugin.getItemManager().containsPlayerHead(item.getItemStack())) {
            // An inventory head can retain an old completed-delivery stamp after upgrading.
            // Clean it only on a real player throw; old world sources must still be removed to prevent duplication.
            ItemStack stack=item.getItemStack(); stripDelivery(stack); item.setItemStack(stack);
        }
        if(plugin.getItemManager().containsProtectedItem(item.getItemStack())) plugin.getItemManager().makeItemPersistent(item);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onAdded(EntityAddToWorldEvent event) {
        // Register protection now; the scheduled task or pulse moves the item after this callback.
        if(event.getEntity() instanceof Item item) protect(item);
        else if(event.getEntity() instanceof ItemFrame frame) registerFrame(frame);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onDespawn(ItemDespawnEvent event) {
        if(plugin.getItemManager().containsProtectedItem(event.getEntity().getItemStack())) { event.setCancelled(true); protect(event.getEntity()); }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onPickup(EntityPickupItemEvent event) {
        Item item=event.getItem();
        if(!(event.getEntity() instanceof Player player)) {
            if(plugin.getItemManager().containsProtectedItem(item.getItemStack())) event.setCancelled(true);
            return;
        }
        UUID id=itemId(item.getItemStack());
        if(id==null) return;
        event.setCancelled(true);
        DeliveryRecord record=records.get(id);
        if(record==null) { item.remove(); return; }
        if(record.kind==DeliveryRecord.Kind.DROP) give(record,player);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if(itemId(event.getItem().getItemStack())!=null) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onMerge(ItemMergeEvent event) {
        if(itemId(event.getEntity().getItemStack())!=null || itemId(event.getTarget().getItemStack())!=null) event.setCancelled(true);
    }

    private void protect(Item item) {
        if(plugin.getDataManager().isRetiredProtectedDrop(item.getUniqueId())) { item.remove(); return; }
        UUID id=itemId(item.getItemStack());
        if(id!=null) {
            DeliveryRecord record=records.get(id);
            if(record==null || record.kind!=DeliveryRecord.Kind.DROP || record.claimant!=null) { item.remove(); return; }
            Item previous=drops.putIfAbsent(id,item);
            if(previous!=null && previous!=item && previous.isValid()) { item.remove(); return; }
            drops.put(id,item);
        }
        if(plugin.getItemManager().containsProtectedItem(item.getItemStack()) || id!=null) {
            plugin.getItemManager().makeItemPersistent(item); protectedDrops.put(item.getUniqueId(),item);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onRemoved(EntityRemoveEvent event) {
        if(!(event.getEntity() instanceof Item item)) return;
        protectedDrops.remove(item.getUniqueId());
        UUID id=itemId(item.getItemStack());
        if(id!=null) drops.remove(id,item);
        if(stopped || event.getCause()!=EntityRemoveEvent.Cause.OUT_OF_WORLD) return;
        if(plugin.getDataManager().isRetiredProtectedDrop(item.getUniqueId())) return;
        // Exceptionally fast movement can cross the entire void between rescue pulses.
        // Existing delivery IDs already own their stack: reuse that record, never create a second one.
        if(id!=null) {
            DeliveryRecord record=records.get(id);
            if(record!=null && record.kind==DeliveryRecord.Kind.DROP && record.claimant==null) {
                spawnDrop(record,item.getLocation());
                scheduleRecord(record);
            }
            return;
        }
        if(!plugin.getItemManager().containsProtectedItem(item.getItemStack())) return;
        try {
            stageDrops(item.getLocation(),List.of(item.getItemStack()));
            // Save the source tombstone with its replacement, before an old entity-region can reload it.
            plugin.getDataManager().retireProtectedDrop(item.getUniqueId());
            // Removal cannot be cancelled. Keep the in-memory recovery too if the disk is temporarily unavailable.
            if(!plugin.getDataManager().saveSafely()) plugin.getLogger().severe("Void recovery awaits a successful save");
        } catch(RuntimeException error) {
            plugin.getLogger().severe("Cannot queue protected item removed by the void: "+error.getMessage());
        }
    }

    @EventHandler public void onChunk(ChunkLoadEvent event) {
        Chunk chunk=event.getChunk();
        wake(chunkWaiters.get(new ChunkKey(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ())));
        Bukkit.getScheduler().runTask(plugin,()->scan(chunk));
    }
    @EventHandler public void onWorld(WorldLoadEvent event) { wakeWorld(event.getWorld().getUID()); }
    @EventHandler public void onEntities(EntitiesLoadEvent event) { for(Entity entity:event.getEntities()) scanEntity(entity); }
    @EventHandler public void onUnload(EntitiesUnloadEvent event) {
        for(Entity entity:event.getEntities()) if(entity instanceof Item item) {
            protectedDrops.remove(item.getUniqueId()); UUID id=itemId(item.getItemStack());
            if(id!=null) drops.remove(id,item);
        }
    }
    private void scan(Chunk chunk) {
        if(!chunk.isLoaded()) return;
        for(Entity entity:chunk.getEntities()) scanEntity(entity);
        for(BlockState state:chunk.getTileEntities()) if(protectedSkull(state.getBlock()) && state instanceof Skull skull) {
            UUID id=readId(skull.getPersistentDataContainer().get(deliveryKey,PersistentDataType.STRING));
            if(id!=null) {
                if(!records.containsKey(id) || records.get(id).kind!=DeliveryRecord.Kind.BLOCK) skull.getBlock().setType(Material.AIR,false);
            } else registerSkull(skull);
        }
    }
    private void scanEntity(Entity entity) {
        if(entity instanceof Item item) protect(item);
        else if(entity instanceof ItemFrame frame) {
            UUID id=itemId(frame.getItem());
            if(id!=null && (!records.containsKey(id) || records.get(id).kind!=DeliveryRecord.Kind.FRAME)) frame.setItem(null);
            else registerFrame(frame);
        }
    }

    @EventHandler public void onPrepare(PrepareItemCraftEvent event) {
        if(Arrays.stream(event.getInventory().getMatrix()).anyMatch(plugin.getItemManager()::containsProtectedItem)) event.getInventory().setResult(null);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onCraft(CraftItemEvent event) {
        if(Arrays.stream(event.getInventory().getMatrix()).anyMatch(plugin.getItemManager()::containsProtectedItem)) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onAutoCraft(CrafterCraftEvent event) {
        if(event.getBlock().getState() instanceof Crafter crafter
            && Arrays.stream(crafter.getInventory().getContents()).anyMatch(plugin.getItemManager()::containsProtectedItem)) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onSmelt(FurnaceSmeltEvent event) { if(plugin.getItemManager().containsProtectedItem(event.getSource())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onBurn(FurnaceBurnEvent event) { if(plugin.getItemManager().containsProtectedItem(event.getFuel())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onCook(BlockCookEvent event) { if(plugin.getItemManager().containsProtectedItem(event.getSource())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onBrew(BrewEvent event) {
        if(Arrays.stream(event.getContents().getContents()).anyMatch(plugin.getItemManager()::containsProtectedItem)) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onBrewingFuel(BrewingStandFuelEvent event) {
        if(plugin.getItemManager().containsProtectedItem(event.getFuel())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if(plugin.getItemManager().isHeart(event.getItem())) event.setUseItemInHand(Event.Result.DENY);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onConsume(PlayerItemConsumeEvent event) { if(plugin.getItemManager().containsProtectedItem(event.getItem())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onBundleInsert(InventoryClickEvent event) {
        boolean insertion=switch(event.getAction()) {
            case PICKUP_ALL_INTO_BUNDLE, PICKUP_SOME_INTO_BUNDLE, PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE -> true;
            default -> false;
        };
        // In 26.3 PRIMARY adds contents; SECONDARY can safely swap these two stacks.
        // Actions are estimates, so LEFT also covers insertion reported as NOTHING/UNKNOWN.
        if(event.getClick()!=ClickType.LEFT && !insertion) return;
        ItemStack cursor=event.getCursor(), slot=event.getCurrentItem();
        if((isBundle(cursor) && plugin.getItemManager().containsPlayerHead(slot))
            || (isBundle(slot) && plugin.getItemManager().containsPlayerHead(cursor))) event.setCancelled(true);
    }

    private boolean isBundle(ItemStack item) {
        return item!=null && item.getItemMeta() instanceof org.bukkit.inventory.meta.BundleMeta;
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onStationClick(InventoryClickEvent event) {
        Inventory top=event.getView().getTopInventory(); InventoryType type=top.getType();
        if(type==InventoryType.ANVIL || type==InventoryType.SMITHING || type==InventoryType.GRINDSTONE
            || type==InventoryType.STONECUTTER || type==InventoryType.MERCHANT) {
            int resultSlot=type==InventoryType.SMITHING ? 3 : type==InventoryType.STONECUTTER ? 1 : 2;
            if(event.getRawSlot()==resultSlot && Arrays.stream(top.getContents()).anyMatch(plugin.getItemManager()::containsProtectedItem)) event.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onWear(PlayerItemDamageEvent event) {
        if(plugin.getItemManager().containsProtectedItem(event.getItem())) event.setCancelled(true);
    }

    // Configurable heart materials must not be consumed as ammunition, food or blocks.
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onDispense(BlockDispenseEvent event) {
        if(plugin.getItemManager().containsProtectedItem(event.getItem())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onShoot(EntityShootBowEvent event) {
        if(plugin.getItemManager().containsProtectedItem(event.getConsumable())
            || plugin.getItemManager().containsProtectedItem(event.getBow())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onResurrect(EntityResurrectEvent event) {
        if(event.getEntity() instanceof Player player && event.getHand()!=null
            && plugin.getItemManager().containsProtectedItem(player.getInventory().getItem(event.getHand()))) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onFeed(PlayerInteractEntityEvent event) {
        if(!(event.getRightClicked() instanceof ItemFrame)
            && plugin.getItemManager().isHeart(event.getPlayer().getInventory().getItem(event.getHand()))) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onCreative(InventoryCreativeEvent event) {
        if(plugin.getItemManager().containsProtectedItem(event.getCurrentItem())
            || plugin.getItemManager().containsProtectedItem(event.getCursor())) event.setCancelled(true);
    }

    private static ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] result=items.clone(); for(int i=0;i<result.length;i++) if(result[i]!=null) result[i]=result[i].clone(); return result;
    }
    private void settlePlacement(DeliveryRecord record, Player player, UUID debit, BlockPlaceEvent event) {
        if(!pendingDebits.containsKey(debit)) return;
        if(event.isCancelled() || !event.canBuild()) {
            if(creativeDebits.contains(debit)) {
                // A later cancellation refunds the manually debited creative head through the durable queue.
                Map<String,Object> before=record.serialize(); record.kind=DeliveryRecord.Kind.PLAYER; record.recipient=player.getUniqueId();
                if(!persist(record,before)) return;
                clearSource(record); sealDebit(player,debit); return;
            }
            if(retryAfter.getOrDefault(debit,0L)>System.currentTimeMillis()) return;
            player.getPersistentDataContainer().remove(debitReceipt(debit));
            try {
                // A cancelled placement retains its original item; remove any premature persisted receipt too.
                GuiEscrow.savePlayerData(player);
                if(finish(record)) {
                    pendingDebits.remove(debit); placementEvents.remove(debit); retryAfter.remove(debit); creativeDebits.remove(debit); creativePlacements.remove(debit);
                } else retryAfter.put(debit,System.currentTimeMillis()+5_000L);
            } catch(RuntimeException error) { retryAfter.put(debit,System.currentTimeMillis()+5_000L); }
        } else sealDebit(player,debit);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        wakeWorld(event.getPlayer().getWorld().getUID());
        for(var entry:new ArrayList<>(pendingRollbacks.entrySet())) {
            if(entry.getValue().equals(event.getPlayer().getUniqueId())) {
                confirmRollback(event.getPlayer(),entry.getKey(),true);
                pendingRollbacks.remove(entry.getKey()); retryAfter.remove(entry.getKey());
            }
        }
        for(var entry:new ArrayList<>(pendingDebits.entrySet())) {
            if(entry.getValue().equals(event.getPlayer().getUniqueId())) {
                BlockPlaceEvent placement=placementEvents.get(entry.getKey());
                DeliveryRecord record=records.values().stream().filter(r->entry.getKey().equals(r.debitReceipt)).findFirst().orElse(null);
                if(placement!=null && record!=null) { retryAfter.remove(entry.getKey()); settlePlacement(record,event.getPlayer(),entry.getKey(),placement); }
                else { retryAfter.remove(entry.getKey()); sealDebit(event.getPlayer(),entry.getKey()); }
                pendingDebits.remove(entry.getKey()); placementEvents.remove(entry.getKey());
                // sealDebit/settlePlacement re-arm retryAfter on failure; nothing will retry once the debit is dropped.
                retryAfter.remove(entry.getKey()); creativeDebits.remove(entry.getKey()); creativePlacements.remove(entry.getKey());
            }
        }
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        wakeAfter(event.getPlayer());
        pendingRollbacks.entrySet().removeIf(entry->entry.getValue().equals(event.getPlayer().getUniqueId()));
        for(var entry:new ArrayList<>(pendingDebits.entrySet())) {
            if(!entry.getValue().equals(event.getPlayer().getUniqueId())) continue;
            pendingDebits.remove(entry.getKey()); placementEvents.remove(entry.getKey());
            retryAfter.remove(entry.getKey()); creativeDebits.remove(entry.getKey()); creativePlacements.remove(entry.getKey());
        }
        Set<NamespacedKey> active=new HashSet<>();
        for(DeliveryRecord record:records.values()) {
            active.add(receipt(record.id));
            if(record.debitReceipt!=null) active.add(debitReceipt(record.debitReceipt));
        }
        var pdc=event.getPlayer().getPersistentDataContainer();
        for(NamespacedKey key:new HashSet<>(pdc.getKeys())) {
            if(key.getNamespace().equals(deliveryKey.getNamespace())
                && (key.getKey().startsWith("protected_received_") || key.getKey().startsWith("protected_debit_"))
                && !active.contains(key)) pdc.remove(key);
        }
    }
    private void wakeAfter(Player player) {
        UUID id=player.getUniqueId();
        if ((!playerWaiters.containsKey(id) && !worldWaiters.containsKey(player.getWorld().getUID())) || !pendingWakes.add(id)) return;
        Bukkit.getScheduler().runTask(plugin,()->{
            pendingWakes.remove(id);
            if (stopped) return;
            wake(playerWaiters.get(id));
            Player current=Bukkit.getPlayer(id);
            if(current!=null) wakeWorld(current.getWorld().getUID());
        });
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onCapacityClick(InventoryClickEvent event) { if(event.getWhoClicked() instanceof Player player) wakeAfter(player); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onCapacityDrag(InventoryDragEvent event) { if(event.getWhoClicked() instanceof Player player) wakeAfter(player); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onCapacityClose(InventoryCloseEvent event) { if(event.getPlayer() instanceof Player player) wakeAfter(player); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onCapacityPickup(EntityPickupItemEvent event) { if(event.getEntity() instanceof Player player) wakeAfter(player); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onCapacityDrop(PlayerDropItemEvent event) { wakeAfter(event.getPlayer()); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onCapacityConsume(PlayerItemConsumeEvent event) { wakeAfter(event.getPlayer()); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onAvailable(PlayerRespawnEvent event) { wakeAfter(event.getPlayer()); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        wakeWorld(event.getFrom().getUID()); wakeAfter(event.getPlayer());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onMode(PlayerGameModeChangeEvent event) { wakeAfter(event.getPlayer()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onMoved(PlayerMoveEvent event) {
        Location to=event.getTo(),from=event.getFrom();
        if(to!=null && (to.getBlockX()!=from.getBlockX() || to.getBlockY()!=from.getBlockY() || to.getBlockZ()!=from.getBlockZ())) wakeAfter(event.getPlayer());
    }

    /** Payment proof is checked before this atomic ungate of the entire issuance group. */
    private boolean releaseDebit(UUID debit) {
        Map<UUID,Map<String,Object>> before=new LinkedHashMap<>();
        UUID owner=null;
        for(DeliveryRecord record:records.values()) if(debit.equals(record.debitReceipt)) {
            before.put(record.id,record.serialize()); owner=record.debitOwner;
            record.debitOwner=null; record.debitReceipt=null;
            plugin.getDataManager().stageProtectedDelivery(record.id,record.serialize());
        }
        if(before.isEmpty()) return true;
        if(!plugin.getDataManager().saveSafely()) {
            before.forEach((id,snapshot)->{
                DeliveryRecord live=records.get(id);
                // Keep object identity: tick/placement callbacks may still refer to this live record.
                live.debitOwner=UUID.fromString((String)snapshot.get("debit-owner"));
                live.debitReceipt=UUID.fromString((String)snapshot.get("debit-receipt"));
                plugin.getDataManager().stageProtectedDelivery(id,snapshot);
            });
            return false;
        }
        for (UUID id : before.keySet()) scheduleRecord(records.get(id));
        Player player=owner==null ? null : Bukkit.getPlayer(owner);
        if(player!=null && player.isOnline()) player.getPersistentDataContainer().remove(debitReceipt(debit));
        return true;
    }

    private void confirmRollback(Player player, UUID debit, boolean force) {
        if(player==null || !player.isOnline() || (!force && retryAfter.getOrDefault(debit,0L)>System.currentTimeMillis())) return;
        try {
            player.getPersistentDataContainer().remove(debitReceipt(debit));
            GuiEscrow.savePlayerData(player);
            pendingRollbacks.remove(debit); retryAfter.remove(debit);
        } catch(RuntimeException error) { retryAfter.put(debit,System.currentTimeMillis()+5_000L); }
    }
    private void sealDebit(Player player, UUID debit) {
        if(player==null || !player.isOnline() || retryAfter.getOrDefault(debit,0L)>System.currentTimeMillis()) return;
        try {
            player.getPersistentDataContainer().set(debitReceipt(debit),PersistentDataType.BYTE,(byte)1);
            GuiEscrow.savePlayerData(player);
            if(!releaseDebit(debit)) { retryAfter.put(debit,System.currentTimeMillis()+5_000L); return; }
            pendingDebits.remove(debit); placementEvents.remove(debit); retryAfter.remove(debit); creativeDebits.remove(debit); creativePlacements.remove(debit);
        } catch(RuntimeException error) {
            retryAfter.put(debit,System.currentTimeMillis()+5_000L);
            // Keep the queue gated; retry the same receipt rather than deciding from an unsaved inventory.
            plugin.getLogger().warning("Placed head debit remains pending: "+error.getMessage());
        }
    }
    private NamespacedKey receipt(UUID id) { return new NamespacedKey(plugin,"protected_received_"+id.toString().replace('-','_')); }
    private NamespacedKey debitReceipt(UUID id) { return new NamespacedKey(plugin,"protected_debit_"+id.toString().replace('-','_')); }
    private boolean loaded(Location location) { return location.getWorld().isChunkLoaded(location.getBlockX()>>4,location.getBlockZ()>>4); }
    private UUID readId(String value) { try { return value==null ? null : UUID.fromString(value); } catch(IllegalArgumentException ignored) { return null; } }
    private UUID itemId(ItemStack item) {
        return item==null || !item.hasItemMeta() ? null : readId(item.getItemMeta().getPersistentDataContainer().get(deliveryKey,PersistentDataType.STRING));
    }
    private void markDelivery(ItemStack item,UUID id) {
        var meta=item.getItemMeta(); if(meta!=null) { meta.getPersistentDataContainer().set(deliveryKey,PersistentDataType.STRING,id.toString()); item.setItemMeta(meta); }
    }
    private void stripDelivery(ItemStack item) {
        var meta=item.getItemMeta(); if(meta!=null) { meta.getPersistentDataContainer().remove(deliveryKey); item.setItemMeta(meta); }
    }
}

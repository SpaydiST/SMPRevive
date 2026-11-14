package macronux.smprevive;

import macronux.smprevive.rules.ReviveRules;
import macronux.smprevive.data.AtomicStateFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;

/** Loads and persists player/altar state while preserving the legacy void integration methods. */
public class DataManager {
    /** Long enough to outlast any realistic world-save rollback, short enough to bound players.yml. */
    private static final long MARKER_RETENTION_MILLIS = 60L * 24 * 60 * 60 * 1000;
    private static final List<String> MARKER_ROOTS = List.of(
        "completed-operations", "retired-head-frames", "retired-head-blocks", "retired-protected-drops");
    private final SMPRevive plugin;
    private final File playersFile;
    private final YamlConfiguration players;
    private boolean loadFailed;
    private AtomicStateFile stateFile;
    private long revision;
    private long serializedRevision;
    private String serializedPlayers;
    // Coordinates are indexed without retaining World objects or loading chunks.
    private final Map<String, AltarPosition> altarPositions = new LinkedHashMap<>();
    private final List<AltarPosition> orderedAltars = new ArrayList<>();
    private final Map<AltarChunk, List<AltarPosition>> altarChunks = new HashMap<>();
    private final Set<String> unbreakableAltarPositions = new HashSet<>();
    private Object indexedAltarSource;
    private int indexedAltarSize;
    private record AltarPosition(String world, int x, int y, int z) {}
    private record AltarChunk(String world, int x, int z) {}

    public DataManager(SMPRevive plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        this.playersFile = new File(plugin.getDataFolder(), "players.yml");
        if (!this.playersFile.exists()) {
            try {
                this.playersFile.createNewFile();
            } catch (IOException e) {
                this.plugin.getLogger().warning("Failed to create players.yml: " + e.getMessage());
            }
        }
        this.players = new YamlConfiguration();
        try {
            this.players.load(this.playersFile);
            this.serializedPlayers = this.players.saveToString();
            this.stateFile = new AtomicStateFile(this.playersFile.toPath(), this.serializedPlayers);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            this.loadFailed = true;
            this.plugin.getLogger().warning("Failed to load players.yml; refusing to overwrite it: " + e.getMessage());
        }
        if (!this.loadFailed) {
            long before = this.revision;
            pruneExpiredMarkers();
            if (this.revision != before) saveSafely();
        }
        reloadAltarIndex();
    }

    public boolean isLoadFailed() {
        return this.loadFailed;
    }

    private void put(String path, Object value) {
        if (Objects.equals(players.get(path), value)) return;
        players.set(path, value);
        revision++;
    }

    private String path(UUID uuid) {
        return "players." + uuid.toString();
    }

    public int getHearts(UUID uuid) {
        if (uuid == null) {
            return ReviveRules.clampPlayerHearts(this.plugin.getStartHearts(), this.plugin.getMaxHearts());
        }
        int configured = this.players.getInt(this.path(uuid) + ".hearts", this.plugin.getStartHearts());
        int clamped = ReviveRules.clampPlayerHearts(configured, this.plugin.getMaxHearts());
        if (configured != clamped && uuid != null) {
            this.put(this.path(uuid) + ".hearts", clamped);
        }
        return clamped;
    }

    public void setHearts(UUID uuid, int hearts) {
        if (uuid == null) return;
        this.put(
            this.path(uuid) + ".hearts",
            ReviveRules.clampPlayerHearts(hearts, this.plugin.getMaxHearts())
        );
    }

    public String getTokenMaterial(UUID uuid) {
        if (uuid == null) return null;
        String configured = this.players.getString(this.path(uuid) + ".token-material");
        if (configured == null) return null;
        Material material = Material.matchMaterial(configured.trim());
        if (material == null || material == Material.AIR || !material.isItem()) {
            this.put(this.path(uuid) + ".token-material", null);
            return null;
        }
        String canonical = material.name();
        if (!canonical.equals(configured)) {
            this.put(this.path(uuid) + ".token-material", canonical);
        }
        return canonical;
    }

    public void setTokenMaterial(UUID uuid, String material) {
        if (uuid == null) return;
        Material matched = material == null ? null : Material.matchMaterial(material.trim());
        this.put(
            this.path(uuid) + ".token-material",
            matched == null || matched == Material.AIR || !matched.isItem() ? null : matched.name()
        );
    }

    public boolean hasToken(UUID uuid) {
        return this.getTokenMaterial(uuid) != null;
    }

    /** Persist a fallback before the optional casino UI can be closed or interrupted. */
    public boolean ensureToken(UUID uuid) {
        if (uuid == null) return false;
        if (hasToken(uuid)) return true;
        List<Material> pool = plugin.getCasinoPool();
        Material fallback = pool.isEmpty() ? Material.DIAMOND
            : pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
        setTokenMaterial(uuid, fallback.name());
        if (saveSafely()) return true;
        setTokenMaterial(uuid, null);
        return false;
    }

    public boolean hasPendingRevivalSpawn(UUID uuid) {
        return uuid != null && this.players.isString(this.path(uuid) + ".revival-spawn");
    }

    public boolean hasPlayerData(UUID uuid) {
        return uuid != null && this.players.contains(this.path(uuid));
    }

    public boolean isDead(UUID uuid) {
        if (uuid == null) return false;
        return this.players.getBoolean(this.path(uuid) + ".dead", false);
    }

    public void setDead(UUID uuid, boolean dead) {
        if (uuid == null) return;
        this.put(this.path(uuid) + ".dead", dead);
    }

    /** Idempotency markers are stored as creation timestamps so {@link #pruneExpiredMarkers()} can expire them. */
    private boolean isMarked(String path) {
        Object value = players.get(path);
        return value instanceof Number || Boolean.TRUE.equals(value);
    }

    private void mark(String path, boolean marked) {
        put(path, marked ? (Object) System.currentTimeMillis() : null);
    }

    /** Drops markers older than the retention window; legacy boolean markers start their window now. */
    private void pruneExpiredMarkers() {
        long now = System.currentTimeMillis();
        long cutoff = now - MARKER_RETENTION_MILLIS;
        for (String root : MARKER_ROOTS) {
            var section = players.getConfigurationSection(root);
            if (section == null) continue;
            for (String key : section.getKeys(false)) {
                Object value = section.get(key);
                if (Boolean.TRUE.equals(value)) put(root + "." + key, now);
                else if (value instanceof Number stamp && stamp.longValue() < cutoff) put(root + "." + key, null);
            }
        }
    }

    public boolean hasCompletedOperation(UUID id) {
        return id != null && isMarked("completed-operations." + id);
    }

    public void setCompletedOperation(UUID id, boolean completed) {
        if (id != null) mark("completed-operations." + id, completed);
    }

    public java.util.Map<String, java.util.Map<String, Object>> getProtectedDeliveries() {
        var result = new java.util.LinkedHashMap<String, java.util.Map<String, Object>>();
        var section = players.getConfigurationSection("protected-deliveries");
        if (players.contains("protected-deliveries") && section == null)
            throw new IllegalStateException("Malformed protected-deliveries root");
        if (section != null) for (String key : section.getKeys(false)) {
            var record = section.getConfigurationSection(key);
            if (record != null) result.put(key, new java.util.LinkedHashMap<>(record.getValues(false)));
            else if (section.get(key) instanceof java.util.Map<?, ?> raw) {
                var map = new java.util.LinkedHashMap<String, Object>();
                raw.forEach((name, value) -> map.put(String.valueOf(name), value));
                result.put(key, map);
            } else throw new IllegalStateException("Malformed protected-deliveries entry: " + key);
        }
        return result;
    }

    public boolean isRetiredHeadFrame(UUID frameId) { return isMarked("retired-head-frames." + frameId); }
    public void setRetiredHeadFrame(UUID frameId, boolean retired) { mark("retired-head-frames." + frameId, retired); }

    /** Source coordinates survive a crash before a newly adopted skull's delivery stamp reaches the world save. */
    public boolean isRetiredHeadBlock(UUID world, int x, int y, int z) {
        return isMarked("retired-head-blocks." + world + "_" + x + "_" + y + "_" + z);
    }

    public void setRetiredHeadBlock(UUID world, int x, int y, int z, boolean retired) {
        mark("retired-head-blocks." + world + "_" + x + "_" + y + "_" + z, retired);
    }

    /** The native source UUID prevents a world-save rollback from duplicating a void recovery. */
    public boolean isRetiredProtectedDrop(UUID entityId) { return isMarked("retired-protected-drops." + entityId); }
    public void retireProtectedDrop(UUID entityId) { mark("retired-protected-drops." + entityId, true); }

    public void stageProtectedDelivery(UUID id, java.util.Map<String, Object> record) {
        put("protected-deliveries." + id, record == null ? null : new LinkedHashMap<>(record));
    }

    public Location getRevivalSpawnLocation(UUID uuid) {
        if (uuid == null) return null;
        String locStr = this.players.getString(this.path(uuid) + ".revival-spawn");
        if (locStr == null) return null;
        String[] parts = locStr.split(",", -1);
        if (parts.length != 4) return null;
        org.bukkit.World world = this.plugin.getServer().getWorld(parts[0]);
        if (world == null) return null;
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
            return new Location(world, x, y, z);
        } catch (NumberFormatException ex) {
            this.plugin.getLogger().warning("Ignoring malformed revival-spawn for " + uuid);
            return null;
        }
    }

    public void setRevivalSpawnLocation(UUID uuid, Location loc) {
        if (uuid == null) return;
        if (loc == null) {
            this.put(this.path(uuid) + ".revival-spawn", null);
        } else if (loc.getWorld() == null) {
            this.put(this.path(uuid) + ".revival-spawn", null);
        } else {
            this.put(this.path(uuid) + ".revival-spawn", loc.getWorld().getName() + "," + loc.getX() + "," + loc.getY() + "," + loc.getZ());
        }
    }

    public void clearRevivalSpawnLocation(UUID uuid) {
        if (uuid == null) return;
        this.put(this.path(uuid) + ".revival-spawn", null);
    }

    public boolean hasDied(UUID uuid) {
        if (uuid == null) return false;
        return this.players.getBoolean(this.path(uuid) + ".has-died", false);
    }

    public void setHasDied(UUID uuid, boolean value) {
        if (uuid == null) return;
        this.put(this.path(uuid) + ".has-died", value);
    }

    public int getPaidDeathCount(UUID uuid) {
        if (uuid == null) return 0;
        return Math.max(0, this.players.getInt(this.path(uuid) + ".paid-deaths", 0));
    }

    public void setPaidDeathCount(UUID uuid, int count) {
        if (uuid == null) return;
        this.put(this.path(uuid) + ".paid-deaths", Math.max(0, count));
    }

    public int getKillStreak(UUID uuid) {
        if (uuid == null) return 0;
        return Math.max(0, this.players.getInt(this.path(uuid) + ".kill-streak", 0));
    }

    public void setKillStreak(UUID uuid, int streak) {
        if (uuid == null) return;
        this.put(this.path(uuid) + ".kill-streak", Math.max(0, streak));
    }

    public boolean hasPendingRevivedAchievement(UUID uuid) {
        if (uuid == null) return false;
        return this.players.getBoolean(this.path(uuid) + ".pending-revived-achievement", false);
    }

    public void setPendingRevivedAchievement(UUID uuid, boolean value) {
        if (uuid == null) return;
        this.put(this.path(uuid) + ".pending-revived-achievement", value);
    }

    /** Legacy SpaydisCore entry point; callers keep its void return contract. */
    public void save() {
        this.saveSafely();
    }

    public boolean saveSafely() {
        if (this.loadFailed) {
            this.plugin.getLogger().warning("Skipped players.yml save because the file could not be loaded safely");
            return false;
        }
        long start = System.nanoTime();
        try {
            if (serializedRevision != revision) {
                serializedPlayers = players.saveToString();
                serializedRevision = revision;
                plugin.getPerformanceCounters().increment("players-serializations");
            }
            // Still check/rewrite a missing or externally changed file, even when the RAM state is unchanged.
            this.stateFile.save(serializedPlayers);
            return true;
        } catch (IOException | RuntimeException e) {
            this.plugin.getLogger().warning("Failed to save players.yml: " + e.getMessage());
            return false;
        } finally {
            this.plugin.getPerformanceCounters().record("players-save", start);
        }
    }

    public long getSaveCount() { return this.stateFile == null ? 0 : this.stateFile.writes(); }
    public long getSkippedSaveCount() { return this.stateFile == null ? 0 : this.stateFile.skipped(); }

    // Altar location methods
    public boolean isAltar(Location loc) {
        return this.isRegisteredAltar(loc)
            && loc.getWorld() != null
            && this.altarBlockPresent(loc);
    }

    /** Trusts the registry for unloaded chunks so a lookup never forces a synchronous chunk load. */
    private boolean altarBlockPresent(Location loc) {
        org.bukkit.World world = loc.getWorld();
        if (!world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return true;
        return this.plugin.isAltarBlockMaterial(loc.getBlock().getType());
    }

    public boolean isRegisteredAltar(Location loc) {
        ensureAltarIndex();
        return loc != null && loc.getWorld() != null && altarPositions.containsKey(serialize(loc));
    }

    public boolean isUnbreakableAltar(Location loc) {
        ensureAltarIndex();
        return loc != null && loc.getWorld() != null && unbreakableAltarPositions.contains(serialize(loc));
    }

    /** Legacy SpaydisCore entry point; use the safe result method inside this plugin. */
    public void addAltar(Location loc) {
        this.addAltarSafely(loc);
    }

    public boolean addAltarSafely(Location loc) {
        return addAltarSafely(loc, false);
    }

    public boolean addAltarSafely(Location loc, boolean unbreakable) {
        if (loc == null || loc.getWorld() == null) return false;
        List<String> altars = this.plugin.getConfig().getStringList("altars");
        List<String> unbreakableAltars = this.plugin.getConfig().getStringList("unbreakable-altars");
        String serialized = serialize(loc);
        if (altars.contains(serialized)) return true;
        List<String> previous = new ArrayList<>(altars);
        List<String> previousUnbreakable = new ArrayList<>(unbreakableAltars);
        altars.add(serialized);
        unbreakableAltars.remove(serialized);
        if (unbreakable) unbreakableAltars.add(serialized);
        this.plugin.getConfig().set("altars", altars);
        this.plugin.getConfig().set("unbreakable-altars", unbreakableAltars);
        if (this.plugin.saveConfigSafely()) { reloadAltarIndex(); return true; }
        try {
            this.plugin.getConfig().set("altars", previous);
            this.plugin.getConfig().set("unbreakable-altars", previousUnbreakable);
        } catch (RuntimeException ignored) {
            // Keep the in-memory registry unchanged when normalization also fails.
        }
        return false;
    }

    /** Legacy SpaydisCore entry point; use the safe result method inside this plugin. */
    public void removeAltar(Location loc) {
        this.removeAltarSafely(loc);
    }

    public boolean removeAltarSafely(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        List<String> altars = this.plugin.getConfig().getStringList("altars");
        List<String> unbreakableAltars = this.plugin.getConfig().getStringList("unbreakable-altars");
        String serialized = serialize(loc);
        if (!altars.contains(serialized) && !unbreakableAltars.contains(serialized)) return true;
        List<String> previous = new ArrayList<>(altars);
        List<String> previousUnbreakable = new ArrayList<>(unbreakableAltars);
        altars.remove(serialized);
        unbreakableAltars.remove(serialized);
        this.plugin.getConfig().set("altars", altars);
        this.plugin.getConfig().set("unbreakable-altars", unbreakableAltars);
        if (this.plugin.saveConfigSafely()) { reloadAltarIndex(); return true; }
        try {
            this.plugin.getConfig().set("altars", previous);
            this.plugin.getConfig().set("unbreakable-altars", previousUnbreakable);
        } catch (RuntimeException ignored) {
            // Keep the in-memory registry unchanged when normalization also fails.
        }
        return false;
    }

    private String serialize(Location loc) {
        return loc.getWorld() == null ? "" : loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    public List<Location> getAllAltars() {
        List<Location> result = new ArrayList<>();
        for (Location location : this.getRegisteredAltars()) {
            if (this.altarBlockPresent(location)) {
                result.add(location);
            }
        }
        return result;
    }

    /** Called after config reload and after a successfully persisted altar registry edit. */
    public void reloadAltarIndex() {
        indexedAltarSource = plugin.getConfig().get("altars");
        indexedAltarSize = indexedAltarSource instanceof List<?> list ? list.size() : 0;
        altarPositions.clear(); orderedAltars.clear(); altarChunks.clear(); unbreakableAltarPositions.clear();
        for (String serialized : plugin.getConfig().getStringList("altars")) {
            if (serialized == null) continue;
            String[] parts = serialized.split(",", -1);
            if (parts.length != 4) { plugin.getLogger().warning("Ignoring malformed altar location: " + serialized); continue; }
            try {
                AltarPosition position = new AltarPosition(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                altarPositions.put(serialized, position);
                orderedAltars.add(position); // Keep legacy enumeration order, including configured duplicates.
                altarChunks.computeIfAbsent(new AltarChunk(position.world, position.x >> 4, position.z >> 4), ignored -> new ArrayList<>()).add(position);
            } catch (NumberFormatException error) { plugin.getLogger().warning("Ignoring malformed altar location: " + serialized); }
        }
        unbreakableAltarPositions.addAll(plugin.getConfig().getStringList("unbreakable-altars"));
        unbreakableAltarPositions.retainAll(altarPositions.keySet());
    }

    private void ensureAltarIndex() {
        Object source = plugin.getConfig().get("altars");
        int size = source instanceof List<?> list ? list.size() : 0;
        if (source != indexedAltarSource || size != indexedAltarSize) reloadAltarIndex();
    }

    public List<Location> getRegisteredAltars() { ensureAltarIndex(); return resolveAltars(orderedAltars); }

    public List<Location> getRegisteredAltarsInChunk(org.bukkit.Chunk chunk) {
        ensureAltarIndex();
        if (chunk == null) return List.of();
        return resolveAltars(altarChunks.getOrDefault(new AltarChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()), List.of()));
    }

    private List<Location> resolveAltars(java.util.Collection<AltarPosition> positions) {
        List<Location> result = new ArrayList<>(positions.size());
        for (AltarPosition position : positions) {
            org.bukkit.World world = plugin.getServer().getWorld(position.world);
            if (world != null && position.y >= world.getMinHeight() && position.y < world.getMaxHeight())
                result.add(new Location(world, position.x, position.y, position.z));
        }
        return result;
    }

    public Location findNearestAltar(Location from) {
        if (from == null || from.getWorld() == null) return null;
        List<Location> altars = getAllAltars();
        if (altars.isEmpty()) return null;
        Location nearest = null;
        double minDistSq = Double.MAX_VALUE;
        for (Location altar : altars) {
            if (!altar.getWorld().equals(from.getWorld())) continue;
            double distSq = altar.distanceSquared(from);
            if (distSq < minDistSq) {
                minDistSq = distSq;
                nearest = altar;
            }
        }
        return nearest;
    }
}

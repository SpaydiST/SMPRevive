package macronux.smprevive.event;

import macronux.smprevive.SMPRevive;
import macronux.smprevive.rules.ReviveRules;

import java.io.File;
import java.util.concurrent.ThreadLocalRandom;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class EventSpawnLocator {
    private static final int BORDER_MARGIN = 50;
    private static final int SEARCH_ATTEMPTS = 10;
    private static final int SAFE_SEARCH_RADIUS = 20;

    private final SMPRevive plugin;
    private final Set<ChunkTicket> heldTickets = new HashSet<>();
    public EventSpawnLocator(SMPRevive plugin) {
        this.plugin = plugin;
    }

    public Location findSafeAt(World world, int x, int z) {
        return world == null ? null : safeLocation(world, x, z);
    }

    public Location find(World world) {
        if (world == null) return null;

        Location delegated = findRandomSpawnPlus(world);
        if (delegated != null) return delegated;

        int spawnRadius = configuredRadius(world);
        WorldBorder border = world.getWorldBorder();
        double half = border.getSize() / 2.0 - BORDER_MARGIN;
        if (half <= 0.0) return null;

        for (int attempt = 0; attempt < SEARCH_ATTEMPTS; attempt++) {
            Location candidate = randomCandidate(world, spawnRadius, border, half);
            if (candidate == null) continue;
            Location safe = findSafeSpot(world, candidate.getBlockX(), candidate.getBlockZ());
            if (safe != null) return safe;
        }
        return null;
    }

    public Location findRandomSpawnPlus(World world) {
        if (world == null) return null;
        Plugin randomSpawn = Bukkit.getPluginManager().getPlugin("RandomSpawnPlus");
        if (randomSpawn == null || !randomSpawn.isEnabled()) return null;

        int radius = configuredRadius(world);
        WorldBorder border = world.getWorldBorder();
        double half = border.getSize() / 2.0 - BORDER_MARGIN;
        if (half <= 0.0) return null;
        for (int attempt = 0; attempt < SEARCH_ATTEMPTS; attempt++) {
            Location candidate = randomCandidate(world, radius, border, half);
            if (candidate == null) continue;
            Location safe = findSafeSpot(world, candidate.getBlockX(), candidate.getBlockZ());
            if (safe != null) return safe;
        }
        return null;
    }

    /** Finds a location without doing generation or column inspection in the event tick. */
    public SearchHandle findAsync(World world, Consumer<Location> callback) {
        SearchHandle search = new SearchHandle(world, callback);
        search.task = Bukkit.getScheduler().runTaskTimer(plugin, search::tickSafely, 1L, 1L);
        return search;
    }

    public void holdLocation(Location location) {
        if (location == null || location.getWorld() == null) return;
        holdChunk(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public void releaseTickets() {
        for (ChunkTicket ticket : heldTickets) {
            ticket.world.removePluginChunkTicket(ticket.x, ticket.z, plugin);
        }
        heldTickets.clear();
    }

    public final class SearchHandle {
        private final World world;
        private final Consumer<Location> callback;
        private final int spawnRadius;
        private final Queue<int[]> columns = new ArrayDeque<>();
        private int attempts;
        private int ageTicks;
        private boolean waitingForChunk;
        private boolean waitingForLandingArea;
        private int pendingLandingChunks;
        private Location pendingResult;
        private volatile boolean cancelled;
        private BukkitTask task;

        private SearchHandle(World world, Consumer<Location> callback) {
            this.world = world;
            this.callback = callback;
            this.spawnRadius = configuredRadius(world);
        }

        public void cancel() {
            cancelled = true;
            if (task != null) task.cancel();
        }

        private void tickSafely() {
            try {
                tick();
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Event spawn search failed: " + exception.getMessage());
                finish(null);
            }
        }

        private void tick() {
            if (cancelled) return;
            if (++ageTicks > EventRules.BURST_PREPARE_TIMEOUT_TICKS) {
                finish(null);
                return;
            }
            if (waitingForChunk || waitingForLandingArea) return;
            int checks = 24;
            while (checks-- > 0 && !cancelled) {
                if (columns.isEmpty()) {
                    if (attempts++ >= SEARCH_ATTEMPTS) {
                        finish(null);
                        return;
                    }
                    double half = world.getWorldBorder().getSize() / 2.0 - BORDER_MARGIN;
                    if (half <= 0.0) { finish(null); return; }
                    Location candidate = randomCandidate(world, spawnRadius, world.getWorldBorder(), half);
                    if (candidate != null) enqueue(candidate.getBlockX(), candidate.getBlockZ());
                    continue;
                }
                int[] column = columns.peek();
                int chunkX = column[0] >> 4;
                int chunkZ = column[1] >> 4;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    waitingForChunk = true;
                    requestChunk(chunkX, chunkZ, () -> waitingForChunk = false);
                    return;
                }
                columns.remove();
                Location safe = safeLocation(world, column[0], column[1]);
                if (safe != null) {
                    retainLandingArea(safe);
                    return;
                }
            }
        }

        private void enqueue(int startX, int startZ) {
            for (int radius = 0; radius <= SAFE_SEARCH_RADIUS; radius++) {
                int minX = startX - radius;
                int maxX = startX + radius;
                int minZ = startZ - radius;
                int maxZ = startZ + radius;
                for (int x = minX; x <= maxX; x++) {
                    columns.add(new int[] {x, minZ});
                    columns.add(new int[] {x, maxZ});
                }
                for (int z = minZ + 1; z < maxZ; z++) {
                    columns.add(new int[] {minX, z});
                    columns.add(new int[] {maxX, z});
                }
            }
        }

        private void finish(Location result) {
            if (cancelled) return;
            cancelled = true;
            if (task != null) task.cancel();
            try {
                callback.accept(result);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Event spawn callback failed: " + exception.getMessage());
            }
        }

        private void retainLandingArea(Location result) {
            if (cancelled) return;
            pendingResult = result;
            waitingForLandingArea = true;
            int chunkX = result.getBlockX() >> 4;
            int chunkZ = result.getBlockZ() >> 4;
            pendingLandingChunks = 9;
            for (int x = chunkX - 1; x <= chunkX + 1; x++) {
                for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                    if (cancelled) return;
                    requestChunk(x, z, () -> {
                        if (waitingForLandingArea && --pendingLandingChunks == 0) finish(pendingResult);
                    });
                }
            }
        }

        private void requestChunk(int x, int z, Runnable loaded) {
            try {
                world.getChunkAtAsync(x, z, true).whenComplete((chunk, failure) -> {
                    if (cancelled || !plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (cancelled || !plugin.isEnabled()) return;
                        if (failure != null) {
                            finish(null);
                        } else {
                            try {
                                // Add a ticket only AFTER asynchronous generation has completed.
                                holdChunk(world, x, z);
                                loaded.run();
                            } catch (RuntimeException exception) {
                                finish(null);
                            }
                        }
                    });
                });
            } catch (RuntimeException exception) {
                finish(null);
            }
        }
    }

    private void holdChunk(World world, int x, int z) {
        ChunkTicket ticket = new ChunkTicket(world, x, z);
        if (!heldTickets.contains(ticket)) {
            world.addPluginChunkTicket(x, z, plugin);
            heldTickets.add(ticket);
        }
    }

    private record ChunkTicket(World world, int x, int z) {
    }

    private YamlConfiguration cachedZones;
    private File cachedZonesFile;
    private long cachedZonesStamp;

    private int configuredRadius(World world) {
        int fallback = Math.max(0, plugin.getConfig().getInt("event.spawn-radius", 0));
        Plugin randomSpawn = Bukkit.getPluginManager().getPlugin("RandomSpawnPlus");
        if (randomSpawn == null || !randomSpawn.isEnabled()) return fallback;

        File zonesFile = new File(randomSpawn.getDataFolder(), "spawnzones.yml");
        if (!zonesFile.isFile()) return fallback;
        // One parse per file version instead of one per participant on the main thread.
        long stamp = zonesFile.lastModified() ^ (zonesFile.length() << 1);
        if (cachedZones == null || !zonesFile.equals(cachedZonesFile) || stamp != cachedZonesStamp) {
            cachedZones = YamlConfiguration.loadConfiguration(zonesFile);
            cachedZonesFile = zonesFile;
            cachedZonesStamp = stamp;
        }
        YamlConfiguration zones = cachedZones;
        String path = "worlds." + world.getName();
        return zones.contains(path) ? Math.max(0, zones.getInt(path, fallback)) : fallback;
    }

    private Location randomCandidate(World world, int spawnRadius, WorldBorder border, double half) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double x;
        double z;
        Location borderCenter = border.getCenter();

        if (spawnRadius > 0) {
            Location spawn = world.getSpawnLocation();
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            double distance = spawnRadius * Math.sqrt(random.nextDouble());
            x = spawn.getX() + distance * Math.cos(angle);
            z = spawn.getZ() + distance * Math.sin(angle);
            boolean insideUsableBorder = x >= borderCenter.getX() - half
                && x <= borderCenter.getX() + half
                && z >= borderCenter.getZ() - half
                && z <= borderCenter.getZ() + half;
            if (!insideUsableBorder) return null; // border margin
        } else {
            x = borderCenter.getX() + random.nextDouble(-half, half);
            z = borderCenter.getZ() + random.nextDouble(-half, half);
        }

        Location candidate = new Location(world, x, 0.0, z);
        return border.isInside(candidate) ? candidate : null;
    }

    private Location findSafeSpot(World world, int startX, int startZ) {
        for (int radius = 0; radius <= SAFE_SEARCH_RADIUS; radius++) {
            int minX = startX - radius;
            int maxX = startX + radius;
            int minZ = startZ - radius;
            int maxZ = startZ + radius;

            for (int x = minX; x <= maxX; x++) {
                Location safe = safeLocation(world, x, minZ);
                if (safe != null) return safe;
                safe = safeLocation(world, x, maxZ);
                if (safe != null) return safe;
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                Location safe = safeLocation(world, minX, z);
                if (safe != null) return safe;
                safe = safeLocation(world, maxX, z);
                if (safe != null) return safe;
            }
        }
        return null;
    }

    private Location safeLocation(World world, int x, int z) {
        if (!isWithinUsableBorder(world.getWorldBorder(), x + 0.5, z + 0.5)) return null;
        int groundY = world.getHighestBlockYAt(x, z);
        if (!isSafeSpawn(world, x, groundY, z)) return null;
        return new Location(world, x + 0.5, groundY + 1.0, z + 0.5);
    }

    private boolean isWithinUsableBorder(WorldBorder border, double x, double z) {
        Location center = border.getCenter();
        double half = border.getSize() / 2.0 - BORDER_MARGIN;
        return half > 0.0
            && x >= center.getX() - half
            && x <= center.getX() + half
            && z >= center.getZ() - half
            && z <= center.getZ() + half;
    }

    private boolean isSafeSpawn(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) return false;

        Block ground = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);
        Material groundType = ground.getType();
        Location candidate = new Location(world, x + 0.5, y + 1.0, z + 0.5);

        return world.getWorldBorder().isInside(candidate)
            && groundType.isSolid()
            && !ground.isLiquid()
            && !Tag.LEAVES.isTagged(groundType)
            && !ReviveRules.isDangerousSpawnMaterial(groundType.name())
            && feet.isPassable()
            && head.isPassable()
            && !feet.isLiquid()
            && !head.isLiquid()
            && !Tag.LEAVES.isTagged(feet.getType())
            && !Tag.LEAVES.isTagged(head.getType())
            && !ReviveRules.isDangerousSpawnMaterial(feet.getType().name())
            && !ReviveRules.isDangerousSpawnMaterial(head.getType().name());
    }
}

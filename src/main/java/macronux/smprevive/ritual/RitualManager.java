package macronux.smprevive.ritual;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import macronux.smprevive.DataManager;
import macronux.smprevive.SMPRevive;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zoglin;

import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Runs skull rituals and looks after the finished shrines: the floating wither skeleton skull, its slow spin,
 * falling hellish particles, sounds, rare mobs and Nether ambience around it. Shrines live in shrines.yml, so
 * the head comes back after every restart.
 */
public final class RitualManager {
    private static final double AMBIENCE_RANGE = 20.0;
    private static final double ACTIVE_RANGE = 96.0;
    private static final String TIME_SPEED_PLUGIN = "TimeSpeed-plugin";
    private static final Sound[] AMBIENT = {
        Sound.AMBIENT_NETHER_WASTES_MOOD, Sound.AMBIENT_NETHER_WASTES_ADDITIONS,
        Sound.AMBIENT_CRIMSON_FOREST_MOOD, Sound.AMBIENT_CRIMSON_FOREST_ADDITIONS,
        Sound.AMBIENT_WARPED_FOREST_MOOD, Sound.AMBIENT_WARPED_FOREST_ADDITIONS,
        Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, Sound.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS,
        Sound.AMBIENT_BASALT_DELTAS_MOOD, Sound.AMBIENT_BASALT_DELTAS_ADDITIONS
    };
    private static final Sound[] SKULL_SOUNDS = {
        Sound.ENTITY_WITHER_AMBIENT, Sound.ENTITY_WITHER_SKELETON_AMBIENT, Sound.ENTITY_GHAST_AMBIENT,
        Sound.ENTITY_ZOGLIN_AMBIENT, Sound.ENTITY_PIGLIN_BRUTE_AMBIENT, Sound.PARTICLE_SOUL_ESCAPE,
        Sound.BLOCK_LAVA_AMBIENT
    };

    private static final class ShrineView {
        UUID display;
        float angle;
    }

    private static final class WeatherState {
        final boolean storm;
        final boolean thunder;
        int holders;

        WeatherState(boolean storm, boolean thunder) {
            this.storm = storm;
            this.thunder = thunder;
        }
    }

    /** The "put the skull here" marker of /event skull: a bobbing translucent skull from the resource pack. */
    private static final class Hint {
        final World world;
        final Vector position;
        ItemDisplay display;
        long age;

        Hint(World world, Vector position) {
            this.world = world;
            this.position = position;
        }
    }

    private static final class TimeState {
        double speed = 1.0;
        double carry;
    }

    private final SMPRevive plugin;
    private final RitualStore store;
    private final Map<String, SkullRitual> rituals = new HashMap<>();
    private final Map<String, ShrineView> views = new HashMap<>();
    private final Map<UUID, Long> ambientDue = new HashMap<>();
    private final Map<UUID, WeatherState> weather = new HashMap<>();
    private final Map<UUID, TimeState> clocks = new HashMap<>();
    private final Map<UUID, Boolean> timeRequests = new HashMap<>();
    private java.lang.reflect.Method timeSpeedProbe;
    private boolean timeSpeedProbed;
    private Hint hint;
    private BukkitTask task;
    private long tick;

    public RitualManager(SMPRevive plugin) {
        this.plugin = plugin;
        this.store = new RitualStore(plugin.getDataFolder(), plugin.getLogger());
    }

    public void start() {
        if (store.isLoadFailed()) return;
        recoverInterrupted();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        // The records of unfinished rituals stay, so the next start returns the skull and the heart.
        for (SkullRitual ritual : new ArrayList<>(rituals.values())) ritual.cleanup();
        rituals.clear();
        for (ShrineView view : views.values()) removeEntity(view.display);
        views.clear();
        clearHint();
        clocks.clear();
        timeRequests.clear();
    }

    /** Starts a ritual at an altar. Sends the reason and returns false when it cannot start. */
    public boolean begin(Player starter, Location altarBlock) {
        if (store.isLoadFailed() || task == null) {
            starter.sendMessage(plugin.msg(starter, "ritual.unavailable"));
            return false;
        }
        // Rituals never stack: one at a time, and none while a shrine from an earlier one still stands.
        if (!rituals.isEmpty() || !store.actives().isEmpty()) {
            starter.sendMessage(plugin.msg(starter, "ritual.busy"));
            return false;
        }
        if (!store.shrines().isEmpty()) {
            starter.sendMessage(plugin.msg(starter, "ritual.exists"));
            return false;
        }
        Location centre = altarBlock.clone().add(0.5, 1.3 + RitualRules.RISE_HEIGHT, 0.5);
        if (pickDonor(centre) == null) {
            starter.sendMessage(plugin.msg(starter, "ritual.no-donor"));
            return false;
        }
        String id = UUID.randomUUID().toString();
        RitualStore.Active active = new RitualStore.Active(id, altarBlock.getWorld().getUID(),
            altarBlock.getBlockX(), altarBlock.getBlockY(), altarBlock.getBlockZ(), null);
        if (!store.addActive(active)) {
            starter.sendMessage(plugin.msg(starter, "ritual.unavailable"));
            return false;
        }
        if (clearHint()) plugin.getEventManager().stopGuide();
        rituals.put(id, new SkullRitual(this, plugin, id, altarBlock));
        return true;
    }

    /** Drops a ritual that has just begun without giving anything back; the caller keeps the skull. */
    public void cancel(Location altarBlock) {
        for (SkullRitual ritual : new ArrayList<>(rituals.values())) {
            if (!sameBlock(ritual.altar(), altarBlock)) continue;
            ritual.cleanup();
            rituals.remove(ritual.id());
            store.removeActive(ritual.id());
        }
    }

    /** Shows the skull marker at a point until a ritual starts or {@link #clearHint()} is called. */
    public void showHint(Location at) {
        clearHint();
        hint = new Hint(at.getWorld(), at.toVector());
    }

    /** Returns whether a marker was showing. */
    public boolean clearHint() {
        if (hint == null) return false;
        if (hint.display != null && hint.display.isValid()) hint.display.remove();
        hint = null;
        return true;
    }

    /** Cancels every running ritual and gives back its skull and heart; the altar is free again. */
    public boolean cancelRunning() {
        boolean any = false;
        for (SkullRitual ritual : new ArrayList<>(rituals.values())) {
            ritual.cleanup();
            abortRitual(ritual);
            rituals.remove(ritual.id());
            any = true;
        }
        return any;
    }

    public List<RitualStore.Shrine> shrines() {
        return store.shrines();
    }

    public RitualStore.Shrine nearestShrine(Location from, double radius) {
        RitualStore.Shrine best = null;
        double bestDistance = radius * radius;
        for (RitualStore.Shrine shrine : store.shrines()) {
            World world = Bukkit.getWorld(shrine.world());
            if (world == null || !world.equals(from.getWorld())) continue;
            double distance = from.distanceSquared(new Location(world, shrine.x(), shrine.y(), shrine.z()));
            if (distance <= bestDistance) {
                best = shrine;
                bestDistance = distance;
            }
        }
        return best;
    }

    public boolean removeShrine(RitualStore.Shrine shrine) {
        if (!store.removeShrine(shrine.id())) return false;
        ShrineView view = views.remove(shrine.id());
        if (view != null) removeEntity(view.display);
        return true;
    }

    // ---- used by SkullRitual ----

    /** The nearest living player in the world who still has more than the minimum number of hearts. */
    Player pickDonor(Location centre) {
        double range = plugin.getConfig().getDouble("ritual.donor-radius", 64.0);
        DataManager data = plugin.getDataManager();
        return centre.getWorld().getPlayers().stream()
            .filter(p -> p.isOnline() && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR)
            .filter(p -> !plugin.isMechanicsDisabled(p.getWorld()))
            .filter(p -> !data.isDead(p.getUniqueId()) && data.getHearts(p.getUniqueId()) > plugin.getMinHearts())
            .filter(p -> p.getLocation().distanceSquared(centre) <= range * range)
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(centre)))
            .orElse(null);
    }

    /** Records the donor first, then takes the heart, so a crash in between can only give a heart back. */
    boolean takeHeart(String ritualId, Player donor) {
        DataManager data = plugin.getDataManager();
        UUID uuid = donor.getUniqueId();
        int hearts = data.getHearts(uuid);
        if (hearts <= plugin.getMinHearts() || !store.setDonor(ritualId, uuid)) return false;
        data.setHearts(uuid, hearts - 1);
        if (!data.saveSafely()) {
            data.setHearts(uuid, hearts);
            store.setDonor(ritualId, null);
            return false;
        }
        donor.setMaxHealth(plugin.maxHealthForHearts(hearts - 1));
        return true;
    }

    /** Persists the finished shrine and brings the wither skeleton skull and its first mobs to life. */
    boolean completeRitual(SkullRitual ritual, Vector top) {
        World world = ritual.altar().getWorld();
        RitualStore.Shrine shrine = new RitualStore.Shrine(UUID.randomUUID().toString(), world.getUID(),
            top.getX(), top.getY(), top.getZ());
        if (!store.complete(ritual.id(), shrine)) return false;
        ShrineView view = new ShrineView();
        views.put(shrine.id(), view);
        ensureDisplay(world, shrine, view);
        if (plugin.getConfig().getBoolean("ritual.mobs", true)) {
            spawnMobs(world, new Vector(shrine.x(), shrine.y(), shrine.z()), 2 + ThreadLocalRandom.current().nextInt(2));
        }
        return true;
    }

    /** Gives the skull back and returns the heart that was taken, if any. */
    void abortRitual(SkullRitual ritual) {
        RitualStore.Active active = store.active(ritual.id());
        if (active == null) return;
        if (active.donor() != null) giveBackHeart(active.donor());
        Location altar = ritual.altar();
        World world = altar.getWorld();
        world.dropItemNaturally(altar.clone().add(0.5, 1.2, 0.5), new ItemStack(Material.SKELETON_SKULL));
        store.removeActive(ritual.id());
    }

    /** A running ritual asks for the world's time to run faster; the storm asks for the maximum. */
    void requestTime(World world, boolean storm) {
        if (!plugin.getConfig().getBoolean("ritual.time-acceleration", true)) return;
        timeRequests.merge(world.getUID(), storm, (a, b) -> a || b);
    }

    void acquireStorm(World world) {
        WeatherState state = weather.computeIfAbsent(world.getUID(), k -> new WeatherState(world.hasStorm(), world.isThundering()));
        state.holders++;
        world.setStorm(true);
        world.setThundering(true);
        world.setWeatherDuration(1200);
        world.setThunderDuration(1200);
    }

    void releaseStorm(World world) {
        WeatherState state = weather.get(world.getUID());
        if (state == null || --state.holders > 0) return;
        weather.remove(world.getUID());
        world.setStorm(state.storm);
        world.setThundering(state.thunder);
    }

    // ---- internals ----

    private void tick() {
        tick++;
        tickHint();
        for (SkullRitual ritual : new ArrayList<>(rituals.values())) {
            boolean done;
            try {
                done = ritual.tick();
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Skull ritual failed, returning the skull: " + exception);
                ritual.cleanup();
                abortRitual(ritual);
                done = true;
            }
            if (done) rituals.remove(ritual.id());
        }
        applyTime();
        if (tick % 20 == 0) tickShrines();
    }

    /**
     * Speeds the clock up the way TimeSpeed does: a fractional carry and a smoothed speed, then the clock is moved
     * ahead. Normal speed during the ritual, the maximum while the storm rages. While TimeSpeed itself is
     * rewinding or fast-forwarding a night in that world, it is left alone so the two never push the clock together.
     */
    private void applyTime() {
        double base = Math.max(1.0, plugin.getConfig().getDouble("ritual.time-speed", 4.0));
        double storm = Math.max(base, plugin.getConfig().getDouble("ritual.storm-time-speed", 300.0));
        List<UUID> worlds = new ArrayList<>(clocks.keySet());
        for (UUID id : timeRequests.keySet()) if (!worlds.contains(id)) worlds.add(id);
        for (UUID id : worlds) {
            World world = Bukkit.getWorld(id);
            Boolean request = timeRequests.get(id);
            TimeState state = clocks.computeIfAbsent(id, k -> new TimeState());
            double target = request == null ? 1.0 : (request ? storm : base);
            state.speed += (target - state.speed) * 0.15;
            if (Math.abs(target - state.speed) < 0.01) state.speed = target;
            if (world == null || (request == null && state.speed <= 1.0)) {
                clocks.remove(id);
                continue;
            }
            if (state.speed <= 1.0 || timeSpeedBusy(world)) continue;
            state.carry += state.speed - 1.0;
            int ticks = (int) state.carry;
            state.carry -= ticks;
            if (ticks > 0) world.setFullTime(world.getFullTime() + ticks);
        }
        timeRequests.clear();
    }

    /** True while the TimeSpeed plugin reports its own acceleration (sleep or /timespeed) for this world. */
    private boolean timeSpeedBusy(World world) {
        if (!timeSpeedProbed) {
            timeSpeedProbed = true;
            try {
                var plugin = Bukkit.getPluginManager().getPlugin(TIME_SPEED_PLUGIN);
                if (plugin != null) timeSpeedProbe = plugin.getClass().getMethod("getWorldTimeSpeed", World.class);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                this.plugin.getLogger().info("TimeSpeed integration unavailable: " + exception.getMessage());
            }
        }
        if (timeSpeedProbe == null) return false;
        try {
            var timeSpeed = Bukkit.getPluginManager().getPlugin(TIME_SPEED_PLUGIN);
            return timeSpeed != null && ((Number) timeSpeedProbe.invoke(timeSpeed, world)).doubleValue() > 0.0;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    /** The marker bobs up and down, turns slowly; its model is translucent in the resource pack. */
    private void tickHint() {
        Hint current = hint;
        if (current == null) return;
        World world = current.world;
        if (Bukkit.getWorld(world.getUID()) == null) {
            hint = null;
            return;
        }
        if (!world.isChunkLoaded((int) Math.floor(current.position.getX()) >> 4,
            (int) Math.floor(current.position.getZ()) >> 4)) return;
        current.age++;
        double bob = Math.sin(current.age * 0.08) * 0.35;
        Location at = new Location(world, current.position.getX(), current.position.getY() + bob,
            current.position.getZ(), (float) (current.age * 2.0 % 360.0), 0f);
        if (current.display == null || !current.display.isValid()) {
            current.display = world.spawn(at, ItemDisplay.class, spawned -> {
                ItemStack skull = new ItemStack(Material.SKELETON_SKULL);
                skull.setData(DataComponentTypes.ITEM_MODEL, Key.key("smprevive", "ghost_skull"));
                spawned.setItemStack(skull);
                spawned.setPersistent(false);
                spawned.setTeleportDuration(2);
                spawned.setViewRange(2.0f);
                spawned.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(RitualRules.SKULL_SCALE), new AxisAngle4f()));
            });
        } else {
            current.display.teleport(at);
        }
    }

    private void tickShrines() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (RitualStore.Shrine shrine : store.shrines()) {
            World world = Bukkit.getWorld(shrine.world());
            if (world == null) continue;
            Location head = new Location(world, shrine.x(), shrine.y(), shrine.z());
            if (!world.isChunkLoaded(head.getBlockX() >> 4, head.getBlockZ() >> 4)) continue;
            Collection<Player> near = world.getNearbyPlayers(head, ACTIVE_RANGE);
            if (near.isEmpty()) continue;
            ShrineView view = views.computeIfAbsent(shrine.id(), k -> new ShrineView());
            ItemDisplay display = ensureDisplay(world, shrine, view);
            view.angle = (float) ((view.angle + 0.7f) % (Math.PI * 2.0));
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(20);
            display.setTransformation(skullTransformation(view.angle));

            if (random.nextDouble() < 0.6) hellishParticles(world, head, random.nextInt(5));
            if (random.nextDouble() < 0.12) {
                world.playSound(head, SKULL_SOUNDS[random.nextInt(SKULL_SOUNDS.length)], SoundCategory.AMBIENT,
                    1.2f, 0.6f + random.nextFloat() * 0.5f);
            }
            for (Player player : near) ambience(player, head, random);
            if (plugin.getConfig().getBoolean("ritual.mobs", true) && random.nextInt(90) == 0) {
                spawnMobs(world, head.toVector(), 1);
            }
        }
    }

    private void hellishParticles(World world, Location head, int kind) {
        switch (kind) {
            case 0 -> world.spawnParticle(Particle.ASH, head, 8, 0.5, 0.2, 0.5, 0.0);
            case 1 -> world.spawnParticle(Particle.SOUL, head, 2, 0.3, 0.1, 0.3, 0.01);
            case 2 -> world.spawnParticle(Particle.FALLING_OBSIDIAN_TEAR, head, 2, 0.4, 0.1, 0.4, 0.0);
            case 3 -> world.spawnParticle(Particle.CRIMSON_SPORE, head, 6, 0.6, 0.3, 0.6, 0.0);
            default -> world.spawnParticle(Particle.SOUL_FIRE_FLAME, head, 2, 0.3, 0.1, 0.3, 0.01);
        }
    }

    /** Players within 20 blocks on every axis hear a changing Nether ambience; each hears one every 6-14 seconds. */
    private void ambience(Player player, Location head, ThreadLocalRandom random) {
        Location at = player.getLocation();
        if (Math.abs(at.getX() - head.getX()) > AMBIENCE_RANGE || Math.abs(at.getY() - head.getY()) > AMBIENCE_RANGE
            || Math.abs(at.getZ() - head.getZ()) > AMBIENCE_RANGE) return;
        if (ambientDue.getOrDefault(player.getUniqueId(), 0L) > tick) return;
        player.playSound(at, AMBIENT[random.nextInt(AMBIENT.length)], SoundCategory.AMBIENT, 0.7f, 1.0f);
        ambientDue.put(player.getUniqueId(), tick + 120 + random.nextInt(160));
    }

    private ItemDisplay ensureDisplay(World world, RitualStore.Shrine shrine, ShrineView view) {
        Entity existing = view.display == null ? null : Bukkit.getEntity(view.display);
        if (existing instanceof ItemDisplay display && display.isValid()) return display;
        // Displays are never saved with the chunk; they are recreated from shrines.yml whenever the chunk is loaded.
        ItemDisplay display = world.spawn(new Location(world, shrine.x(), shrine.y(), shrine.z()), ItemDisplay.class, spawned -> {
            spawned.setItemStack(new ItemStack(Material.WITHER_SKELETON_SKULL));
            spawned.setPersistent(false);
            spawned.setViewRange(2.0f);
            spawned.setTransformation(skullTransformation(view.angle));
        });
        view.display = display.getUniqueId();
        return display;
    }

    private static Transformation skullTransformation(float angle) {
        return new Transformation(new Vector3f(0f, RitualRules.SKULL_LIFT, 0f), new AxisAngle4f(angle, 0f, 1f, 0f),
            new Vector3f(RitualRules.SKULL_SCALE), new AxisAngle4f());
    }

    /** Rare zombified piglins and zoglins around the shrine; never more than a handful nearby. */
    private void spawnMobs(World world, Vector centre, int count) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location middle = new Location(world, centre.getX(), centre.getY(), centre.getZ());
        if (world.getNearbyEntities(middle, 24.0, 24.0, 24.0, e -> e instanceof PigZombie || e instanceof Zoglin).size() >= 5) return;
        for (int spawned = 0, attempts = 0; spawned < count && attempts < 12; attempts++) {
            double angle = random.nextDouble(Math.PI * 2.0);
            double distance = 3.0 + random.nextDouble(6.0);
            int x = (int) Math.floor(centre.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(centre.getZ() + Math.sin(angle) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Location spot = new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5);
            if (!spot.getBlock().isPassable() || !spot.clone().add(0, 1, 0).getBlock().isPassable()
                || !spot.clone().subtract(0, 1, 0).getBlock().isSolid()) continue;
            if (random.nextDouble() < 0.8) world.spawn(spot, PigZombie.class, mob -> mob.setRemoveWhenFarAway(true));
            else world.spawn(spot, Zoglin.class, mob -> mob.setRemoveWhenFarAway(true));
            spawned++;
        }
    }

    /** After a crash or restart in the middle of a ritual the skull and the heart go back to their owners. */
    private void recoverInterrupted() {
        for (RitualStore.Active active : store.actives()) {
            World world = Bukkit.getWorld(active.world());
            if (world == null) continue;
            Location altar = new Location(world, active.x(), active.y(), active.z());
            world.getChunkAtAsync(altar).thenAccept(chunk -> {
                if (active.donor() != null) giveBackHeart(active.donor());
                world.dropItemNaturally(altar.clone().add(0.5, 1.2, 0.5), new ItemStack(Material.SKELETON_SKULL));
                store.removeActive(active.id());
                plugin.getLogger().warning("Returned the skull of an interrupted ritual at " + active.x() + " "
                    + active.y() + " " + active.z());
            });
        }
    }

    private void giveBackHeart(UUID uuid) {
        DataManager data = plugin.getDataManager();
        int hearts = Math.min(data.getHearts(uuid) + 1, plugin.getMaxHearts());
        data.setHearts(uuid, hearts);
        data.saveSafely();
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) online.setMaxHealth(plugin.maxHealthForHearts(hearts));
    }

    private static void removeEntity(UUID id) {
        if (id == null) return;
        Entity entity = Bukkit.getEntity(id);
        if (entity != null) entity.remove();
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld().equals(b.getWorld()) && a.getBlockX() == b.getBlockX()
            && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }
}

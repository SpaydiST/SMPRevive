package macronux.smprevive.event;

import macronux.smprevive.SMPRevive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Owns event phase timing, participant state, and restoration of world/player state. */
public final class EventManager implements Listener {
    private static final double CLOUD_HEIGHT_OFFSET = 12.0;

    public enum StartResult {
        STARTED,
        ALREADY_RUNNING,
        NO_PLAYERS,
        BORDER_TOO_LARGE,
        SETUP_FAILED
    }

    private final SMPRevive plugin;
    private final EventSpawnLocator spawnLocator;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private World world;
    private Location center;
    private BorderSnapshot border;
    private EventRules.Phase phase;
    private int phaseTick;
    private int sceneTick;
    private int maxHeartDelay;
    private double borderSlowTarget;
    private boolean playerRiseSoundPlayed;
    private boolean heartCenterSoundPlayed;
    private ItemDisplay centralHeart;
    private Location centralHeartStart;
    private Location centralHeartEnd;
    private BukkitTask task;
    private final List<EventSpawnLocator.SearchHandle> spawnSearches = new ArrayList<>();
    private final Map<UUID, EventSpawnLocator.SearchHandle> spawnSearchByParticipant = new LinkedHashMap<>();
    private int pendingSpawnSearches;
    private boolean preparingBurst;
    private boolean finishing;

    private final List<Player> newcomers = new ArrayList<>();
    private final Map<UUID, Location> limboOrigins = new LinkedHashMap<>();
    private final Map<UUID, Boolean> limboDeathStates = new LinkedHashMap<>();
    private final Map<UUID, GameMode> limboGameModes = new LinkedHashMap<>();
    private final Map<UUID, Location> formationPoints = new LinkedHashMap<>();
    private boolean newcomerScene;
    private int formationTick;
    private int formationIndex;

    static List<? extends Player> limboParticipants(java.util.Collection<? extends Player> online, World limbo) {
        return online.stream().filter(p -> p.getWorld() == limbo && !p.isDead()).toList();
    }

    private final GuideTrail guide;

    // Newbie-event TNT helmet: released once when the cloud is fully formed, then homes in on one player.
    private static final int TNT_HEAD_DELAY_TICKS = 220;
    private static final double TNT_HEAD_SPEED = 0.9;
    private static final int TNT_HEAD_MAX_FLIGHT_TICKS = 400;
    private boolean tntHeadPending;
    private org.bukkit.entity.BlockDisplay tntHeadDisplay;
    private UUID tntHeadTarget;
    private int tntHeadFlightTicks;

    public int startGuide(World guideWorld, Location point) { return guide.start(guideWorld, point); }
    public boolean stopGuide() { return guide.stop(); }
    public boolean isGuideActive() { return guide.isActive(); }

    public StartResult startNewbies(Player initiator, Location point) {
        guide.stop(); // The players the trail was leading are about to be moved by the scene itself.
        if (isRunning()) return StartResult.ALREADY_RUNNING;
        World limbo = Bukkit.getWorld(new org.bukkit.NamespacedKey("spaydiscore", "limbo"));
        if (limbo == null) return StartResult.SETUP_FAILED;
        if (point.getWorld() == limbo || !point.getWorld().getWorldBorder().isInside(point)) return StartResult.SETUP_FAILED;
        List<? extends Player> waiting = limboParticipants(Bukkit.getOnlinePlayers(), limbo);
        if (waiting.isEmpty()) return StartResult.NO_PLAYERS;
        world = point.getWorld(); center = point.clone(); border = BorderSnapshot.capture(world.getWorldBorder());
        try {
            for (int i = 0; i < waiting.size(); i++) {
                Player player = waiting.get(i);
                double angle = i * Math.PI * 2 / waiting.size();
                Location ground = spawnLocator.findSafeAt(world,
                    (int)Math.floor(point.getX() + Math.cos(angle) * 20),
                    (int)Math.floor(point.getZ() + Math.sin(angle) * 20));
                if (ground == null) throw new IllegalStateException("No safe formation point near event center");
                Location air = ground.clone();
                air.setY(Math.max(ground.getY()+3, point.getY()));
                if (air.getY()+3 >= world.getMaxHeight()) throw new IllegalStateException("Formation point above build height");
                for (int y = 0; y < 3; y++) if (!air.clone().add(0,y,0).getBlock().isPassable())
                    throw new IllegalStateException("Formation point is obstructed");
                if (!world.getWorldBorder().isInside(air)) throw new IllegalStateException("Formation point outside border");
                spawnLocator.holdLocation(air);
                formationPoints.put(player.getUniqueId(), air);
                limboOrigins.put(player.getUniqueId(), player.getLocation().clone());
            }
            newcomers.addAll(waiting); newcomerScene = true; formationTick = 0; formationIndex = 0;
            tntHeadPending = plugin.getConfig().getBoolean("event.tnt-head.enabled", true);
            phase = EventRules.Phase.HEARTS_RISE; phaseTick = 0; sceneTick = 0;
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
            return StartResult.STARTED;
        } catch (RuntimeException error) { plugin.getLogger().warning(error.getMessage()); stop(true); return StartResult.SETUP_FAILED; }
    }

    private void blackParticles(Location location, int count) {
        world.spawnParticle(Particle.DUST, location, count, .25, .5, .25, 0,
            new Particle.DustOptions(org.bukkit.Color.BLACK, 1.5f));
    }

    private void tickFormation() {
        formationTick++;
        for (Participant participant : new ArrayList<>(participants.values())) {
            if (!participant.player.isOnline() || participant.player.getWorld() != world
                || participant.player.isDead() || plugin.getDataManager().isDead(participant.uuid)) {
                if (participant.player.isDead() || plugin.getDataManager().isDead(participant.uuid)) limboDeathStates.remove(participant.uuid);
                removeParticipant(participant.uuid);
                continue;
            }
            if (participant.player.isOnline()) {
                participant.player.setGravity(false);
                participant.player.setAllowFlight(true);
                participant.player.setFlying(true);
                participant.player.setVelocity(EventRules.motionVelocity(participant.motionTarget.toVector()
                    .subtract(participant.player.getLocation().toVector())));
                participant.player.setFallDistance(0);
            }
        }
        if (formationTick <= 80) {
            double t = formationTick / 80.0;
            for (int i=0; i<24; i++) {
                double a = i * Math.PI / 12 + t * 4;
                blackParticles(center.clone().add(Math.cos(a)*20*(1-t), Math.sin(t*Math.PI)*3, Math.sin(a)*20*(1-t)), 1);
            }
            return;
        }
        if (formationIndex >= newcomers.size()) {
            newcomerScene = false;
            if (participants.isEmpty()) { stop(true); return; }
            for (Participant participant : participants.values()) {
                participant.heartAnchor = participant.player.getLocation().add(0,2,0);
                participant.riseStart = participant.player.getLocation();
                participant.heart = world.spawn(participant.heartAnchor, ItemDisplay.class);
                participant.heart.setItemStack(plugin.getItemManager().createHeart());
                configureDisplay(participant.heart,48,2,2);
            }
            phase = EventRules.Phase.HEARTS_RISE; phaseTick = 0; sceneTick = 0;
            return;
        }
        // A relogged newcomer is a new Player object; resolve the live one by id.
        Player player = Bukkit.getPlayer(newcomers.get(formationIndex).getUniqueId());
        if (player == null) player = newcomers.get(formationIndex);
        int step = (formationTick - 81) % 40;
        Location destination = formationPoints.get(player.getUniqueId());
        double t = step / 30.0;
        if (step < 30) {
            Location trail = center.clone().add(destination.toVector().subtract(center.toVector()).multiply(Math.min(1,t)));
            blackParticles(trail, 12);
            for (int i=0;i<8;i++) {
                double a = i*Math.PI/4 + t*8;
                blackParticles(destination.clone().add(Math.cos(a)*.4, i*.23, Math.sin(a)*.4), 1);
            }
        } else if (step == 30 && player.isOnline() && !player.isDead()
            && player.getWorld() == limboOrigins.get(player.getUniqueId()).getWorld()) {
            boolean wasDead = plugin.getDataManager().isDead(player.getUniqueId());
            limboDeathStates.put(player.getUniqueId(), wasDead);
            plugin.getDataManager().setDead(player.getUniqueId(), false);
            if (!plugin.getDataManager().saveSafely()) {
                plugin.getDataManager().setDead(player.getUniqueId(), wasDead);
                throw new IllegalStateException("Could not persist limbo departure");
            }
            limboGameModes.put(player.getUniqueId(), player.getGameMode());
            player.setGameMode(plugin.getDefaultGameMode());
            addParticipant(player);
            Participant newcomer = participants.get(player.getUniqueId());
            newcomer.gravity = true;
            newcomer.allowFlight = false;
            newcomer.flying = false;
            newcomer.walkSpeed = .2f;
            newcomer.flySpeed = .1f;
            removeDisplay(participants.get(player.getUniqueId()));
            teleportPlayer(participants.get(player.getUniqueId()), destination);
            blackParticles(destination.clone().add(0,1,0), 70);
        }
        if (step == 39) formationIndex++;
    }

    public EventManager(SMPRevive plugin) {
        this.plugin = plugin;
        this.spawnLocator = plugin.getEventSpawnLocator();
        this.guide = new GuideTrail(plugin);
    }

    public boolean isRunning() {
        return this.task != null && (!this.participants.isEmpty() || newcomerScene);
    }

    public boolean isNewbieParticipant(UUID uuid) { return limboOrigins.containsKey(uuid); }

    public int participantCount() { return newcomerScene ? newcomers.size() : this.participants.size(); }
    public String phaseName() { return newcomerScene ? "NEWBIE_FORMATION" : this.phase == null ? "IDLE" : this.phase.name(); }

    /** Uses the same eligibility rules as start(), without changing players or loading chunks. */
    public List<String> preview(World selectedWorld) {
        return eligiblePlayers(selectedWorld).stream().map(Player::getName).toList();
    }

    private List<Player> eligiblePlayers(World selectedWorld) {
        List<Player> eligible = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline() && player.getWorld().equals(selectedWorld)
                && player.getGameMode() != GameMode.SPECTATOR && !player.isDead()
                && !plugin.getDataManager().isDead(player.getUniqueId())) eligible.add(player);
        }
        return eligible;
    }

    public StartResult start(Player initiator) {
        if (isRunning()) return StartResult.ALREADY_RUNNING;
        if (initiator == null || !initiator.isOnline() || initiator.getWorld() == null) {
            return StartResult.NO_PLAYERS;
        }

        World selectedWorld = initiator.getWorld();
        WorldBorder selectedBorder = selectedWorld.getWorldBorder();
        if (selectedBorder.getSize() > EventRules.TARGET_BORDER_SIZE) {
            return StartResult.BORDER_TOO_LARGE;
        }

        List<Player> eligible = eligiblePlayers(selectedWorld);
        if (eligible.isEmpty()) return StartResult.NO_PLAYERS;

        this.world = selectedWorld;
        this.center = calculateCenter(selectedWorld, selectedBorder, eligible);
        this.border = BorderSnapshot.capture(selectedBorder);
        this.phase = EventRules.Phase.HEARTS_RISE;
        this.phaseTick = 0;
        this.sceneTick = 0;
        this.maxHeartDelay = EventRules.HEART_DELAY_MAX_TICKS;
        this.playerRiseSoundPlayed = false;
        this.heartCenterSoundPlayed = false;
        this.finishing = false;

        try {
            for (Player player : eligible) addParticipant(player);
            this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
            playEventSound(this.center, "event.start");
            return StartResult.STARTED;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Failed to start event: " + exception.getMessage());
            stop(true);
            return StartResult.SETUP_FAILED;
        }
    }

    public void stop(boolean restoreBorder) {
        if (finishing) return;
        finishing = true;
        try {
            guide.stop();
            removeTntHead();
            tntHeadPending = false;
            if (task != null) task.cancel();
            task = null;
            for (EventSpawnLocator.SearchHandle search : spawnSearches) search.cancel();
            spawnSearches.clear();
            spawnSearchByParticipant.clear();
            pendingSpawnSearches = 0;
            preparingBurst = false;
            removeCentralHeart();
            // One failing participant must not skip the others or the border restore.
            for (Participant participant : new ArrayList<>(participants.values())) {
                try {
                    removeDisplay(participant);
                    restorePlayer(participant, restoreBorder);
                } catch (RuntimeException error) {
                    plugin.getLogger().warning("Failed to restore event participant: " + error);
                }
            }
            participants.clear();
            try {
                if (restoreBorder && !limboDeathStates.isEmpty()) {
                    for (var entry : limboDeathStates.entrySet()) plugin.getDataManager().setDead(entry.getKey(), entry.getValue());
                    plugin.getDataManager().saveSafely();
                }
            } catch (RuntimeException error) {
                plugin.getLogger().warning("Failed to restore limbo death states: " + error);
            }
            newcomers.clear(); formationPoints.clear(); limboOrigins.clear(); limboDeathStates.clear(); limboGameModes.clear(); newcomerScene = false;
            if (restoreBorder && border != null && world != null) border.restore(world.getWorldBorder());
        } finally {
            world = null;
            center = null;
            border = null;
            phase = null;
            phaseTick = 0;
            sceneTick = 0;
            maxHeartDelay = 0;
            borderSlowTarget = 0.0;
            playerRiseSoundPlayed = false;
            heartCenterSoundPlayed = false;
            centralHeartStart = null;
            centralHeartEnd = null;
            preparingBurst = false;
            spawnLocator.releaseTickets();
            finishing = false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (participants.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    /** A server crash mid-event leaves frozen speeds and flight in playerdata; undo that when no event is running. */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isRunning() || participants.containsKey(player.getUniqueId())) return;
        if (player.getWalkSpeed() != 0.0f || player.getFlySpeed() != 0.0f) return;
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
        player.setGravity(true);
        GameMode mode = player.getGameMode();
        if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
        plugin.getLogger().warning("Reset event movement lock left on " + player.getName() + " by an interrupted event");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeParticipant(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event == null || event.isCancelled()) return;
        // A new death belongs to the death handler, not this event's rollback.
        limboDeathStates.remove(event.getEntity().getUniqueId());
        removeParticipant(event.getEntity().getUniqueId());
    }

    private void tick() {
        if (!isRunning() || world == null || center == null || phase == null) return;
        long start = System.nanoTime();
        try {
            if (newcomerScene) { tickFormation(); return; }
            phaseTick++;
            sceneTick++;
            for (Participant participant : new ArrayList<>(participants.values())) {
                if (!participant.player.isOnline()
                    || !participant.player.getWorld().equals(world)
                    || participant.player.isDead()
                    || plugin.getDataManager().isDead(participant.uuid)) {
                    if (participant.player.isDead() || plugin.getDataManager().isDead(participant.uuid)) limboDeathStates.remove(participant.uuid);
                    removeParticipant(participant.uuid);
                    continue;
                }
            }
            if (!isRunning()) return;

            tickCloud();
            tickTntHead();
            switch (phase) {
                case HEARTS_RISE -> tickHeartsRise();
                case HEARTS_TO_CENTER -> tickHeartsToCenter();
                case PLAYERS_RISE -> tickPlayersRise();
                case BORDER_EXPANSION -> tickBorderExpansion();
                case BURST -> tickBurst();
                case PLAYER_LAND -> tickLanding();
            }
            // One controller also holds the last target during pauses; never rewrite move packets.
            for (Participant participant : participants.values()) {
                Player player = participant.player;
                player.setGravity(false);
                player.setAllowFlight(true);
                if (!player.isFlying()) player.setFlying(true);
                Vector offset = participant.motionTarget.toVector().subtract(player.getLocation().toVector());
                player.setVelocity(EventRules.motionVelocity(offset));
                player.setFallDistance(0.0f);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Event phase execution failed: " + exception.getMessage());
            stop(true);
        } finally {
            plugin.getPerformanceCounters().record("event-tick", start);
        }
    }

    private void tickCloud() {
        if (world == null || center == null) return;
        double progress = EventRules.clamp01(sceneTick / 220.0);
        double radius = EventRules.cloudRadius(progress);
        double halfHeight = EventRules.cloudHalfHeight(progress);
        int count = (int) Math.round(EventRules.interpolate(20.0, 55.0, progress));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            double distance = radius * Math.sqrt(random.nextDouble());
            Location particle = center.clone().add(
                Math.cos(angle) * distance,
                random.nextDouble(-halfHeight, halfHeight),
                Math.sin(angle) * distance
            );
            world.spawnParticle(Particle.CLOUD, particle, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private void tickTntHead() {
        try {
            if (tntHeadDisplay == null) {
                if (tntHeadPending && sceneTick >= TNT_HEAD_DELAY_TICKS) {
                    tntHeadPending = false;
                    launchTntHead();
                }
                return;
            }
            flyTntHead();
        } catch (RuntimeException error) {
            // A decorative projectile must never abort the event.
            plugin.getLogger().warning("TNT helmet flight failed: " + error);
            removeTntHead();
        }
    }

    private boolean validTntHeadTarget(Player player) {
        return player != null && player.isOnline() && !player.isDead() && player.getWorld().equals(world)
            && player.getGameMode() != GameMode.SPECTATOR;
    }

    private boolean isNewcomer(UUID uuid) {
        if (participants.containsKey(uuid)) return true;
        for (Player newcomer : newcomers) if (newcomer.getUniqueId().equals(uuid)) return true;
        return false;
    }

    /** Random onlooker near the cloud: an established player watching the scene, never one of the newcomers from limbo. */
    private Player pickTntHeadTarget() {
        double radius = Math.max(1.0, plugin.getConfig().getDouble("event.tnt-head.radius", 100.0));
        double limit = radius * radius;
        List<Player> candidates = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (validTntHeadTarget(player) && !isNewcomer(player.getUniqueId())
                && player.getLocation().distanceSquared(center) <= limit
                && !macronux.smprevive.item.TntHeadItem.isTntHead(player.getInventory().getHelmet())) {
                candidates.add(player);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private void launchTntHead() {
        if (Bukkit.getPluginManager().getPlugin("Toolkit") == null) {
            plugin.getLogger().warning("Toolkit is not installed: the TNT helmet will not explode when its wearer is hurt.");
        }
        Player target = pickTntHeadTarget();
        if (target == null) {
            plugin.getLogger().info("No onlooker within range of the cloud; the TNT helmet was not released.");
            return;
        }
        tntHeadTarget = target.getUniqueId();
        tntHeadFlightTicks = 0;
        tntHeadDisplay = world.spawn(center.clone(), org.bukkit.entity.BlockDisplay.class, display -> {
            display.setBlock(org.bukkit.Material.TNT.createBlockData());
            display.setPersistent(false);
            display.setViewRange(96.0f);
            display.setInterpolationDuration(2);
            display.setTeleportDuration(2);
        });
        world.playSound(center, "entity.tnt.primed", org.bukkit.SoundCategory.MASTER, 1.5f, 1.0f);
        world.spawnParticle(Particle.CLOUD, center, 40, 1.0, 1.0, 1.0, 0.1);
    }

    private void flyTntHead() {
        tntHeadFlightTicks++;
        Player target = Bukkit.getPlayer(tntHeadTarget);
        if (!validTntHeadTarget(target)) {
            target = pickTntHeadTarget();
            if (target == null) { removeTntHead(); return; }
            tntHeadTarget = target.getUniqueId();
        }
        if (tntHeadFlightTicks > TNT_HEAD_MAX_FLIGHT_TICKS) { removeTntHead(); return; }
        Location position = tntHeadDisplay.getLocation();
        Vector delta = target.getEyeLocation().toVector().subtract(position.toVector());
        double distance = delta.length();
        if (distance <= 1.0) {
            equipTntHead(target);
            removeTntHead();
            return;
        }
        position.add(delta.multiply(Math.min(distance, TNT_HEAD_SPEED) / distance));
        tntHeadDisplay.teleport(position);
        float spin = tntHeadFlightTicks * 0.35f;
        Quaternionf rotation = new Quaternionf().rotateY(spin).rotateX(spin * 0.6f);
        float scale = 0.6f;
        // Rotate around the block's centre rather than its corner.
        Vector3f centring = new Vector3f(0.5f * scale).rotate(rotation).negate();
        tntHeadDisplay.setTransformation(new Transformation(centring, rotation, new Vector3f(scale), new Quaternionf()));
        world.spawnParticle(Particle.SMOKE, position, 2, 0.1, 0.1, 0.1, 0.0);
    }

    private void equipTntHead(Player target) {
        var inventory = target.getInventory();
        ItemStack previous = inventory.getHelmet();
        if (previous != null && !previous.getType().isAir()) {
            // Whatever was on their head is knocked off and thrown out of them.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            target.getWorld().dropItem(target.getEyeLocation(), previous.clone(), item -> {
                item.setVelocity(new Vector(random.nextDouble(-0.25, 0.25), 0.3, random.nextDouble(-0.25, 0.25)));
                item.setPickupDelay(40);
            });
        }
        inventory.setHelmet(macronux.smprevive.item.TntHeadItem.create());
        Location head = target.getEyeLocation();
        target.getWorld().playSound(head, "item.armor.equip_iron", org.bukkit.SoundCategory.MASTER, 1.2f, 0.8f);
        target.getWorld().spawnParticle(Particle.CLOUD, head, 25, 0.3, 0.3, 0.3, 0.05);
    }

    private void removeTntHead() {
        if (tntHeadDisplay != null && !tntHeadDisplay.isDead()) tntHeadDisplay.remove();
        tntHeadDisplay = null;
        tntHeadTarget = null;
    }

    private void tickHeartsRise() {
        if (phaseTick == 1) playEventSound(center, "event.heart-rise");
        double progress = EventRules.smoothstep(
            (double) phaseTick / EventRules.HEART_RISE_TICKS
        );
        for (Participant participant : new ArrayList<>(participants.values())) {
            if (participant.heart == null || participant.heart.isDead()) continue;
            Location anchor = participant.heartAnchor.clone();
            float angle = (float) (progress * Math.PI * 4.0);
            float scale = (float) EventRules.interpolate(0.85, 0.70, progress);
            participant.heart.teleport(anchor);
            participant.heart.setTransformation(new Transformation(
                new Vector3f(0.0f, (float) (progress * 3.0), 0.0f),
                new Quaternionf().rotateY(angle),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
            ));
            if (phaseTick % 2 == 0) {
                world.spawnParticle(Particle.ENCHANT, anchor.clone().add(0.0, progress * 3.0 + 0.2, 0.0),
                    5, 0.25, 0.25, 0.25, 0.06);
            }
        }
        if (phaseTick >= EventRules.HEART_RISE_TICKS) {
            phase = EventRules.Phase.HEARTS_TO_CENTER;
            phaseTick = 0;
            maxHeartDelay = 0;
            for (Participant participant : participants.values()) {
                participant.heartStart = participant.heartAnchor.clone().add(0.0, 3.0, 0.0);
                participant.heartCompleted = participant.heart == null || participant.heart.isDead();
                maxHeartDelay = Math.max(maxHeartDelay, participant.heartDelay);
                if (!participant.heartCompleted) {
                    participant.heart.setTransformation(new Transformation(
                        new Vector3f(),
                        new Quaternionf(),
                        new Vector3f(0.85f, 0.85f, 0.85f),
                        new Quaternionf()
                    ));
                }
            }
        }
    }

    private void tickHeartsToCenter() {
        Location target = center.clone().add(0.0, 1.0, 0.0);
        for (Participant participant : new ArrayList<>(participants.values())) {
            if (participant.heartCompleted || participant.heart == null || participant.heart.isDead()) {
                participant.heartCompleted = true;
                continue;
            }
            int travelTick = phaseTick - participant.heartDelay;
            if (travelTick <= 0) {
                participant.heart.teleport(participant.heartStart);
                continue;
            }
            double progress = EventRules.clamp01((double) travelTick / EventRules.HEART_TRAVEL_TICKS);
            double eased = EventRules.smoothstep(progress);
            Location start = participant.heartStart;
            Location position = new Location(
                world,
                EventRules.interpolate(start.getX(), target.getX(), eased),
                EventRules.interpolate(start.getY(), target.getY(), eased)
                    + Math.sin(progress * Math.PI) * 3.0,
                EventRules.interpolate(start.getZ(), target.getZ(), eased)
            );
            float angle = (float) (progress * Math.PI * 5.0);
            float scale = (float) EventRules.interpolate(0.85, 0.62, eased);
            participant.heart.teleport(position);
            participant.heart.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf().rotateY(angle),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
            ));
            world.spawnParticle(Particle.ENCHANT, position, 5, 0.25, 0.25, 0.25, 0.06);
            if (progress >= 1.0) {
                removeDisplay(participant);
                participant.heartCompleted = true;
                if (!heartCenterSoundPlayed) {
                    playEventSound(target, "event.heart-center");
                    heartCenterSoundPlayed = true;
                }
            }
        }
        if (phaseTick >= maxHeartDelay + EventRules.HEART_TRAVEL_TICKS) {
            phase = EventRules.Phase.PLAYERS_RISE;
            phaseTick = 0;
            playerRiseSoundPlayed = false;
            for (Participant participant : participants.values()) {
                participant.riseStart = participant.player.getLocation().clone();
            }
        }
    }

    private void tickPlayersRise() {
        double maxDelay = EventRules.PLAYER_DELAY_MAX_TICKS;
        for (Participant participant : participants.values()) {
            if (phaseTick <= participant.playerDelay) continue;
            if (!playerRiseSoundPlayed) {
                playEventSound(center, "event.player-rise");
                playerRiseSoundPlayed = true;
            }
            double progress = EventRules.clamp01(
                (double) (phaseTick - participant.playerDelay) / EventRules.PLAYER_RISE_TICKS
            );
            double eased = EventRules.smoothstep(progress);
            Location position = participant.riseStart.clone().add(0.0, eased * 12.0, 0.0);
            movePlayerSmoothly(participant, position);
            world.spawnParticle(Particle.ENCHANT, position.clone().add(0.0, 0.6, 0.0),
                3, 0.25, 0.25, 0.25, 0.05);
            maxDelay = Math.max(maxDelay, participant.playerDelay);
        }
        if (phaseTick >= maxDelay + EventRules.PLAYER_RISE_TICKS) {
            phase = EventRules.Phase.BORDER_EXPANSION;
            phaseTick = 0;
            borderSlowTarget = EventRules.slowBorderTarget(border.size);
            playEventSound(center, "event.border-slow");
        }
    }

    private void tickBorderExpansion() {
        if (preparingBurst) return;
        if (!limboOrigins.isEmpty()) { prepareBurst(); return; }
        WorldBorder current = world.getWorldBorder();
        if (phaseTick <= EventRules.BORDER_SLOW_TICKS) {
            double progress = (double) phaseTick / EventRules.BORDER_SLOW_TICKS;
            current.setSize(EventRules.interpolate(
                border.size,
                borderSlowTarget,
                EventRules.smoothstep(progress)
            ));
        } else {
            if (phaseTick == EventRules.BORDER_SLOW_TICKS + 1) {
                playEventSound(center, "event.border-fast");
            }
            double progress = (double) (phaseTick - EventRules.BORDER_SLOW_TICKS)
                / EventRules.BORDER_FAST_TICKS;
            double accelerated = EventRules.clamp01(progress);
            accelerated *= accelerated;
            current.setSize(EventRules.interpolate(
                borderSlowTarget,
                EventRules.TARGET_BORDER_SIZE,
                accelerated
            ));
        }
        if (phaseTick >= EventRules.BORDER_SLOW_TICKS + EventRules.BORDER_FAST_TICKS) {
            current.setSize(EventRules.TARGET_BORDER_SIZE);
            prepareBurst();
        }
    }

    private void prepareBurst() {
        preparingBurst = true;
        pendingSpawnSearches = participants.size();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Participant participant : participants.values()) {
            participant.burstStart = participant.player.getLocation().clone();
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            double radius = random.nextDouble(4.0, 10.0);
            participant.burstEnd = participant.burstStart.clone().add(
                Math.cos(angle) * radius,
                random.nextDouble(35.0, 55.0),
                Math.sin(angle) * radius
            );
            EventSpawnLocator.SearchHandle search = spawnLocator.findAsync(world, target -> {
                try {
                    onLandingTarget(participant.uuid, target);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Could not prepare event landing: " + exception.getMessage());
                    stop(true);
                }
            });
            spawnSearches.add(search);
            spawnSearchByParticipant.put(participant.uuid, search);
        }
        if (pendingSpawnSearches == 0) finishBurstPreparation();
    }

    private void onLandingTarget(UUID uuid, Location target) {
        if (!preparingBurst || world == null) return;
        Participant participant = participants.get(uuid);
        if (spawnSearchByParticipant.remove(uuid) == null) return;
        if (participant == null) {
            if (--pendingSpawnSearches == 0) finishBurstPreparation();
            return;
        }
        if (target == null) {
            Location fallback = participant.startLocation;
            if (limboOrigins.containsKey(uuid)) fallback = null;
            if (fallback == null || fallback.getWorld() != world) {
                abortBurstPreparation();
                return;
            }
            participant.landingTarget = fallback.clone();
        } else if (target.getWorld() != world) {
            abortBurstPreparation();
            return;
        } else {
            participant.landingTarget = target;
        }
        pendingSpawnSearches--;
        if (pendingSpawnSearches == 0) finishBurstPreparation();
    }

    private void finishBurstPreparation() {
        if (!preparingBurst) return;
        preparingBurst = false;
        for (EventSpawnLocator.SearchHandle search : spawnSearches) search.cancel();
        spawnSearches.clear();
        spawnSearchByParticipant.clear();
        phase = EventRules.Phase.BURST;
        phaseTick = 0;
        Location heartLocation = center.clone().add(0.0, 1.0, 0.0);
        centralHeartStart = heartLocation.clone();
        centralHeartEnd = heartLocation.clone().add(0.0, 18.0, 0.0);
        centralHeart = world.spawn(heartLocation, ItemDisplay.class);
        centralHeart.setItemStack(plugin.getItemManager().createHeart());
        configureDisplay(centralHeart, 64.0f, 2, 2);
        centralHeart.setTransformation(new Transformation(
            new Vector3f(),
            new Quaternionf(),
            new Vector3f(1.35f, 1.35f, 1.35f),
            new Quaternionf()
        ));

        world.spawnParticle(Particle.EXPLOSION, heartLocation, 30, 1.0, 1.0, 1.0, 0.0);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, heartLocation, 90, 1.2, 1.2, 1.2, 0.45);
        world.spawnParticle(Particle.ENCHANT, heartLocation, 80, 1.0, 1.5, 1.0, 0.25);
        spawnBurstCloudPulse(heartLocation);
        playEventSound(heartLocation, "event.burst");

        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Participant participant : participants.values()) {
            double landingAngle = random.nextDouble(0.0, Math.PI * 2.0);
            double landingRadius = random.nextDouble(4.0, 10.0);
            participant.landingHigh = participant.landingTarget.clone().add(
                Math.cos(landingAngle) * landingRadius,
                random.nextDouble(28.0, 40.0),
                Math.sin(landingAngle) * landingRadius
            );
            participant.landSoundPlayed = false;
        }
    }

    private void abortBurstPreparation() {
        if (!preparingBurst) return;
        stop(true);
    }

    private void spawnBurstCloudPulse(Location location) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 80; i++) {
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            double radius = random.nextDouble(2.5, 3.8);
            Location particle = location.clone().add(
                Math.cos(angle) * radius,
                random.nextDouble(-2.0, 2.0),
                Math.sin(angle) * radius
            );
            world.spawnParticle(Particle.CLOUD, particle, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private void tickBurst() {
        int playerLaunchStart = EventRules.BURST_HEART_HOLD_TICKS + EventRules.BURST_HEART_FLIGHT_TICKS;
        double heartProgress = EventRules.clamp01(
            (double) (phaseTick - EventRules.BURST_HEART_HOLD_TICKS)
                / EventRules.BURST_HEART_FLIGHT_TICKS
        );
        double heartEased = EventRules.smoothstep(heartProgress);
        double playerProgress = EventRules.clamp01(
            (double) (phaseTick - playerLaunchStart) / EventRules.BURST_PLAYER_FLIGHT_TICKS
        );
        double playerEased = EventRules.smoothstep(playerProgress);
        if (centralHeart != null && !centralHeart.isDead()) {
            Location heartPosition = new Location(
                world,
                EventRules.interpolate(centralHeartStart.getX(), centralHeartEnd.getX(), heartEased),
                EventRules.interpolate(centralHeartStart.getY(), centralHeartEnd.getY(), heartEased),
                EventRules.interpolate(centralHeartStart.getZ(), centralHeartEnd.getZ(), heartEased)
            );
            float pulse = (float) (1.35 + Math.sin(heartProgress * Math.PI) * 0.25);
            centralHeart.teleport(heartPosition);
            centralHeart.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf().rotateY((float) (heartProgress * Math.PI * 4.0)),
                new Vector3f(pulse, pulse, pulse),
                new Quaternionf()
            ));
            if (phaseTick > EventRules.BURST_HEART_HOLD_TICKS) {
                world.spawnParticle(Particle.ENCHANT, heartPosition, 8, 0.3, 0.3, 0.3, 0.08);
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, heartPosition, 3, 0.2, 0.2, 0.2, 0.08);
            }
        }
        for (Participant participant : participants.values()) {
            double eased = playerEased;
            Location position = new Location(
                world,
                EventRules.interpolate(participant.burstStart.getX(), participant.burstEnd.getX(), eased),
                EventRules.interpolate(participant.burstStart.getY(), participant.burstEnd.getY(), eased),
                EventRules.interpolate(participant.burstStart.getZ(), participant.burstEnd.getZ(), eased)
            );
            movePlayerSmoothly(participant, position);
            if (phaseTick > playerLaunchStart) {
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, position, 5, 0.25, 0.25, 0.25, 0.15);
            }
        }
        if (phaseTick >= EventRules.BURST_TICKS) {
            for (Participant participant : participants.values()) {
                teleportPlayer(participant, participant.landingHigh);
            }
            phase = EventRules.Phase.PLAYER_LAND;
            phaseTick = 0;
            removeCentralHeart();
        }
    }

    private void tickLanding() {
        for (Participant participant : new ArrayList<>(participants.values())) {
            if (participant.landingTarget == null || participant.landingHigh == null) {
                throw new IllegalStateException("Event landing target is missing for " + participant.uuid);
            }
            if (!participant.landSoundPlayed) {
                plugin.playSoundToPlayer(participant.player, "event.land");
                participant.landSoundPlayed = true;
            }
            double progress = EventRules.easeOutCubic(
                (double) phaseTick / EventRules.PLAYER_LAND_TICKS
            );
            Location position = new Location(
                world,
                EventRules.interpolate(participant.landingHigh.getX(), participant.landingTarget.getX(), progress),
                EventRules.interpolate(participant.landingHigh.getY(), participant.landingTarget.getY(), progress),
                EventRules.interpolate(participant.landingHigh.getZ(), participant.landingTarget.getZ(), progress)
            );
            movePlayerSmoothly(participant, position);
            participant.player.setFallDistance(0.0f);
            world.spawnParticle(Particle.CLOUD, position, 2, 0.08, 0.04, 0.08, 0.005);
            if (phaseTick >= EventRules.PLAYER_LAND_TICKS) {
                teleportPlayer(participant, participant.landingTarget);
                participant.player.setFallDistance(0.0f);
            }
        }
        // Keep everyone available for rollback until every final teleport has succeeded.
        if (phaseTick >= EventRules.PLAYER_LAND_TICKS) {
            for (Participant participant : new ArrayList<>(participants.values())) completeLanding(participant);
        }
    }

    private void completeLanding(Participant participant) {
        restorePlayer(participant, false);
        if (limboOrigins.containsKey(participant.uuid)) {
            participant.player.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin,"newbie_waiting"), org.bukkit.persistence.PersistentDataType.BYTE, (byte)0);
            participant.player.saveData();
        }
        participants.remove(participant.uuid);
        removeDisplay(participant);
        if (limboOrigins.containsKey(participant.uuid)) {
            limboDeathStates.remove(participant.uuid);
            Player landed = participant.player;
            plugin.getReviveListener().initializeOnlinePlayer(landed);
            // initializeOnlinePlayer already stored a random fallback token; the casino lets the newcomer re-roll it.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (landed.isOnline() && !landed.isDead() && !plugin.isMechanicsDisabled(landed.getWorld())
                    && landed.getOpenInventory().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                    plugin.getCasinoGui().open(landed);
                }
            }, 20L);
        }
        if (participants.isEmpty()) stop(false);
    }

    private void removeCentralHeart() {
        if (centralHeart != null && !centralHeart.isDead()) centralHeart.remove();
        centralHeart = null;
    }

    private void addParticipant(Player player) {
        UUID uuid = player.getUniqueId();
        Participant participant = new Participant(uuid, player);
        participant.startLocation = player.getLocation().clone();
        spawnLocator.holdLocation(participant.startLocation);
        participant.motionTarget = participant.startLocation.clone();
        participant.heartAnchor = participant.startLocation.clone().add(0.0, player.getHeight() + 0.35, 0.0);
        participant.riseStart = participant.startLocation.clone();
        participant.gravity = player.hasGravity();
        participant.allowFlight = player.getAllowFlight();
        participant.flying = player.isFlying();
        participant.walkSpeed = player.getWalkSpeed();
        participant.flySpeed = player.getFlySpeed();
        participant.fallDistance = player.getFallDistance();
        participant.heartDelay = randomDelay(EventRules.HEART_DELAY_MIN_TICKS, EventRules.HEART_DELAY_MAX_TICKS);
        participant.playerDelay = randomDelay(EventRules.PLAYER_DELAY_MIN_TICKS, EventRules.PLAYER_DELAY_MAX_TICKS);
        maxHeartDelay = Math.max(maxHeartDelay, participant.heartDelay);
        participants.put(uuid, participant);
        player.setGravity(false);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setWalkSpeed(0.0f);
        player.setFlySpeed(0.0f);
        player.setVelocity(new Vector());

        if (newcomerScene) return;
        Location displayLocation = player.getLocation().clone().add(0.0, player.getHeight() + 0.35, 0.0);
        participant.heart = world.spawn(displayLocation, ItemDisplay.class);
        ItemDisplay display = participant.heart;
        ItemStack heart = plugin.getItemManager().createHeart();
        display.setItemStack(heart);
        configureDisplay(display, 48.0f, 2, 2);
    }

    private void removeParticipant(UUID uuid) {
        if (uuid == null) return;
        Participant participant = participants.remove(uuid);
        if (participant == null) return;
        EventSpawnLocator.SearchHandle search = spawnSearchByParticipant.remove(uuid);
        if (search != null) {
            search.cancel();
            pendingSpawnSearches--;
        }
        removeDisplay(participant);
        restorePlayer(participant, true);
        if (participants.isEmpty() && task != null && !newcomerScene) {
            stop(true);
            return;
        }
        if (preparingBurst && pendingSpawnSearches == 0) finishBurstPreparation();
    }

    private void removeDisplay(Participant participant) {
        if (participant.heart != null && !participant.heart.isDead()) participant.heart.remove();
        participant.heart = null;
    }

    private void restorePlayer(Participant participant, boolean restoreLocation) {
        Player player = participant.player;
        if (player == null || !player.isOnline()) return;
        if (restoreLocation && limboDeathStates.containsKey(participant.uuid)) {
            plugin.getDataManager().setDead(participant.uuid, limboDeathStates.remove(participant.uuid));
            plugin.getDataManager().saveSafely();
        }
        Location origin = limboOrigins.getOrDefault(participant.uuid, participant.startLocation);
        if (restoreLocation && origin != null && !player.isDead()) {
            try { teleportPlayer(participant, origin.clone()); }
            catch (RuntimeException error) {
                plugin.getLogger().warning("Could not restore event location: " + error.getMessage());
            }
        }
        // A newcomer rolled back into limbo must leave with the game mode they arrived with.
        GameMode limboMode = limboGameModes.remove(participant.uuid);
        if (restoreLocation && limboMode != null && limboOrigins.containsKey(participant.uuid) && !player.isDead()) {
            player.setGameMode(limboMode);
        }
        player.setGravity(participant.gravity);
        player.setAllowFlight(participant.allowFlight);
        player.setFlying(participant.flying && participant.allowFlight);
        player.setWalkSpeed(participant.walkSpeed);
        player.setFlySpeed(participant.flySpeed);
        player.setFallDistance(participant.fallDistance);
        player.setVelocity(new Vector());
    }

    private void teleportPlayer(Participant participant, Location location) {
        if (location == null || participant.player == null || !participant.player.isOnline()) return;
        Location destination = location.clone();
        Location current = participant.player.getLocation();
        destination.setYaw(current.getYaw());
        destination.setPitch(current.getPitch());
        if (!participant.player.teleport(destination)) {
            throw new IllegalStateException("Event teleport was cancelled for " + participant.uuid);
        }
        participant.motionTarget = destination.clone();
        participant.player.setVelocity(new Vector());
    }

    private void movePlayerSmoothly(Participant participant, Location target) {
        if (target != null) participant.motionTarget = target.clone();
    }

    private void configureDisplay(ItemDisplay display, float viewRange, int interpolationDuration, int teleportDuration) {
        display.setPersistent(false);
        display.setViewRange(viewRange);
        display.setInterpolationDuration(interpolationDuration);
        display.setTeleportDuration(teleportDuration);
    }

    private void playEventSound(Location location, String path) {
        plugin.playSound(location, path);
        for (Participant participant : new ArrayList<>(participants.values())) {
            if (participant.player.isOnline() && participant.player.getWorld().equals(world)) {
                plugin.playSoundToPlayer(participant.player, path);
            }
        }
    }

    private Location calculateCenter(World targetWorld, WorldBorder targetBorder, List<Player> eligible) {
        Location borderCenter = targetBorder.getCenter();
        // Do not synchronously inspect or generate the border-center chunk during start.
        double y = targetWorld.getSpawnLocation().getY();
        if (targetWorld.isChunkLoaded(borderCenter.getBlockX() >> 4, borderCenter.getBlockZ() >> 4)) {
            Location safe = spawnLocator.findSafeAt(targetWorld, borderCenter.getBlockX(), borderCenter.getBlockZ());
            if (safe != null) y = safe.getY();
        }
        if (!Double.isFinite(y)
            || y <= targetWorld.getMinHeight()
            || y >= targetWorld.getMaxHeight() - 2) {
            y = targetWorld.getSpawnLocation().getY();
        }
        double highestPlayerY = eligible.stream()
            .filter(Player::isOnline)
            .mapToDouble(player -> player.getLocation().getY())
            .max()
            .orElse(y);
        y = Math.max(y, highestPlayerY) + CLOUD_HEIGHT_OFFSET;
        if (y >= targetWorld.getMaxHeight() - 2) y = targetWorld.getMaxHeight() - 3;
        return new Location(targetWorld, borderCenter.getX(), y, borderCenter.getZ());
    }

    private int randomDelay(int minimum, int maximum) {
        return ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
    }

    private static final class Participant {
        private final UUID uuid;
        private final Player player;
        private Location startLocation;
        private Location motionTarget;
        private Location heartAnchor;
        private Location heartStart;
        private Location riseStart;
        private Location burstStart;
        private Location burstEnd;
        private Location landingTarget;
        private Location landingHigh;
        private boolean gravity;
        private boolean allowFlight;
        private boolean flying;
        private float walkSpeed;
        private float flySpeed;
        private float fallDistance;
        private int heartDelay;
        private int playerDelay;
        private boolean heartCompleted;
        private boolean landSoundPlayed;
        private ItemDisplay heart;

        private Participant(UUID uuid, Player player) {
            this.uuid = uuid;
            this.player = player;
        }
    }

    private static final class BorderSnapshot {
        private final Location center;
        private final double size;
        private final double damageBuffer;
        private final double damageAmount;
        private final int warningDistance;
        private final int warningTime;

        private BorderSnapshot(
            Location center,
            double size,
            double damageBuffer,
            double damageAmount,
            int warningDistance,
            int warningTime
        ) {
            this.center = center;
            this.size = size;
            this.damageBuffer = damageBuffer;
            this.damageAmount = damageAmount;
            this.warningDistance = warningDistance;
            this.warningTime = warningTime;
        }

        private static BorderSnapshot capture(WorldBorder border) {
            return new BorderSnapshot(
                border.getCenter().clone(),
                border.getSize(),
                border.getDamageBuffer(),
                border.getDamageAmount(),
                border.getWarningDistance(),
                border.getWarningTime()
            );
        }

        private void restore(WorldBorder border) {
            border.setCenter(center.getX(), center.getZ());
            border.setSize(size);
            border.setDamageBuffer(damageBuffer);
            border.setDamageAmount(damageAmount);
            border.setWarningDistance(warningDistance);
            border.setWarningTime(warningTime);
        }
    }
}


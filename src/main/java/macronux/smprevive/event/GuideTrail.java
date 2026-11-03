package macronux.smprevive.event;

import macronux.smprevive.SMPRevive;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Sparse black wisps that leave each player and drift toward a point, shrinking until they vanish.
 * Dust particles ignore velocity, so every wisp is moved here each tick and drawn as one particle.
 */
public final class GuideTrail {
    private static final long PULSE_TICKS = 50L;
    private static final long MAX_TICKS = 20L * 60 * 10;
    /** Wisps released per player per pulse, and how far ahead of the player each one starts, in blocks. */
    private static final double[] START_AHEAD = {0.8, 2.0, 3.2};
    private static final double SPEED_PER_TICK = 0.2;
    private static final int LIFE_TICKS = 70;
    private static final float START_SIZE = 1.8f;
    private static final float END_SIZE = 0.2f;
    /** Players closer than this have arrived and release no more wisps. */
    private static final double ARRIVAL_DISTANCE = 4.0;

    private final SMPRevive plugin;
    private final List<Wisp> wisps = new ArrayList<>();
    private BukkitTask task;
    private World world;
    private Location target;
    private long elapsedTicks;

    private static final class Wisp {
        private final Player viewer;
        private final Vector position;
        private final Vector step;
        private int age;

        private Wisp(Player viewer, Vector position, Vector step) {
            this.viewer = viewer;
            this.position = position;
            this.step = step;
        }
    }

    public GuideTrail(SMPRevive plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return task != null;
    }

    /** Replaces any running trail and returns how many players in the world will see it. */
    public int start(World world, Location target) {
        stop();
        this.world = world;
        this.target = target.clone();
        this.elapsedTicks = 0;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        return world.getPlayers().size();
    }

    /** Returns whether a trail was running. */
    public boolean stop() {
        boolean wasActive = task != null;
        if (task != null) task.cancel();
        task = null;
        wisps.clear();
        world = null;
        target = null;
        return wasActive;
    }

    private void tick() {
        if (world == null || target == null || Bukkit.getWorld(world.getUID()) == null || elapsedTicks > MAX_TICKS) {
            stop();
            return;
        }
        if (elapsedTicks % PULSE_TICKS == 0) release();
        elapsedTicks++;
        advance();
    }

    private void release() {
        Vector goal = target.toVector();
        for (Player player : world.getPlayers()) {
            if (player.isDead()) continue;
            Vector from = player.getLocation().add(0.0, 1.0, 0.0).toVector();
            Vector toGoal = goal.clone().subtract(from);
            double distance = toGoal.length();
            if (distance < ARRIVAL_DISTANCE) continue;
            Vector direction = toGoal.multiply(1.0 / distance);
            Vector step = direction.clone().multiply(SPEED_PER_TICK);
            for (double ahead : START_AHEAD) {
                if (ahead >= distance) break;
                wisps.add(new Wisp(player, from.clone().add(direction.clone().multiply(ahead)), step));
            }
        }
    }

    private void advance() {
        Vector goal = target.toVector();
        for (Iterator<Wisp> it = wisps.iterator(); it.hasNext(); ) {
            Wisp wisp = it.next();
            Player viewer = wisp.viewer;
            if (!viewer.isOnline() || viewer.getWorld() != world || ++wisp.age > LIFE_TICKS
                || wisp.position.distanceSquared(goal) < 1.0) {
                it.remove();
                continue;
            }
            wisp.position.add(wisp.step);
            float size = START_SIZE + (END_SIZE - START_SIZE) * wisp.age / LIFE_TICKS;
            viewer.spawnParticle(Particle.DUST, wisp.position.getX(), wisp.position.getY(), wisp.position.getZ(),
                1, 0.0, 0.0, 0.0, 0.0, new Particle.DustOptions(Color.BLACK, size));
        }
    }
}

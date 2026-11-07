package macronux.smprevive.ritual;

import java.util.concurrent.ThreadLocalRandom;
import macronux.smprevive.SMPRevive;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * One run of the skull ritual: the skull rises out of the altar, hides inside a purple sphere, a nether star
 * leaves it, a storm closes in until lightning hits the skull, a heart is taken from the nearest player and
 * circles the star until both vanish into the sphere, and the skull becomes a wither skeleton skull.
 */
final class SkullRitual {
    private enum Phase { RISE, SPIN, SPHERE, STAR_OUT, STORM, CALM, HEART_LIFT, HEART_FLIGHT, APPROACH, SHRINK, FADE }

    private static final Color PURPLE = Color.fromRGB(150, 40, 200);
    private static final Color BLACK = Color.fromRGB(12, 12, 12);
    private static final Color WHITE = Color.WHITE;
    private static final Color RED = Color.fromRGB(255, 60, 80);

    private final RitualManager manager;
    private final SMPRevive plugin;
    private final String id;
    private final World world;
    private final Location altar;
    private final Vector base;
    private final Vector top;
    private Phase phase = Phase.RISE;
    private int phaseTick;
    private long age;
    private double yaw;
    private double sphereRotation;
    private double orbitAngle;
    private Vector starHome;
    private Vector heartStart;
    private ItemDisplay skull;
    private ItemDisplay star;
    private ItemDisplay heart;
    private boolean stormHeld;
    private boolean finished;
    private boolean aborted;

    SkullRitual(RitualManager manager, SMPRevive plugin, String id, Location altar) {
        this.manager = manager;
        this.plugin = plugin;
        this.id = id;
        this.altar = altar.clone();
        this.world = altar.getWorld();
        this.base = new Vector(altar.getBlockX() + 0.5, altar.getBlockY() + 1.3, altar.getBlockZ() + 0.5);
        this.top = base.clone().add(new Vector(0.0, RitualRules.RISE_HEIGHT, 0.0));
        this.skull = spawn(base, new ItemStack(Material.SKELETON_SKULL), RitualRules.SKULL_SCALE, false, RitualRules.SKULL_LIFT);
        play(base, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 0.6f);
        play(base, Sound.ENTITY_WITHER_AMBIENT, 0.8f, 0.5f);
    }

    String id() {
        return id;
    }

    Location altar() {
        return altar;
    }

    boolean isFinished() {
        return finished;
    }

    /** Advances one tick; returns true once the ritual is over. */
    boolean tick() {
        if (finished) return true;
        age++;
        phaseTick++;
        if (phase != Phase.FADE) manager.requestTime(world, phase == Phase.STORM);
        if (phase.ordinal() >= Phase.STAR_OUT.ordinal() && phase.ordinal() <= Phase.SHRINK.ordinal()) {
            drawSphere(RitualRules.SPHERE_POINTS, RitualRules.SPHERE_RADIUS, PURPLE, 1.5f);
        }
        switch (phase) {
            case RISE -> tickRise();
            case SPIN -> tickSpin();
            case SPHERE -> tickSphere();
            case STAR_OUT -> tickStarOut();
            case STORM -> tickStorm();
            case CALM -> tickCalm();
            case HEART_LIFT -> tickHeartLift();
            case HEART_FLIGHT -> tickHeartFlight();
            case APPROACH -> tickApproach();
            case SHRINK -> tickShrink();
            case FADE -> tickFade();
        }
        return finished;
    }

    /** Removes everything this run spawned and releases the weather; the persistent record stays untouched. */
    void cleanup() {
        finished = true;
        removeDisplay(skull);
        removeDisplay(star);
        removeDisplay(heart);
        skull = null;
        star = null;
        heart = null;
        releaseStorm();
    }

    private void advance(Phase next) {
        phase = next;
        phaseTick = 0;
    }

    private void tickRise() {
        double t = RitualRules.smoothstep(phaseTick / (double) RitualRules.RISE_TICKS);
        yaw += 5.0;
        move(skull, lerp(base, top, t), yaw);
        if (phaseTick % 3 == 0) {
            world.spawnParticle(Particle.SOUL, skull.getLocation(), 2, 0.2, 0.2, 0.2, 0.01);
        }
        if (phaseTick >= RitualRules.RISE_TICKS) advance(Phase.SPIN);
    }

    private void tickSpin() {
        yaw += 3.0;
        move(skull, top, yaw);
        if (phaseTick > 20) {
            world.spawnParticle(Particle.REVERSE_PORTAL, at(top), 4, 0.5, 0.5, 0.5, 0.06);
            world.spawnParticle(Particle.DUST, at(top), 2, 0.6, 0.6, 0.6, 0.0, new Particle.DustOptions(PURPLE, 1.2f));
        }
        if (phaseTick >= RitualRules.SPIN_TICKS) advance(Phase.SPHERE);
    }

    private void tickSphere() {
        yaw += 3.0;
        move(skull, top, yaw);
        double t = RitualRules.smoothstep(phaseTick / (double) RitualRules.SPHERE_TICKS);
        double radius = 0.4 + (RitualRules.SPHERE_RADIUS - 0.4) * t;
        drawSphere(24 + (int) ((RitualRules.SPHERE_POINTS - 24) * t), radius, PURPLE, 1.5f);
        world.spawnParticle(Particle.REVERSE_PORTAL, at(top), 3, 0.7, 0.7, 0.7, 0.05);
        if (phaseTick == 1) play(top, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 0.5f);
        if (phaseTick >= RitualRules.SPHERE_TICKS) {
            star = spawn(top, new ItemStack(Material.NETHER_STAR), 0.9f, true, 0.0f);
            orbitAngle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
            starHome = top.clone().add(new Vector(0.0, RitualRules.STAR_HEIGHT, 0.0));
            play(top, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, 0.6f);
            advance(Phase.STAR_OUT);
        }
    }

    private void tickStarOut() {
        yaw += 3.0;
        move(skull, top, yaw);
        double t = RitualRules.smoothstep(phaseTick / (double) RitualRules.STAR_TICKS);
        Vector position = lerp(top, starHome, t);
        move(star, position, 0.0);
        world.spawnParticle(Particle.END_ROD, at(position), 2, 0.05, 0.05, 0.05, 0.01);
        if (phaseTick >= RitualRules.STAR_TICKS) {
            if (plugin.getConfig().getBoolean("ritual.storm-weather", true)) {
                manager.acquireStorm(world);
                stormHeld = true;
            }
            advance(Phase.STORM);
        }
    }

    private void tickStorm() {
        yaw += 3.0;
        move(skull, top, yaw);
        move(star, bob(starHome, 1.0), 0.0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double expected = RitualRules.strikesPerTick(phaseTick, RitualRules.STORM_TICKS);
        int strikes = (int) expected + (random.nextDouble() < expected - Math.floor(expected) ? 1 : 0);
        double radius = RitualRules.strikeRadius(phaseTick, RitualRules.STORM_TICKS);
        for (int i = 0; i < Math.min(strikes, 3); i++) {
            double angle = random.nextDouble(Math.PI * 2.0);
            double distance = radius * (0.8 + 0.2 * random.nextDouble());
            strikeGround(top.getX() + Math.cos(angle) * distance, top.getZ() + Math.sin(angle) * distance);
        }
        if (phaseTick >= RitualRules.STORM_TICKS) {
            Location head = at(top);
            world.strikeLightningEffect(head);
            world.spawnParticle(Particle.END_ROD, head, 150, 0.3, 0.3, 0.3, 0.5);
            knockback(head, RitualRules.STRIKE_KNOCKBACK_RADIUS, 0.7, 0.35);
            releaseStorm();
            advance(Phase.CALM);
        }
    }

    private void tickCalm() {
        yaw += 2.0;
        move(skull, top, yaw);
        move(star, bob(starHome, 1.0), 0.0);
        if (phaseTick < RitualRules.CALM_TICKS) return;
        Player donor = manager.pickDonor(at(top));
        if (donor == null || !manager.takeHeart(id, donor)) {
            abort();
            return;
        }
        heartStart = donor.getLocation().toVector().add(new Vector(0.0, donor.getHeight() + 0.7, 0.0));
        heart = spawn(heartStart, plugin.getItemManager().createHeart(), 0.8f, true, 0.0f);
        play(heartStart, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 0.7f);
        advance(Phase.HEART_LIFT);
    }

    private void tickHeartLift() {
        yaw += 2.0;
        move(skull, top, yaw);
        move(star, bob(starHome, 1.0), 0.0);
        move(heart, heartStart.clone().add(new Vector(0.0, Math.sin(phaseTick * 0.2) * 0.1, 0.0)), 0.0);
        if (phaseTick % 10 == 0) world.spawnParticle(Particle.HEART, heart.getLocation(), 1, 0.2, 0.2, 0.2, 0.0);
        if (phaseTick >= RitualRules.HEART_LIFT_TICKS) advance(Phase.HEART_FLIGHT);
    }

    private void tickHeartFlight() {
        yaw += 2.0;
        move(skull, top, yaw);
        Vector starPosition = bob(starHome, 1.0);
        move(star, starPosition, 0.0);
        double t = RitualRules.smoothstep(phaseTick / (double) RitualRules.HEART_FLIGHT_TICKS);
        Vector position = lerp(heartStart, starPosition, t).add(new Vector(0.0, Math.sin(Math.PI * t) * 2.0, 0.0));
        move(heart, position, 0.0);
        world.spawnParticle(Particle.DUST, at(position), 2, 0.1, 0.1, 0.1, 0.0, new Particle.DustOptions(RED, 0.8f));
        if (phaseTick >= RitualRules.HEART_FLIGHT_TICKS) advance(Phase.APPROACH);
    }

    /** The star and the heart come down from above the sphere to its level and part to opposite sides. */
    private void tickApproach() {
        yaw += 2.0;
        move(skull, top, yaw);
        double u = RitualRules.smoothstep(phaseTick / (double) RitualRules.APPROACH_TICKS);
        orbitAngle += 0.06 * u;
        double wobble = 1.0 - u;
        double height = RitualRules.STAR_HEIGHT * wobble;
        double radius = RitualRules.ORBIT_START_RADIUS * u;
        Vector starPosition = orbit(orbitAngle, radius, height + Math.sin(age * 0.12) * 0.25 * wobble);
        Vector heartPosition = orbit(orbitAngle + Math.PI * u,
            radius * (1.0 + 0.3 * Math.sin(phaseTick * 0.5) * wobble), height + Math.sin(phaseTick * 0.35) * wobble);
        move(star, starPosition, 0.0);
        move(heart, heartPosition, 0.0);
        if (u > 0.3) thread(starPosition, heartPosition);
        if (phaseTick >= RitualRules.APPROACH_TICKS) advance(Phase.SHRINK);
    }

    private void tickShrink() {
        yaw += 4.0;
        move(skull, top, yaw);
        orbitAngle += RitualRules.orbitSpeed(phaseTick, RitualRules.SHRINK_TICKS);
        double radius = RitualRules.orbitRadius(phaseTick, RitualRules.SHRINK_TICKS);
        Vector starPosition = orbit(orbitAngle, radius, 0.0);
        Vector heartPosition = orbit(orbitAngle + Math.PI, radius, 0.0);
        move(star, starPosition, 0.0);
        move(heart, heartPosition, 0.0);
        thread(starPosition, heartPosition);
        if (phaseTick % 20 == 1) {
            float pitch = (float) (0.5 + RitualRules.progress(phaseTick, RitualRules.SHRINK_TICKS));
            play(top, Sound.BLOCK_BEACON_AMBIENT, 0.8f, pitch);
        }
        if (phaseTick >= RitualRules.SHRINK_TICKS) merge();
    }

    /** The star and the heart vanish into the sphere and the skull is replaced by the wither skeleton skull. */
    private void merge() {
        Location centre = at(top);
        removeDisplay(star);
        removeDisplay(heart);
        removeDisplay(skull);
        star = null;
        heart = null;
        skull = null;
        world.spawnParticle(Particle.END_ROD, centre, 90, 0.3, 0.3, 0.3, 0.35);
        play(top, Sound.ENTITY_WITHER_SPAWN, 1.2f, 0.7f);
        play(top, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5f, 0.5f);
        if (!manager.completeRitual(this, top)) {
            abort();
            return;
        }
        advance(Phase.FADE);
    }

    private void tickFade() {
        double t = phaseTick / (double) RitualRules.FADE_TICKS;
        int count = (int) (RitualRules.SPHERE_POINTS * Math.max(0.0, 1.0 - t));
        drawSphere(count, RitualRules.SPHERE_RADIUS * (1.0 + 0.4 * t), BLACK, 1.8f);
        if (phaseTick >= RitualRules.FADE_TICKS) finished = true;
    }

    /** Interrupts the ritual and hands the skull and any taken heart back. */
    private void abort() {
        if (aborted) return;
        aborted = true;
        cleanup();
        manager.abortRitual(this);
    }

    private void strikeGround(double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) return;
        Location strike = new Location(world, x, world.getHighestBlockYAt(blockX, blockZ) + 1, z);
        world.strikeLightningEffect(strike);
        knockback(strike, RitualRules.STRIKE_KNOCKBACK_RADIUS, 0.5, 0.3);
    }

    /** Lightning here is only an effect: nobody is hurt, but players close to it are thrown away from it. */
    private void knockback(Location origin, double radius, double power, double lift) {
        for (Player player : world.getNearbyPlayers(origin, radius)) {
            Vector away = player.getLocation().toVector().subtract(origin.toVector());
            double distance = away.length();
            away.setY(0.0);
            if (away.lengthSquared() < 0.01) {
                double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
                away = new Vector(Math.cos(angle), 0.0, Math.sin(angle));
            }
            away.normalize().multiply(power * (1.0 - 0.5 * Math.min(1.0, distance / radius)));
            away.setY(lift);
            player.setVelocity(away);
        }
    }

    private void drawSphere(int count, double radius, Color color, float size) {
        sphereRotation += 0.15;
        Particle.DustOptions options = new Particle.DustOptions(color, size);
        for (int i = 0; i < count; i++) {
            double[] p = RitualRules.spherePoint(i, Math.max(count, 1), sphereRotation);
            world.spawnParticle(Particle.DUST, top.getX() + p[0] * radius, top.getY() + p[1] * radius,
                top.getZ() + p[2] * radius, 1, 0.0, 0.0, 0.0, 0.0, options);
        }
    }

    private void thread(Vector from, Vector to) {
        Particle.DustOptions options = new Particle.DustOptions(WHITE, 0.6f);
        int points = 14;
        for (int i = 0; i <= points; i++) {
            double t = i / (double) points;
            Vector p = lerp(from, to, t);
            world.spawnParticle(Particle.DUST, p.getX(), p.getY(), p.getZ(), 1, 0.0, 0.0, 0.0, 0.0, options);
        }
    }

    private Vector orbit(double angle, double radius, double height) {
        return new Vector(top.getX() + Math.cos(angle) * radius, top.getY() + height, top.getZ() + Math.sin(angle) * radius);
    }

    private Vector bob(Vector home, double amount) {
        return home.clone().add(new Vector(0.0, Math.sin(age * 0.12) * 0.25 * amount, 0.0));
    }

    private static Vector lerp(Vector from, Vector to, double t) {
        return new Vector(from.getX() + (to.getX() - from.getX()) * t,
            from.getY() + (to.getY() - from.getY()) * t,
            from.getZ() + (to.getZ() - from.getZ()) * t);
    }

    private Location at(Vector vector) {
        return new Location(world, vector.getX(), vector.getY(), vector.getZ());
    }

    private void move(ItemDisplay display, Vector position, double yawDegrees) {
        if (display == null || !display.isValid()) return;
        Location target = at(position);
        target.setYaw((float) yawDegrees);
        display.teleport(target);
    }

    private ItemDisplay spawn(Vector position, ItemStack item, float scale, boolean facePlayers, float lift) {
        return world.spawn(at(position), ItemDisplay.class, display -> {
            display.setItemStack(item);
            display.setPersistent(false);
            display.setTeleportDuration(2);
            display.setViewRange(2.0f);
            if (facePlayers) display.setBillboard(Display.Billboard.CENTER);
            display.setTransformation(new Transformation(new Vector3f(0.0f, lift, 0.0f), new AxisAngle4f(),
                new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
    }

    private void play(Vector position, Sound sound, float volume, float pitch) {
        world.playSound(at(position), sound, SoundCategory.AMBIENT, volume, pitch);
    }

    private static void removeDisplay(ItemDisplay display) {
        if (display != null && display.isValid()) display.remove();
    }

    private void releaseStorm() {
        if (!stormHeld) return;
        stormHeld = false;
        manager.releaseStorm(world);
    }
}

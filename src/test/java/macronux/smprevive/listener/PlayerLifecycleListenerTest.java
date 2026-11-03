package macronux.smprevive.listener;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import junit.framework.TestCase;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import macronux.smprevive.DataManager;

public final class PlayerLifecycleListenerTest extends TestCase {
    private static final Location ORIGIN = new Location(null, 1, 64, 1);
    private static final Location DESTINATION = new Location(null, 20, 70, 20);
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public void testCancelledDeathDoesNotTouchLifecycleDependencies() {
        Player unusedPlayer = proxy(Player.class, (object, method, args) -> {
            throw new AssertionError("cancelled death reached player: " + method.getName());
        });
        PlayerDeathEvent event = new PlayerDeathEvent(unusedPlayer, null, new ArrayList<>(), 0, "cancelled");
        event.setCancelled(true);

        new PlayerLifecycleListener(null, null).onDeath(event);

        EventHandler handler;
        try {
            handler = PlayerLifecycleListener.class
                .getMethod("onDeath", PlayerDeathEvent.class)
                .getAnnotation(EventHandler.class);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
        assertNotNull(handler);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }

    public void testCancelledEventParticipationCleanupDoesNotTouchUninitializedManager() {
        Player unusedPlayer = proxy(Player.class, (object, method, args) -> {
            throw new AssertionError("cancelled death reached player: " + method.getName());
        });
        PlayerDeathEvent event = new PlayerDeathEvent(unusedPlayer, null, new ArrayList<>(), 0, "cancelled");
        event.setCancelled(true);
        macronux.smprevive.event.EventManager manager = allocateWithoutConstructor(
            macronux.smprevive.event.EventManager.class
        );

        manager.onPlayerDeath(event);

        EventHandler handler;
        try {
            handler = macronux.smprevive.event.EventManager.class
                .getMethod("onPlayerDeath", PlayerDeathEvent.class)
                .getAnnotation(EventHandler.class);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
        assertNotNull(handler);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }

    public void testPendingRevivalClearsDeadBeforeTeleportAndConsumesMarkerOnSuccess() {
        FakeDataManager state = fakeDataManager(true);
        PlayerFixture fixture = new PlayerFixture(state, TeleportBehavior.ALLOW);

        assertTrue(apply(fixture, state));

        assertFalse(state.dead);
        assertNull(state.pendingLocation);
        assertEquals(1, state.saves);
        assertEquals(1, fixture.teleports.size());
        assertLocation(DESTINATION, fixture.teleports.get(0));
    }

    public void testTeleportVetoRestoresPendingStateAndOrigin() {
        FakeDataManager state = fakeDataManager(true);
        PlayerFixture fixture = new PlayerFixture(state, TeleportBehavior.VETO);

        assertFalse(apply(fixture, state));

        assertRollback(state, fixture);
        assertEquals(GameMode.SPECTATOR, fixture.lastGameMode);

        PlayerFixture retry = new PlayerFixture(state, TeleportBehavior.ALLOW);
        assertTrue(apply(retry, state));
        assertFalse(state.isDead(PLAYER_ID));
        assertFalse(state.hasPendingRevivalSpawn(PLAYER_ID));
    }

    public void testTeleportExceptionRestoresPendingStateAndOrigin() {
        FakeDataManager state = fakeDataManager(true);
        PlayerFixture fixture = new PlayerFixture(state, TeleportBehavior.THROW);

        assertFalse(apply(fixture, state));

        assertRollback(state, fixture);
        assertEquals(GameMode.SPECTATOR, fixture.lastGameMode);
        assertFalse(fixture.warnings.isEmpty());
    }

    public void testPersistenceFailureRestoresPendingStateAndOrigin() {
        FakeDataManager state = fakeDataManager(false);
        PlayerFixture fixture = new PlayerFixture(state, TeleportBehavior.ALLOW);

        assertFalse(apply(fixture, state));

        assertRollback(state, fixture);
        assertEquals(2, state.saves);
        assertEquals(GameMode.SPECTATOR, fixture.lastGameMode);
    }

    private static boolean apply(PlayerFixture fixture, FakeDataManager state) {
        return PlayerLifecycleListener.applyPendingRevival(
            fixture.player,
            DESTINATION,
            GameMode.SURVIVAL,
            20.0,
            state,
            PLAYER_ID,
            fixture.warnings::add
        );
    }

    private static void assertRollback(FakeDataManager state, PlayerFixture fixture) {
        assertTrue(state.isDead(PLAYER_ID));
        assertTrue(state.hasPendingRevivalSpawn(PLAYER_ID));
        assertLocation(DESTINATION, state.pendingLocation);
        assertEquals(2, fixture.teleports.size());
        assertLocation(DESTINATION, fixture.teleports.get(0));
        assertLocation(ORIGIN, fixture.teleports.get(1));
    }

    private static void assertLocation(Location expected, Location actual) {
        assertNotNull(actual);
        assertEquals(expected.getX(), actual.getX());
        assertEquals(expected.getY(), actual.getY());
        assertEquals(expected.getZ(), actual.getZ());
    }

    private enum TeleportBehavior { ALLOW, VETO, THROW }

    private static final class FakeDataManager extends DataManager {
        private boolean dead;
        private Location pendingLocation;
        private boolean saveSucceeds;
        private int saves;

        private FakeDataManager() {
            super(null);
            throw new AssertionError("fixture must be allocated without its server-dependent constructor");
        }

        @Override public boolean isDead(UUID uuid) { return dead; }
        @Override public void setDead(UUID uuid, boolean dead) { this.dead = dead; }
        @Override public boolean hasPendingRevivalSpawn(UUID uuid) { return pendingLocation != null; }
        @Override public void clearRevivalSpawnLocation(UUID uuid) { pendingLocation = null; }
        @Override public void setRevivalSpawnLocation(UUID uuid, Location location) {
            pendingLocation = location == null ? null : location.clone();
        }
        @Override public boolean saveSafely() { saves++; return saveSucceeds; }
    }

    private static FakeDataManager fakeDataManager(boolean saveSucceeds) {
        FakeDataManager data = allocateWithoutConstructor(FakeDataManager.class);
        data.dead = true;
        data.pendingLocation = DESTINATION.clone();
        data.saveSucceeds = saveSucceeds;
        return data;
    }

    private static final class PlayerFixture {
        private final List<Location> teleports = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final TeleportBehavior behavior;
        private final FakeDataManager state;
        private GameMode lastGameMode;
        private Player player;

        private PlayerFixture(FakeDataManager state, TeleportBehavior behavior) {
            this.state = state;
            this.behavior = behavior;
            this.player = proxy(Player.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "getLocation": return ORIGIN.clone();
                    case "teleport":
                        Location destination = ((Location) args[0]).clone();
                        teleports.add(destination);
                        if (teleports.size() == 1) {
                            assertFalse("dead must be cleared before exit teleport", this.state.isDead(PLAYER_ID));
                            if (this.behavior == TeleportBehavior.THROW) throw new IllegalStateException("teleport failed");
                            return this.behavior != TeleportBehavior.VETO;
                        }
                        return true;
                    case "setGameMode": lastGameMode = (GameMode) args[0]; return null;
                    case "setMaxHealth":
                    case "setHealth":
                    case "setFoodLevel":
                    case "setSaturation":
                    case "setFallDistance": return null;
                    default: throw new AssertionError("Unexpected player call: " + method.getName());
                }
            });
        }

    }

    private static <T> T allocateWithoutConstructor(Class<T> type) {
        try {
            java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            return type.cast(unsafe.allocateInstance(type));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

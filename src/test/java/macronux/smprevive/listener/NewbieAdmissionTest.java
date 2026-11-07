package macronux.smprevive.listener;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import junit.framework.TestCase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;

public final class NewbieAdmissionTest extends TestCase {
    private static final UUID NEW_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public void testFirstTimePlayerSpawnsInSpaydisCoreLimboAndIsRememberedForJoin() throws Exception {
        World limbo = world("limbo", 3, 70, 4);
        World normalWorld = world("world", 10, 64, 10);
        Server server = proxy(Server.class, (object, method, args) -> {
            if (method.getName().equals("getWorld")) {
                assertEquals("spaydiscore", ((NamespacedKey) args[0]).getNamespace());
                assertEquals("limbo", ((NamespacedKey) args[0]).getKey());
                return limbo;
            }
            throw new AssertionError("Unexpected server call: " + method.getName());
        });
        PlayerLifecycleListener listener = new PlayerLifecycleListener(null, null, () -> true);
        AsyncPlayerSpawnLocationEvent event = new AsyncPlayerSpawnLocationEvent(
            connection(NEW_PLAYER), new Location(normalWorld, 10, 64, 10), true
        );

        withServer(server, () -> listener.onInitialSpawn(event));

        assertSpawn(event.getSpawnLocation(), limbo, 3, 70, 4);
        assertTrue(pendingFirstJoins(listener).contains(NEW_PLAYER));
    }

    public void testNewcomerKeepsNormalSpawnWhenNewbieEnteringIsOff() throws Exception {
        World limbo = world("limbo", 3, 70, 4);
        World normalWorld = world("world", 10, 64, 10);
        Server server = proxy(Server.class, (object, method, args) -> {
            throw new AssertionError("Limbo lookup with newbie entering off: " + method.getName());
        });
        PlayerLifecycleListener listener = new PlayerLifecycleListener(null, null, () -> false);
        AsyncPlayerSpawnLocationEvent event = new AsyncPlayerSpawnLocationEvent(
            connection(NEW_PLAYER), new Location(normalWorld, 10, 64, 10), true
        );

        withServer(server, () -> listener.onInitialSpawn(event));

        assertSpawn(event.getSpawnLocation(), normalWorld, 10, 64, 10);
        assertTrue(limbo != null);
    }

    public void testVeteranSpawnDoesNotInspectProfileOrChangeSpawn() throws Exception {
        PlayerConfigurationConnection unusedConnection = proxy(PlayerConfigurationConnection.class,
            (object, method, args) -> { throw new AssertionError("veteran connection inspected: " + method.getName()); });
        Server unusedServer = proxy(Server.class,
            (object, method, args) -> { throw new AssertionError("veteran caused server lookup: " + method.getName()); });
        World normalWorld = world("world", 10, 64, 10);
        Location originalSpawn = new Location(normalWorld, 10, 64, 10);
        PlayerLifecycleListener listener = new PlayerLifecycleListener(null, null);
        AsyncPlayerSpawnLocationEvent event = new AsyncPlayerSpawnLocationEvent(
            unusedConnection, originalSpawn, false
        );

        withServer(unusedServer, () -> listener.onInitialSpawn(event));

        assertSpawn(event.getSpawnLocation(), normalWorld, 10, 64, 10);
        assertTrue(pendingFirstJoins(listener).isEmpty());
    }

    private static PlayerConfigurationConnection connection(UUID uuid) {
        PlayerProfile profile = proxy(PlayerProfile.class, (object, method, args) -> {
            if (method.getName().equals("getId")) return uuid;
            throw new AssertionError("Unexpected profile call: " + method.getName());
        });
        return proxy(PlayerConfigurationConnection.class, (object, method, args) -> {
            if (method.getName().equals("getProfile")) return profile;
            throw new AssertionError("Unexpected connection call: " + method.getName());
        });
    }

    private static World world(String name, double x, double y, double z) {
        return proxy(World.class, (object, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getSpawnLocation" -> new Location((World) object, x, y, z);
            default -> objectMethod(object, method.getName(), args);
        });
    }

    private static void assertSpawn(Location actual, World world, double x, double y, double z) {
        assertSame(world, actual.getWorld());
        assertEquals(x, actual.getX());
        assertEquals(y, actual.getY());
        assertEquals(z, actual.getZ());
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> pendingFirstJoins(PlayerLifecycleListener listener) throws Exception {
        var field = PlayerLifecycleListener.class.getDeclaredField("pendingFirstJoins");
        field.setAccessible(true);
        return (Set<UUID>) field.get(listener);
    }

    private static void withServer(Server server, Runnable action) throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, server);
        try {
            action.run();
        } finally {
            field.set(null, previous);
        }
    }

    private static Object objectMethod(Object proxy, String method, Object[] args) {
        return switch (method) {
            case "toString" -> "test proxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError("Unexpected proxy call: " + method);
        };
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

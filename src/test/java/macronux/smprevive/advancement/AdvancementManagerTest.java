package macronux.smprevive.advancement;

import junit.framework.TestCase;
import macronux.smprevive.SMPRevive;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class AdvancementManagerTest extends TestCase {
    public void testNormalPlayerCanReceiveEveryGameplayAchievementOnce() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        SMPRevive plugin = (SMPRevive) unsafe.allocateInstance(SMPRevive.class);
        PluginDescriptionFile meta = new PluginDescriptionFile("SMPRevive", "test", SMPRevive.class.getName());
        for (String name : new String[]{"description", "pluginMeta"}) {
            var field = JavaPlugin.class.getDeclaredField(name);
            field.setAccessible(true); field.set(plugin, meta);
        }
        var loggerField = JavaPlugin.class.getDeclaredField("logger");
        loggerField.setAccessible(true); loggerField.set(plugin, Logger.getAnonymousLogger());
        Set<String> awarded = new HashSet<>();
        AtomicBoolean online = new AtomicBoolean(true);
        AtomicBoolean loaded = new AtomicBoolean(true);
        Server server = proxy(Server.class, (object, method, args) -> {
            if (!method.getName().equals("getAdvancement")) throw new AssertionError(method.getName());
            NamespacedKey key = (NamespacedKey) args[0];
            assertEquals("smprevive", key.getNamespace());
            if (!loaded.get()) return null;
            return proxy(Advancement.class, (advancement, called, values) -> {
                if (called.getName().equals("getKey")) return key;
                throw new AssertionError(called.getName());
            });
        });
        Player player = proxy(Player.class, (object, method, args) -> {
            if (method.getName().equals("isOnline")) return online.get();
            if (method.getName().equals("getAdvancementProgress")) {
                String id = ((Advancement) args[0]).getKey().getKey();
                return proxy(AdvancementProgress.class, (progress, called, values) -> switch (called.getName()) {
                    case "isDone" -> awarded.contains(id);
                    case "awardCriteria" -> {
                        assertEquals("unlock", values[0]);
                        assertTrue("completed achievements must not be awarded twice", awarded.add(id));
                        yield true;
                    }
                    default -> throw new AssertionError(called.getName());
                });
            }
            // Permission/OP checks would fail this test as unexpected calls.
            throw new AssertionError("Unexpected player call: " + method.getName());
        });
        var bukkitServer = Bukkit.class.getDeclaredField("server");
        bukkitServer.setAccessible(true);
        Object previous = bukkitServer.get(null);
        bukkitServer.set(null, server);
        try {
            AdvancementManager manager = new AdvancementManager(plugin);
            for (Achievement achievement : Achievement.values()) {
                assertTrue(manager.grant(player, achievement));
                assertTrue(manager.grant(player, achievement));
            }
            assertEquals(Achievement.values().length + 1, awarded.size());
            loaded.set(false);
            assertFalse(manager.grant(player, Achievement.FIRST_HEART));
            online.set(false);
            assertFalse(manager.grant(player, Achievement.FIRST_HEART));
        } finally {
            bukkitServer.set(null, previous);
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

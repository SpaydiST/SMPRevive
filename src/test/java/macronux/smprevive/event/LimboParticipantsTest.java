package macronux.smprevive.event;

import java.lang.reflect.Proxy;
import java.util.List;
import junit.framework.TestCase;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class LimboParticipantsTest extends TestCase {
    public void testLimboSpectatorsAreIncludedWithoutReadingRevivalOrNewbieFlags() {
        World limbo = world(), normal = world();
        Player trapped = player(limbo, false);
        Player newcomer = player(limbo, false);
        Player deathScreen = player(limbo, true);
        Player outside = player(normal, false);
        var selected = EventManager.limboParticipants(List.of(trapped, newcomer, deathScreen, outside), limbo);
        assertEquals(2, selected.size());
        assertSame(trapped, selected.get(0));
        assertSame(newcomer, selected.get(1));
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (object, method, args) -> { throw new AssertionError(method.getName()); });
    }

    private static Player player(World world, boolean deathScreen) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (object, method, args) -> switch (method.getName()) {
                case "getWorld" -> world;
                case "isDead" -> deathScreen;
                default -> throw new AssertionError("Admission must not filter flags/game mode: " + method.getName());
            });
    }
}

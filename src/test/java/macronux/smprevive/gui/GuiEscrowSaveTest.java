package macronux.smprevive.gui;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import junit.framework.TestCase;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

public final class GuiEscrowSaveTest extends TestCase {
    @Override
    protected void setUp() {
        GuiEscrow.useDataFileLocator(player -> null);
    }

    @Override
    protected void tearDown() {
        GuiEscrow.resetDataFileLocator();
    }

    public void testSilentlyFailedServerWriteIsReportedToTheCaller() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("smprevive-playerdata", ".dat");
        try {
            java.nio.file.Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 60_000L));
            GuiEscrow.useDataFileLocator(player -> file);
            // Paper logs and swallows I/O errors, so saveData() returns normally without touching the file.
            try {
                GuiEscrow.savePlayerData(player(false, false, new ArrayList<>(), null));
                fail("An unwritten playerdata file must not look like a successful save");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().startsWith("Server did not write player data"));
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    public void testServerWriteThatTouchesTheFileSucceeds() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("smprevive-playerdata", ".dat");
        try {
            java.nio.file.Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 60_000L));
            GuiEscrow.useDataFileLocator(player -> file);
            GuiEscrow.savePlayerData(player(false, false, new ArrayList<>(), file));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    public void testOrdinarySaveUsesOnlyServerApi() {
        List<String> calls = new ArrayList<>();
        GuiEscrow.savePlayerData(player(false, false, calls));
        assertEquals(List.of("save"), calls);
    }

    public void testAltarCursorRestoredEvenWhenServerSaveThrows() {
        List<String> calls = new ArrayList<>();
        try {
            GuiEscrow.savePlayerData(player(true, true, calls));
            fail("Server save failure must reach the recovery caller");
        } catch (IllegalStateException expected) {
            assertEquals("disk unavailable", expected.getMessage());
        }
        assertEquals(List.of("cursor", "save", "cursor"), calls);
    }

    private static Player player(boolean altar, boolean failSave, List<String> calls) {
        return player(altar, failSave, calls, null);
    }

    private static Player player(boolean altar, boolean failSave, List<String> calls, java.nio.file.Path touchedOnSave) {
        Inventory inventory = proxy(Inventory.class, (object, method, args) -> {
            if (method.getName().equals("getHolder")) return altar ? new MenuHolder(MenuHolder.Type.ALTAR) : null;
            throw new AssertionError(method.getName());
        });
        InventoryView view = proxy(InventoryView.class, (object, method, args) -> {
            if (method.getName().equals("getTopInventory")) return inventory;
            throw new AssertionError(method.getName());
        });
        return proxy(Player.class, (object, method, args) -> {
            switch (method.getName()) {
                case "getOpenInventory": return view;
                case "getUniqueId": return java.util.UUID.nameUUIDFromBytes(new byte[] {1});
                case "getItemOnCursor": return null;
                case "setItemOnCursor": calls.add("cursor"); return null;
                case "saveData":
                    calls.add("save");
                    if (failSave) throw new IllegalStateException("disk unavailable");
                    if (touchedOnSave != null) {
                        java.nio.file.Files.setLastModifiedTime(touchedOnSave,
                            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                    }
                    return null;
                default: throw new AssertionError("Unexpected save dependency: " + method.getName());
            }
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

package macronux.smprevive.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * The Toolkit plugin's TNT helmet, recognised by its public PDC marker {@code toolkit:tnthead}.
 * Toolkit owns the behaviour (it explodes when the wearer takes damage); this class only builds the same item.
 */
public final class TntHeadItem {
    public static final NamespacedKey MARKER = new NamespacedKey("toolkit", "tnthead");
    /** Items made before Toolkit renamed its marker. */
    private static final NamespacedKey LEGACY_MARKER = new NamespacedKey("toolkit", "tnt-hat");

    private TntHeadItem() {
    }

    public static ItemStack create() {
        ItemStack hat = new ItemStack(Material.TNT);
        hat.editMeta(meta -> {
            meta.getPersistentDataContainer().set(MARKER, PersistentDataType.BYTE, (byte) 1);
            meta.setMaxStackSize(1);
            meta.displayName(Component.text("ТНТ-шлем", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Взрывается при получении урона", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        });
        hat.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD).swappable(true).build());
        return hat;
    }

    public static boolean isTntHead(ItemStack item) {
        if (item == null || item.getType() != Material.TNT) return false;
        var meta = item.getItemMeta();
        if (meta == null) return false;
        var pdc = meta.getPersistentDataContainer();
        return pdc.has(MARKER, PersistentDataType.BYTE) || pdc.has(LEGACY_MARKER, PersistentDataType.BYTE);
    }
}

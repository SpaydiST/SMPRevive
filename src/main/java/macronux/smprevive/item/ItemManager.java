package macronux.smprevive.item;

import macronux.smprevive.SMPRevive;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

public class ItemManager {
    private final SMPRevive plugin;
    private final NamespacedKey headKey;
    private final NamespacedKey heartKey;
    private final NamespacedKey altarKey;
    private final NamespacedKey unbreakableAltarKey;
    private final NamespacedKey altarDisplayKey;
    private final NamespacedKey menuFillerKey;
    private ItemStack heartTemplate;
    private ItemStack altarTemplate;

    public void clearTemplates() { heartTemplate = null; altarTemplate = null; }

    public NamespacedKey altarRecipeKey() {
        return new NamespacedKey(plugin, "revival_altar");
    }

    /** A survival source for the same marked altar previously available only to admins. */
    public void registerAltarRecipe() {
        NamespacedKey key = altarRecipeKey();
        Bukkit.removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, createAltarItem());
        recipe.shape("CNC", "NSN", "CNC");
        recipe.setIngredient('C', Material.COPPER_INGOT);
        recipe.setIngredient('N', Material.NETHERITE_INGOT);
        recipe.setIngredient('S', Material.NETHER_STAR);
        if (!Bukkit.addRecipe(recipe)) plugin.getLogger().warning("Could not register revival altar recipe");
        for (Player player : Bukkit.getOnlinePlayers()) player.discoverRecipe(key);
    }

    public ItemManager(SMPRevive plugin) {
        this.plugin = plugin;
        this.headKey = new NamespacedKey(plugin, "head");
        this.heartKey = new NamespacedKey(plugin, "heart");
        this.altarKey = new NamespacedKey(plugin, "altar");
        this.unbreakableAltarKey = new NamespacedKey(plugin, "unbreakable_altar");
        this.altarDisplayKey = new NamespacedKey(plugin, "altar_display");
        this.menuFillerKey = new NamespacedKey(plugin, "menu_filler");
    }

    public ItemStack createHeart() {
        return createHeart(null);
    }

    public ItemStack createHeart(Player player) {
        if (heartTemplate != null) return heartTemplate.clone();
        ItemStack item = this.createCustomModelItem(this.plugin.getHeartMaterial(), "heart");
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.plugin.msg("heart.name"));
            meta.setLore(this.plugin.lore("heart.lore"));
            meta.getPersistentDataContainer().set(this.heartKey, PersistentDataType.INTEGER, 1);
            item.setItemMeta(meta);
        }
        heartTemplate = item.clone();
        return item;
    }

    public void makeItemPersistent(org.bukkit.entity.Item entity) {
        entity.setPersistent(true);
        entity.setInvulnerable(true);
        entity.setFireTicks(-20000);
        entity.setUnlimitedLifetime(true);
        entity.setWillAge(false);
        entity.setCanMobPickup(false);
    }

    public void makeHeartPersistent(org.bukkit.entity.Item entity) {
        makeItemPersistent(entity);
    }

    public boolean isHeart(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(this.heartKey, PersistentDataType.INTEGER);
    }

    public ItemStack createAltarItem() {
        return createAltarItem(null);
    }

    public ItemStack createAltarItem(Player player) {
        if (altarTemplate != null) return altarTemplate.clone();
        ItemStack item = this.createCustomModelItem(this.plugin.getAltarItemMaterial(), "altar");
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.plugin.msg("altar-item.name"));
            meta.setLore(this.plugin.lore("altar-item.lore"));
            meta.getPersistentDataContainer().set(this.altarKey, PersistentDataType.INTEGER, 1);
            item.setItemMeta(meta);
        }
        altarTemplate = item.clone();
        return item;
    }

    public boolean isAltarItem(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(this.altarKey, PersistentDataType.INTEGER);
    }

    public ItemStack createUnbreakableAltarItem() {
        ItemStack item = createAltarItem();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.plugin.msg("altar-unbreakable-item.name"));
            meta.setLore(this.plugin.lore("altar-unbreakable-item.lore"));
            meta.getPersistentDataContainer().set(this.unbreakableAltarKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isUnbreakableAltarItem(ItemStack item) {
        return isAltarItem(item)
            && item.getItemMeta().getPersistentDataContainer().has(this.unbreakableAltarKey, PersistentDataType.BYTE);
    }

    public void markAltarDisplay(ItemDisplay display) {
        display.getPersistentDataContainer().set(this.altarDisplayKey, PersistentDataType.BYTE, (byte) 1);
    }

    public boolean isAltarDisplay(ItemDisplay display) {
        return display != null && display.getPersistentDataContainer().has(this.altarDisplayKey, PersistentDataType.BYTE);
    }

    public NamespacedKey getAltarDisplayKey() {
        return this.altarDisplayKey;
    }

    public void markMenuFiller(ItemStack item) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(this.menuFillerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    public void unmarkMenuFiller(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().remove(this.menuFillerKey);
        item.setItemMeta(meta);
    }

    public boolean isMenuFiller(ItemStack item) {
        return item != null
            && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(this.menuFillerKey, PersistentDataType.BYTE);
    }

    private ItemStack createCustomModelItem(Material material, String model) {
        String itemKey = material.getKey().toString();
        try {
            return Bukkit.getItemFactory().createItemStack(
                itemKey + "[minecraft:custom_model_data={strings:[\"" + model + "\"]}]"
            );
        } catch (Exception ignored) {
            return new ItemStack(material, 1);
        }
    }

    public ItemStack createHead(OfflinePlayer player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.setPlayerProfile(player.getPlayerProfile());
            meta.getPersistentDataContainer().set(this.headKey, PersistentDataType.STRING, player.getUniqueId().toString());
            item.setItemMeta(meta);
        }
        return item;
    }

    public OfflinePlayer getHeadOwner(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        String stored = meta.getPersistentDataContainer().get(this.headKey, PersistentDataType.STRING);
        if (stored != null) {
            try {
                java.util.UUID uuid = java.util.UUID.fromString(stored);
                return this.plugin.getServer().getOfflinePlayer(uuid);
            } catch (IllegalArgumentException ignored) {}
        }
        
        if (meta instanceof SkullMeta) {
            SkullMeta skullMeta = (SkullMeta) meta;
            OfflinePlayer owner = skullMeta.getOwningPlayer();
            if (owner != null) return owner;
            
            var profile = skullMeta.getPlayerProfile();
            if (profile != null && profile.getId() != null) {
                return this.plugin.getServer().getOfflinePlayer(profile.getId());
            }
        }
        return null;
    }

    /** Physical protection depends only on material, never on an owner or delivery ID. */
    public boolean isPlayerHead(ItemStack item) {
        return item != null && item.getType() == Material.PLAYER_HEAD;
    }

    /** Read the already stored skin; do not resolve profiles or contact Mojang here. */
    public boolean hasHeadSkin(ItemStack item) {
        if (!isPlayerHead(item) || !(item.getItemMeta() instanceof SkullMeta skull)) return false;
        var profile = skull.getPlayerProfile();
        return profile != null && profile.getTextures().getSkin() != null;
    }

    /** Revival identity is separate from physical protection of all player heads. */
    public boolean isProtectedHead(ItemStack item) {
        return isPlayerHead(item) && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(headKey, PersistentDataType.STRING);
    }

    /** Includes packaging so a shulker/bundle cannot bypass dropped-item protection. */
    public boolean containsProtectedItem(ItemStack item) {
        return containsItem(item, 0, new int[]{256}, true);
    }

    public boolean containsPlayerHead(ItemStack item) {
        return containsItem(item, 0, new int[]{256}, false);
    }

    private boolean containsItem(ItemStack item, int depth, int[] budget, boolean includeHearts) {
        if (item == null || item.getType().isAir()) return false;
        if (isPlayerHead(item) || includeHearts && isHeart(item)) return true;
        // Quarantine an unexpectedly deep/large wrapper rather than silently declaring it safe.
        if (depth >= 8 || --budget[0] < 0) return true;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.BundleMeta bundle) {
            for (ItemStack nested : bundle.getItems()) if (containsItem(nested, depth + 1, budget, includeHearts)) return true;
        }
        if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta block
            && block.getBlockState() instanceof org.bukkit.block.Container container) {
            for (ItemStack nested : container.getInventory().getContents())
                if (containsItem(nested, depth + 1, budget, includeHearts)) return true;
        }
        return false;
    }
}

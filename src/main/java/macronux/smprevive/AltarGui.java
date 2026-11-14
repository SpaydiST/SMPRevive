package macronux.smprevive;

import macronux.smprevive.gui.AltarDisplayManager;
import macronux.smprevive.advancement.Achievement;
import macronux.smprevive.advancement.AdvancementManager;
import macronux.smprevive.gui.GuiEscrow;
import macronux.smprevive.gui.MenuHolder;
import macronux.smprevive.recovery.RecoveryJournal;
import macronux.smprevive.rules.ReviveRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Owns altar inventory flow and the payment-to-revival transaction. */
public class AltarGui {
    private final SMPRevive plugin;
    private final AltarDisplayManager altarDisplayManager;
    // Centered 3x3 altar: eight payment slots surround the head (Bukkit slots are zero-based).
    private final int[] grid = new int[]{12, 13, 14, 21, 23, 30, 31, 32};
    private final int headSlot = 22;
    private final int reviveBtn = 49;
    private final Map<UUID, PendingRevival> pendingByTarget = new HashMap<>();
    private final Map<UUID, PendingRevival> pendingByReviver = new HashMap<>();
    private final Map<UUID, LimboArrival> limboArrivals = new HashMap<>();
    private ItemStack invisibleTemplate;

    public AltarGui(SMPRevive plugin) {
        this.plugin = plugin;
        // Display entities are rebuilt from registered altar blocks; the revival transaction owns no display state.
        this.altarDisplayManager = new AltarDisplayManager(plugin);
    }

    public boolean hasPendingRevival(UUID target) { return pendingByTarget.containsKey(target); }

    public ItemStack createInvisibleItem() {
        if (invisibleTemplate != null) return invisibleTemplate.clone();
        ItemStack item;
        try {
            item = Bukkit.getItemFactory().createItemStack("minecraft:stick[minecraft:custom_model_data={strings:[\"invisible\"]}]");
        } catch (Exception e) {
            item = new ItemStack(Material.STICK, 1);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setHideTooltip(true);
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        plugin.getItemManager().markMenuFiller(item);
        invisibleTemplate = item.clone();
        return item;
    }

    private boolean isInvisibleItem(ItemStack item) {
        return plugin.getItemManager().isMenuFiller(item);
    }

    public void open(Player player, Location altarLoc) {
        if (player == null || !player.isOnline() || altarLoc == null || plugin.isMechanicsDisabled(altarLoc)) return;
        if (!ReviveRules.allowsCustomAltarAction(player.getGameMode().name())) return;
        if (!plugin.getDataManager().isAltar(altarLoc)) return;
        if (pendingByReviver.containsKey(player.getUniqueId())) return;
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder current
            && current.getType() == MenuHolder.Type.ALTAR) player.closeInventory();
        if (!GuiEscrow.restoreAndSave(plugin, player)) {
            sendError(player, "altar-error.transaction");
            return;
        }

        MenuHolder holder = new MenuHolder(MenuHolder.Type.ALTAR, altarLoc);
        String configTitle = plugin.getConfig().getString("altar-title");
        String localizedTitle = plugin.msg(player, "altar.title");
        String title = configTitle != null && !configTitle.isEmpty() ? configTitle : localizedTitle;
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        ItemStack invStick = createInvisibleItem();
        for (int i = 0; i < 54; ++i) {
            inv.setItem(i, invStick.clone());
        }
        for (int slot : grid) {
            inv.setItem(slot, null);
        }
        inv.setItem(headSlot, null);

        ItemStack reviveBtnItem = createInvisibleItem();
        ItemMeta reviveMeta = reviveBtnItem.getItemMeta();
        if (reviveMeta != null) {
            reviveMeta.setDisplayName(plugin.msg(player, "altar.button-name"));
            reviveMeta.setLore(plugin.lore(player, "altar.button-lore"));
            reviveMeta.setHideTooltip(false);
            reviveBtnItem.setItemMeta(reviveMeta);
        }
        plugin.getItemManager().unmarkMenuFiller(reviveBtnItem);
        inv.setItem(reviveBtn, reviveBtnItem);

        player.openInventory(inv);
        plugin.playSound(player.getLocation(), "altar.open");
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof MenuHolder holder) || holder.getType() != MenuHolder.Type.ALTAR) return;
        if (holder.isRevivalStarted()) {
            event.setCancelled(true);
            return;
        }

        int raw = event.getRawSlot();
        // Default Bukkit mutations happen after the event; commit supported moves ourselves before acknowledging them.
        event.setCancelled(true);
        if (raw < 0) return;
        if (raw == reviveBtn) {
            event.setCancelled(true);
            tryRevive(player, inventory, holder);
            return;
        }
        if (raw < inventory.getSize() && !isGridSlot(raw) && raw != headSlot) return;
        transferClick(player, inventory, event);
    }

    public void handleDrag(InventoryDragEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof MenuHolder holder) || holder.getType() != MenuHolder.Type.ALTAR) return;
        // All moves use the click transaction so cursor and GUI ownership have a single saved snapshot.
        event.setCancelled(true);
    }

    public void handleClose(Player player, Inventory inventory) {
        if (player == null || inventory == null) return;
        if (!(inventory.getHolder() instanceof MenuHolder holder)
            || holder.getType() != MenuHolder.Type.ALTAR
            || !holder.markContentsReturned()) return;
        List<ItemStack> returned = ownedContents(player, inventory);
        // Persist GUI ownership together with the inventory before returning any items.
        GuiEscrow.checkpoint(plugin, player, returned);
        for (int slot : grid) {
            inventory.setItem(slot, null);
        }
        inventory.setItem(headSlot, null);
        player.setItemOnCursor(null);
        try {
            GuiEscrow.savePlayerData(player);
            GuiEscrow.restoreAndSave(plugin, player);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not persist closed altar contents: " + exception.getMessage());
        }
    }

    private List<ItemStack> editableContents(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot : grid) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !isInvisibleItem(item)) items.add(item.clone());
        }
        ItemStack head = inventory.getItem(headSlot);
        if (head != null && !isInvisibleItem(head)) items.add(head.clone());
        return items;
    }

    private List<ItemStack> ownedContents(Player player, Inventory inventory) {
        List<ItemStack> items = editableContents(inventory);
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) items.add(cursor.clone());
        return items;
    }

    private void savePlayerWithoutCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        player.setItemOnCursor(null);
        try { GuiEscrow.savePlayerData(player); }
        finally { player.setItemOnCursor(cursor); }
    }

    private ItemStack[] snapshot(ItemStack[] contents) {
        ItemStack[] result = contents.clone();
        for (int i = 0; i < result.length; i++) if (result[i] != null) result[i] = result[i].clone();
        return result;
    }

    private ItemStack nonEmpty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() < 1 ? null : item.clone(); }

    private void transferClick(Player player, Inventory top, InventoryClickEvent event) {
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT && click != ClickType.SHIFT_LEFT
            && click != ClickType.SHIFT_RIGHT && click != ClickType.NUMBER_KEY && click != ClickType.SWAP_OFFHAND) return;
        int raw = event.getRawSlot();
        boolean inTop = raw < top.getSize();
        int slot = inTop ? raw : event.getSlot();
        if (!inTop && (slot < 0 || slot >= player.getInventory().getSize())) return;
        ItemStack[] beforeTop = snapshot(top.getContents());
        ItemStack[] beforePlayer = snapshot(player.getInventory().getContents());
        ItemStack beforeCursor = nonEmpty(player.getItemOnCursor());
        List<ItemStack> beforeEscrow = GuiEscrow.read(plugin, player);
        try {
            Inventory source = inTop ? top : player.getInventory();
            ItemStack current = nonEmpty(source.getItem(slot));
            ItemStack cursor = nonEmpty(player.getItemOnCursor());
            if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) {
                int other = click == ClickType.SWAP_OFFHAND ? 40 : event.getHotbarButton();
                if (other < 0 || other >= player.getInventory().getSize() || (!inTop && slot == other)) return;
                source.setItem(slot, nonEmpty(player.getInventory().getItem(other)));
                player.getInventory().setItem(other, current);
            } else if (event.isShiftClick()) {
                if (current == null) return;
                if (inTop) {
                    int left = player.getInventory().addItem(current.clone()).values().stream().mapToInt(ItemStack::getAmount).sum();
                    current.setAmount(left); source.setItem(slot, nonEmpty(current));
                } else {
                    boolean headLike = current.getType() == Material.PLAYER_HEAD || current.getType() == Material.SKELETON_SKULL;
                    int[] targets = headLike ? new int[]{headSlot} : grid;
                    for (int target : targets) {
                        ItemStack existing = nonEmpty(top.getItem(target));
                        if (existing != null && !existing.isSimilar(current)) continue;
                        int available = Math.min(current.getMaxStackSize(),top.getMaxStackSize()) - (existing == null ? 0 : existing.getAmount());
                        int moved = Math.min(current.getAmount(),Math.max(0,available));
                        if (moved == 0) continue;
                        ItemStack next = existing == null ? current.clone() : existing;
                        next.setAmount((existing == null ? 0 : existing.getAmount()) + moved);
                        top.setItem(target,next); current.setAmount(current.getAmount()-moved);
                        if (current.getAmount()==0) break;
                    }
                    source.setItem(slot,nonEmpty(current));
                }
            } else if (cursor == null) {
                if (current == null) return;
                int moved = click == ClickType.RIGHT ? (current.getAmount()+1)/2 : current.getAmount();
                cursor = current.clone(); cursor.setAmount(moved);
                current.setAmount(current.getAmount()-moved); source.setItem(slot,nonEmpty(current));
                player.setItemOnCursor(cursor);
            } else if (current == null || current.isSimilar(cursor)) {
                int available = Math.min(cursor.getMaxStackSize(),source.getMaxStackSize()) - (current==null ? 0 : current.getAmount());
                int moved = Math.min(cursor.getAmount(), Math.max(0,available));
                if (click == ClickType.RIGHT) moved = Math.min(1,moved);
                ItemStack next = current == null ? cursor.clone() : current;
                next.setAmount((current==null ? 0 : current.getAmount())+moved);
                source.setItem(slot,nonEmpty(next)); cursor.setAmount(cursor.getAmount()-moved);
                player.setItemOnCursor(nonEmpty(cursor));
            } else {
                source.setItem(slot,cursor); player.setItemOnCursor(current);
            }
            GuiEscrow.checkpoint(plugin,player,ownedContents(player,top));
            savePlayerWithoutCursor(player);
        } catch (RuntimeException error) {
            top.setContents(beforeTop); player.getInventory().setContents(beforePlayer); player.setItemOnCursor(beforeCursor);
            GuiEscrow.checkpoint(plugin,player,beforeEscrow);
            try { savePlayerWithoutCursor(player); }
            catch (RuntimeException ignored) { plugin.getLogger().warning("Altar inventory rollback could not be saved"); }
            sendError(player,"altar-error.transaction");
        }
        player.updateInventory();
    }

    /** A skeleton skull on the altar starts the wither skeleton ritual instead of a revival. */
    private void startSkullRitual(Player player, Inventory inventory, Location altar, ItemStack skull) {
        if (!plugin.getRitualManager().begin(player, altar)) return;
        ItemStack[] beforeTop = snapshot(inventory.getContents());
        List<ItemStack> beforeEscrow = GuiEscrow.read(plugin, player);
        try {
            ItemStack rest = skull.clone();
            rest.setAmount(skull.getAmount() - 1);
            inventory.setItem(headSlot, nonEmpty(rest));
            GuiEscrow.checkpoint(plugin, player, ownedContents(player, inventory));
            savePlayerWithoutCursor(player);
        } catch (RuntimeException error) {
            inventory.setContents(beforeTop);
            GuiEscrow.checkpoint(plugin, player, beforeEscrow);
            plugin.getRitualManager().cancel(altar);
            sendError(player, "altar-error.transaction");
            return;
        }
        player.closeInventory();
    }

    private void tryRevive(Player reviver, Inventory inventory, MenuHolder holder) {
        if (!ReviveRules.allowsCustomAltarAction(reviver.getGameMode().name())) return;
        if (plugin.isMechanicsDisabled(reviver.getLocation())) return;
        Location altarLocation = holder.getAltarLocation();
        if (!plugin.getDataManager().isAltar(altarLocation)) {
            sendError(reviver, "altar-error.no-safe-location");
            return;
        }
        if (pendingByReviver.containsKey(reviver.getUniqueId())) {
            sendError(reviver, "altar-error.revival-pending");
            return;
        }

        ItemStack headItem = inventory.getItem(headSlot);
        if (headItem != null && headItem.getType() == Material.SKELETON_SKULL) {
            startSkullRitual(reviver, inventory, altarLocation, headItem);
            return;
        }
        OfflinePlayer target = plugin.getItemManager().getHeadOwner(headItem);
        if (target == null) {
            sendError(reviver, "altar-error.head-required");
            return;
        }

        UUID targetId = target.getUniqueId();
        if (pendingByTarget.containsKey(targetId)) {
            sendError(reviver, "altar-error.revival-pending");
            return;
        }

        DataManager data = plugin.getDataManager();
        if (!data.isDead(targetId)) {
            sendError(reviver, "altar-error.not-dead", "%player%", nameOf(target));
            return;
        }

        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null && plugin.isMechanicsDisabled(onlineTarget.getWorld())) {
            sendError(reviver, "altar-error.not-dead", "%player%", nameOf(target));
            return;
        }

        String tokenMaterialName = data.getTokenMaterial(targetId);
        Material requiredMaterial = tokenMaterialName == null ? null : Material.matchMaterial(tokenMaterialName);
        if (requiredMaterial == null || requiredMaterial == Material.AIR || !requiredMaterial.isItem()) {
            sendError(reviver, "altar-error.no-token-material", "%player%", nameOf(target));
            return;
        }

        List<ReviveRules.TokenStack> tokenStacks = new ArrayList<>();
        for (int slot : grid) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || isInvisibleItem(item)) continue;
            if (plugin.getItemManager().containsProtectedItem(item)) {
                sendError(reviver, "altar-error.wrong-tokens", "%required-item%", plugin.getMaterialName(reviver, requiredMaterial.name()));
                return;
            }
            tokenStacks.add(new ReviveRules.TokenStack(slot, item.getType().name(), item.getAmount()));
        }

        int needed = plugin.getTokensRequired(targetId);
        ReviveRules.TokenSummary summary = ReviveRules.inspectAndConsume(tokenStacks, requiredMaterial.name(), needed);
        String localizedMaterial = plugin.getMaterialName(reviver, requiredMaterial.name());
        if (summary.wrongStackCount() > 0) {
            sendError(reviver, "altar-error.wrong-tokens", "%required-item%", localizedMaterial);
            return;
        }
        if (summary.matchingCount() < needed) {
            String message = plugin.msg(reviver, "altar-error.tokens-required")
                .replace("%required%", String.valueOf(needed))
                .replace("%item%", localizedMaterial)
                .replace("%count%", String.valueOf(summary.matchingCount()))
                .replace("%player%", nameOf(target));
            reviver.sendMessage(message);
            plugin.playSoundToPlayer(reviver, "altar.error");
            return;
        }

        Location spawnLocation = calculateSpawnLocation(altarLocation);
        if (spawnLocation == null) {
            sendError(reviver, "altar-error.no-safe-location");
            return;
        }

        List<RecoveryJournal.Payment> payments = new ArrayList<>();
        if (headItem != null && !isInvisibleItem(headItem)) {
            ItemStack headPayment = headItem.clone();
            headPayment.setAmount(1);
            payments.add(new RecoveryJournal.Payment("GUI", null, headPayment));
        }
        for (ReviveRules.TokenStack consumed : summary.consumed()) {
            ItemStack source = inventory.getItem(consumed.slot());
            if (source == null || source.getAmount() < consumed.amount()) {
                sendError(reviver, "altar-error.tokens-required");
                return;
            }
            ItemStack refund = source.clone();
            refund.setAmount(consumed.amount());
            payments.add(new RecoveryJournal.Payment("GUI", null, refund));
        }

        try {
            GuiEscrow.checkpoint(plugin, reviver, ownedContents(reviver, inventory));
            savePlayerWithoutCursor(reviver);
        } catch (RuntimeException exception) {
            reviver.closeInventory();
            handleClose(reviver, inventory);
            sendError(reviver, "altar-error.transaction");
            return;
        }

        RecoveryJournal.Operation journalOperation = plugin.getRecoveryJournal() == null ? null : plugin.getRecoveryJournal().begin(
            RecoveryJournal.Type.ALTAR,
            reviver.getUniqueId(),
            targetId,
            data.getHearts(targetId),
            true,
            altarLocation,
            reviver.getLocation(),
            payments
        );
        if (plugin.getRecoveryJournal() != null && journalOperation == null) {
            reviver.closeInventory();
            handleClose(reviver, inventory);
            sendError(reviver, "altar-error.transaction");
            return;
        }

        PendingRevival operation = new PendingRevival(
            target,
            reviver,
            altarLocation,
            spawnLocation,
            reviver.getLocation(),
            journalOperation,
            false
        );
        for (RecoveryJournal.Payment payment : payments) {
            operation.refundItems.add(payment.refundItem());
        }

        for (ReviveRules.TokenStack consumed : summary.consumed()) {
            ItemStack source = inventory.getItem(consumed.slot());
            int remaining = source.getAmount() - consumed.amount();
            if (remaining > 0) {
                source.setAmount(remaining);
                inventory.setItem(consumed.slot(), source);
            } else {
                inventory.setItem(consumed.slot(), null);
            }
        }
        inventory.setItem(headSlot, null);
        int remainingHeads = headItem.getAmount() - 1;
        if (remainingHeads > 0) {
            ItemStack headRemainder = headItem.clone();
            headRemainder.setAmount(remainingHeads);
            inventory.setItem(headSlot, headRemainder);
        }
        GuiEscrow.checkpoint(plugin, reviver, ownedContents(reviver, inventory));
        ItemStack savedCursor = reviver.getItemOnCursor();
        boolean consumedSaved = true;
        reviver.setItemOnCursor(null);
        try {
            if (plugin.getRecoveryJournal() != null) consumedSaved = plugin.getRecoveryJournal().markConsumed(journalOperation, reviver);
        } finally { reviver.setItemOnCursor(savedCursor); }
        if (!consumedSaved) {
            plugin.getRecoveryJournal().abort(journalOperation, false, reviver, reviver.getLocation());
            reviver.closeInventory();
            handleClose(reviver, inventory);
            sendError(reviver, "altar-error.transaction");
            return;
        }
        holder.setRevivalStarted(true);
        pendingByTarget.put(targetId, operation);
        pendingByReviver.put(reviver.getUniqueId(), operation);

        reviver.closeInventory();
        // Some integrations do not fire InventoryCloseEvent synchronously.
        // Return only the unconsumed remainder explicitly; consumed payment
        // slots are already empty and are owned by the recovery journal.
        handleClose(reviver, inventory);
        startRevivalAnimation(operation);
    }

    private Location calculateSpawnLocation(Location altarLocation) {
        return calculateLocalSpawnLocation(altarLocation);
    }

    private Location calculateLocalSpawnLocation(Location altarLocation) {
        if (altarLocation == null || altarLocation.getWorld() == null) return null;
        World world = altarLocation.getWorld();
        int radius = plugin.getSpawnRadius();
        int attempts = Math.max(32, Math.min(256, (radius * 2 + 1) * (radius * 2 + 1)));

        for (int attempt = 0; attempt < attempts; attempt++) {
            int x = altarLocation.getBlockX() + (int) (Math.random() * (radius * 2 + 1)) - radius;
            int z = altarLocation.getBlockZ() + (int) (Math.random() * (radius * 2 + 1)) - radius;
            Location safe = findSafeAt(world, x, z, altarLocation.getBlockY());
            if (safe != null) return safe;
        }
        return findSafeAt(world, altarLocation.getBlockX(), altarLocation.getBlockZ(), altarLocation.getBlockY());
    }

    private Location findSafeAt(World world, int x, int z, int altarY) {
        // Without a sky the highest block is the roof (Nether bedrock), so search around the altar instead.
        int top = world.hasCeiling() ? altarY + 4 : world.getHighestBlockYAt(x, z);
        int highest = Math.min(top, world.getMaxHeight() - 3);
        int lowest = Math.max(world.getMinHeight() + 1, highest - 8);
        for (int y = highest; y >= lowest; y--) {
            if (isSafeSpawn(world, x, y, z)) {
                return new Location(world, x + 0.5, y + 1.0, z + 0.5);
            }
        }
        return null;
    }

    private boolean isSafeSpawn(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) return false;
        Block ground = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);
        Material groundType = ground.getType();
        Location candidate = new Location(world, x + 0.5, y + 1.0, z + 0.5);
        return world.getWorldBorder().isInside(candidate)
            && groundType.isSolid()
            && !ground.isLiquid()
            && !Tag.LEAVES.isTagged(groundType)
            && !ReviveRules.isDangerousSpawnMaterial(groundType.name())
            && feet.isPassable()
            && head.isPassable()
            && !feet.isLiquid()
            && !head.isLiquid()
            && !Tag.LEAVES.isTagged(feet.getType())
            && !Tag.LEAVES.isTagged(head.getType())
            && !ReviveRules.isDangerousSpawnMaterial(feet.getType().name())
            && !ReviveRules.isDangerousSpawnMaterial(head.getType().name());
    }

    public boolean isSafeSpawnLocation(Location candidate) {
        if (candidate == null || candidate.getWorld() == null) return false;
        return isSafeSpawn(
            candidate.getWorld(),
            candidate.getBlockX(),
            candidate.getBlockY() - 1,
            candidate.getBlockZ()
        );
    }

    private void startRevivalAnimation(PendingRevival operation) {
        Location start = operation.altarLocation.clone().add(0.5, 1.0, 0.5);
        try {
            ItemStack headItem = new ItemStack(Material.PLAYER_HEAD, 1);
            SkullMeta skullMeta = (SkullMeta) headItem.getItemMeta();
            if (skullMeta == null) throw new IllegalStateException("PLAYER_HEAD meta unavailable");
            skullMeta.setPlayerProfile(operation.target.getPlayerProfile());
            headItem.setItemMeta(skullMeta);

            ItemDisplay display = start.getWorld().spawn(start, ItemDisplay.class);
            display.setItemStack(headItem);
            display.setPersistent(false);
            display.setViewRange(10.0f);
            operation.display = display;

            plugin.playSound(start, "altar.start.beacon");
            plugin.playSound(start, "altar.start.dragon");
            plugin.playSoundToPlayer(operation.reviver, "altar.start.beacon");
            plugin.playSoundToPlayer(operation.reviver, "altar.start.dragon");

            Vector3f spawnOffset = new Vector3f(
                (float) (operation.spawnLocation.getX() - start.getX()),
                (float) (operation.spawnLocation.getY() - start.getY()),
                (float) (operation.spawnLocation.getZ() - start.getZ())
            );

            operation.task = new BukkitRunnable() {
                int tick;
                final int phaseOne = 50;
                final int phaseTwo = 15;

                @Override
                public void run() {
                    if (!isActive(operation)
                        || !plugin.getDataManager().isDead(operation.target.getUniqueId())
                        || plugin.isMechanicsDisabled(operation.altarLocation)
                        || !isSafeSpawnLocation(operation.spawnLocation)
                        || (operation.journalOperation != null && !plugin.getDataManager().isAltar(operation.altarLocation))
                        || display.isDead()) {
                        cancel();
                        cancelPending(operation, true);
                        return;
                    }
                    tick++;
                    if (tick <= phaseOne) {
                        float raw = (float) tick / phaseOne;
                        float t = 0.5f - 0.5f * (float) Math.cos(Math.PI * raw);
                        float y = 1.0f + t * 4.0f;
                        float angle = (float) (t * Math.PI * 2.0 * 1.5);
                        display.setTransformation(new Transformation(
                            new Vector3f(0, y, 0),
                            new Quaternionf(0, (float) Math.sin(angle), 0, (float) Math.cos(angle)),
                            new Vector3f(1, 1, 1),
                            new Quaternionf()
                        ));
                        if (tick % 4 == 0) {
                            Location particle = start.clone().add(0, y + 1.0, 0);
                            start.getWorld().spawnParticle(Particle.ENCHANT, particle, 5, 0.3, 0.3, 0.3, 0.1);
                            plugin.playSound(particle, "altar.phase1.enchant");
                            plugin.playSoundToPlayer(operation.reviver, "altar.phase1.enchant");
                        }
                        if (tick == 1) {
                            plugin.playSound(start, "altar.phase1.xp_pickup");
                            plugin.playSoundToPlayer(operation.reviver, "altar.phase1.xp_pickup");
                        }
                    } else if (tick <= phaseOne + phaseTwo) {
                        float t = (float) (tick - phaseOne) / phaseTwo;
                        float eased = t * t;
                        float arc = (float) Math.sin(t * Math.PI) * 3.0f;
                        float x = spawnOffset.x * eased;
                        float y = 4.0f + (spawnOffset.y + 1.0f - 4.0f) * eased + arc;
                        float z = spawnOffset.z * eased;
                        float angle = (float) (Math.PI * 2.0 * 1.5 + eased * Math.PI);
                        float scale = 1.0f - eased * 0.5f;
                        display.setTransformation(new Transformation(
                            new Vector3f(x, y, z),
                            new Quaternionf(0, (float) Math.sin(angle), 0, (float) Math.cos(angle)),
                            new Vector3f(scale, scale, scale),
                            new Quaternionf()
                        ));
                        Location particle = display.getLocation().clone().add(0, 1.0, 0);
                        display.getWorld().spawnParticle(Particle.ENCHANT, particle, 8, 0.4, 0.4, 0.4, 0.1);
                        if (tick % 3 == 0) {
                            plugin.playSound(particle, "altar.phase2.trident");
                            plugin.playSoundToPlayer(operation.reviver, "altar.phase2.trident");
                        }
                    } else {
                        cancel();
                        completeRevival(operation);
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Failed to start altar revival animation: " + ex.getMessage());
            cancelPending(operation, true);
            sendError(operation.reviver, "altar-error.no-safe-location");
        }
    }

    private boolean completeRevival(PendingRevival operation) {
        if (!isActive(operation)) return false;
        DataManager data = plugin.getDataManager();
        if (!data.isDead(operation.target.getUniqueId())
            || !isSafeSpawnLocation(operation.spawnLocation)
            || plugin.isMechanicsDisabled(operation.spawnLocation)
            || (operation.journalOperation != null && !data.isAltar(operation.altarLocation))) {
            if (!operation.external && !isSafeSpawnLocation(operation.spawnLocation)) {
                sendError(operation.reviver, "altar-error.no-safe-location");
            }
            cancelPending(operation, true);
            return false;
        }

        UUID targetId = operation.target.getUniqueId();
        Player online = operation.target.getPlayer();
        // A player on the death screen is online, but teleporting them now is lost on respawn.
        boolean readyToTeleport = online != null && online.isOnline() && !online.isDead();
        Location targetBefore = readyToTeleport ? online.getLocation().clone() : null;
        int foodBefore = readyToTeleport ? online.getFoodLevel() : 20;
        float saturationBefore = readyToTeleport ? online.getSaturation() : 5.0f;
        int beforeHearts = data.getHearts(targetId);
        UUID completionId = operation.journalOperation == null ? null : operation.journalOperation.id();
        boolean beforeCompletion = completionId != null && data.hasCompletedOperation(completionId);
        boolean beforeAchievement = data.hasPendingRevivedAchievement(targetId);
        Location beforeSpawn = data.getRevivalSpawnLocation(targetId);
        boolean durableSuccess = false;
        try {
            // Inside the try so any failure here still releases the pending maps and refunds.
            int revivedHearts = plugin.getHeartAllergyManager() == null
                ? beforeHearts
                : plugin.getHeartAllergyManager().heartsForRevival(
                    targetId,
                    beforeHearts,
                    plugin.getMaxHearts()
                );
            data.setHearts(targetId, revivedHearts);
            data.setDead(targetId, false);
            if (readyToTeleport) {
                online.setGameMode(plugin.getDefaultGameMode());
                online.setMaxHealth(plugin.maxHealthForHearts(revivedHearts));
                online.setHealth(online.getMaxHealth());
                online.setFoodLevel(20);
                online.setSaturation(10.0f);
                if (!isSafeSpawnLocation(operation.spawnLocation)) {
                    throw new IllegalStateException("revival location became unsafe");
                }
                if (!online.teleport(operation.spawnLocation)) {
                    throw new IllegalStateException("target teleport was rejected");
                }
                online.setFallDistance(0.0f);
                data.clearRevivalSpawnLocation(targetId);
            } else {
                data.setRevivalSpawnLocation(targetId, operation.spawnLocation);
            }

            boolean targetOnline = readyToTeleport;
            boolean pendingRevivedAchievement = ReviveRules.shouldQueueRevivedAchievement(targetOnline, true);
            AdvancementManager advancements = plugin.getAdvancementManager();
            // Persist the successful revival before any advancement side effect.
            // If awarding fails, the pending flag is retried on the next join.
            data.setPendingRevivedAchievement(targetId, pendingRevivedAchievement);
            data.setCompletedOperation(completionId, true);
            if (!data.saveSafely()) {
                throw new IllegalStateException("players.yml was not saved");
            }
            durableSuccess = true;
            if (targetOnline && advancements != null) {
                boolean targetAwarded = advancements.grant(online, Achievement.REVIVED);
                if (targetAwarded) {
                    data.setPendingRevivedAchievement(targetId, false);
                    if (!data.saveSafely()) {
                        data.setPendingRevivedAchievement(targetId, true);
                        plugin.getLogger().warning(
                            "Could not persist completed revived achievement for " + targetId
                        );
                    }
                }
            }
            if (ReviveRules.isDifferentPlayer(
                operation.reviver == null ? null : operation.reviver.getUniqueId(),
                targetId
            ) && advancements != null) {
                advancements.grant(operation.reviver, Achievement.REVIVE_OTHER);
            }
            if (plugin.getRecoveryJournal() != null && operation.journalOperation != null) {
                if (!plugin.getRecoveryJournal().commit(operation.journalOperation)) {
                    plugin.getLogger().warning("Altar revival succeeded but recovery journal cleanup was deferred for " + targetId);
                }
            }
            finishPending(operation, false);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Failed to commit altar revival: " + ex.getMessage());
            if (durableSuccess) {
                // Effects/achievement failures cannot undo a persisted revival or refund its payment.
                finishPending(operation, false);
                return true;
            }
            data.setHearts(targetId, beforeHearts);
            data.setPendingRevivedAchievement(targetId, beforeAchievement);
            data.setRevivalSpawnLocation(targetId, beforeSpawn);
            data.setCompletedOperation(completionId, beforeCompletion);
            data.setDead(targetId, true);
            if (!data.saveSafely()) {
                plugin.getLogger().warning("Failed to roll back altar revival data for " + targetId);
            }
            if (readyToTeleport && online.isOnline()) {
                try {
                    if (targetBefore != null) online.teleport(targetBefore);
                    online.setMaxHealth(plugin.maxHealthForHearts(beforeHearts));
                    online.setHealth(online.getMaxHealth());
                    online.setFoodLevel(foodBefore);
                    online.setSaturation(saturationBefore);
                    online.setGameMode(org.bukkit.GameMode.SPECTATOR);
                } catch (RuntimeException rollbackError) {
                    plugin.getLogger().warning("Failed to roll back target game mode: " + rollbackError.getMessage());
                }
            }
            finishPending(operation, true);
            return false;
        }

        if (operation.external) return true;

        Location spawn = operation.spawnLocation;
        spawn.getWorld().strikeLightningEffect(spawn);
        plugin.playSound(spawn, "altar.finish.totem");
        plugin.playSoundToPlayer(operation.reviver, "altar.finish.totem");
        spawn.getWorld().spawnParticle(Particle.HEART, spawn.clone().add(0, 1, 0), 60, 1.0, 1.0, 1.0);
        spawn.getWorld().spawnParticle(Particle.ENCHANT, spawn.clone().add(0, 0.5, 0), 80, 0.8, 1.5, 0.8, 0.5);

        boolean wasOnline = online != null && online.isOnline();
        String msgKey = wasOnline ? "revive.success" : "revive.offline";
        String reviverName = operation.reviver == null ? "Console" : operation.reviver.getName();
        String message = plugin.msg(msgKey)
            .replace("%player%", nameOf(operation.target))
            .replace("%reviver%", reviverName);
        if (operation.reviver != null && operation.reviver.isOnline()) {
            operation.reviver.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(message));
        }
        if (online != null && online.isOnline()) {
            online.sendActionBar(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(plugin.msg(online, "revive.offline-welcome")));
        }
        return true;
    }

    public boolean reviveFromLimbo(Player target, Location spawnLocation) {
        if (target == null || !target.isOnline()) {
            return false;
        }
        UUID targetId = target.getUniqueId();
        DataManager data = plugin.getDataManager();
        if (!data.hasPlayerData(targetId)) return false;
        World targetWorld = spawnLocation == null ? target.getWorld() : spawnLocation.getWorld();
        Location delegated = targetWorld == null ? null : plugin.getEventSpawnLocator().findRandomSpawnPlus(targetWorld);
        Location revivalLocation = delegated == null ? spawnLocation : delegated;
        if (revivalLocation == null || revivalLocation.getWorld() == null
            || plugin.isMechanicsDisabled(revivalLocation)) return false;
        if (!isSafeSpawnLocation(revivalLocation) || pendingByTarget.containsKey(targetId)) return false;
        if (!data.isDead(targetId)) {
            boolean teleported = target.teleport(revivalLocation);
            if (teleported) startLimboArrivalAnimation(target, revivalLocation);
            return teleported;
        }

        PendingRevival operation = new PendingRevival(
            target,
            null,
            revivalLocation,
            revivalLocation,
            revivalLocation,
            null,
            true
        );
        pendingByTarget.put(targetId, operation);
        boolean revived = completeRevival(operation);
        if (revived) startLimboArrivalAnimation(target, revivalLocation);
        return revived;
    }

    private void startLimboArrivalAnimation(Player target, Location arrival) {
        if (target == null || !target.isOnline() || arrival == null || arrival.getWorld() == null) return;
        cancelLimboArrival(target.getUniqueId());

        Location cloud = arrival.clone().add(0.0, 1.0, 0.0);
        Location headStart = cloud.clone().add(0.7, 4.0, 0.7);
        Location headEnd = arrival.clone().add(0.0, 1.0, 0.0);
        ItemDisplay display = arrival.getWorld().spawn(headStart, ItemDisplay.class);
        ItemStack headItem = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta skullMeta = (SkullMeta) headItem.getItemMeta();
        if (skullMeta == null) {
            display.remove();
            return;
        }
        skullMeta.setPlayerProfile(target.getPlayerProfile());
        headItem.setItemMeta(skullMeta);
        display.setItemStack(headItem);
        display.setPersistent(false);
        display.setViewRange(32.0f);
        LimboArrival arrivalAnimation = new LimboArrival(target.getUniqueId(), target, cloud, headStart, headEnd, display);
        limboArrivals.put(target.getUniqueId(), arrivalAnimation);
        arrivalAnimation.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isActiveLimboArrival(arrivalAnimation)
                    || !target.isOnline()
                    || target.getWorld() != arrival.getWorld()
                    || display.isDead()) {
                    cancelLimboArrival(target.getUniqueId());
                    cancel();
                    return;
                }
                arrivalAnimation.tick++;
                double cloudProgress = Math.min(1.0, arrivalAnimation.tick / 30.0);
                arrival.getWorld().spawnParticle(
                    Particle.CLOUD,
                    cloud,
                    18 + (int) Math.round(cloudProgress * 16.0),
                    0.45 + cloudProgress * 0.25,
                    0.35 + cloudProgress * 0.20,
                    0.45 + cloudProgress * 0.25,
                    0.01
                );
                arrival.getWorld().spawnParticle(Particle.ENCHANT, cloud, 5, 0.3, 0.45, 0.3, 0.08);
                if (arrivalAnimation.tick == 1) {
                    plugin.playSound(cloud, "altar.start.beacon");
                    plugin.playSoundToPlayer(target, "altar.start.beacon");
                }

                double progress = Math.min(1.0, Math.max(0.0, (arrivalAnimation.tick - 6.0) / 24.0));
                double eased = progress * progress * (3.0 - 2.0 * progress);
                Location position = new Location(
                    arrival.getWorld(),
                    headStart.getX() + (headEnd.getX() - headStart.getX()) * eased,
                    headStart.getY() + (headEnd.getY() - headStart.getY()) * eased
                        + Math.sin(progress * Math.PI) * 0.45,
                    headStart.getZ() + (headEnd.getZ() - headStart.getZ()) * eased
                );
                display.teleport(position);
                display.setTransformation(new Transformation(
                    new Vector3f(),
                    new Quaternionf().rotateY((float) (progress * Math.PI * 3.0)),
                    new Vector3f(0.9f, 0.9f, 0.9f),
                    new Quaternionf()
                ));

                if (arrivalAnimation.tick >= 30) {
                    target.teleport(arrival);
                    target.setFallDistance(0.0f);
                    arrival.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, headEnd, 30, 0.5, 0.8, 0.5, 0.2);
                    arrival.getWorld().spawnParticle(Particle.ENCHANT, headEnd, 35, 0.5, 0.8, 0.5, 0.12);
                    plugin.playSound(headEnd, "altar.finish.totem");
                    plugin.playSoundToPlayer(target, "altar.finish.totem");
                    finishLimboArrival(arrivalAnimation);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private boolean isActiveLimboArrival(LimboArrival arrival) {
        return arrival != null && limboArrivals.get(arrival.uuid) == arrival;
    }

    private void finishLimboArrival(LimboArrival arrival) {
        if (arrival == null) return;
        limboArrivals.remove(arrival.uuid, arrival);
        if (arrival.display != null && !arrival.display.isDead()) arrival.display.remove();
    }

    public void cancelLimboArrival(UUID uuid) {
        LimboArrival arrival = limboArrivals.remove(uuid);
        if (arrival == null) return;
        if (arrival.task != null) arrival.task.cancel();
        if (arrival.display != null && !arrival.display.isDead()) arrival.display.remove();
    }

    /** Legacy SpaydisCore fallback used when the v1 reviveFromLimbo hook is unavailable. */
    public void forceRevive(OfflinePlayer target, Player reviver) {
        if (target == null) return;
        if (reviver != null && plugin.isMechanicsDisabled(reviver.getWorld())) return;
        Player onlineTarget = target.getPlayer();
        if (onlineTarget != null && plugin.isMechanicsDisabled(onlineTarget.getWorld())) return;
        UUID targetId = target.getUniqueId();
        if (pendingByTarget.containsKey(targetId)) {
            if (reviver != null) sendError(reviver, "altar-error.revival-pending");
            return;
        }

        DataManager data = plugin.getDataManager();
        if (!data.isDead(targetId)) {
            if (reviver != null) sendError(reviver, "altar-error.not-dead", "%player%", nameOf(target));
            return;
        }

        Location altarLocation = reviver == null ? null : data.findNearestAltar(reviver.getLocation());
        if (altarLocation == null) {
            List<Location> allAltars = data.getAllAltars();
            if (!allAltars.isEmpty()) altarLocation = allAltars.get(0);
        }
        Location spawn;
        if (altarLocation != null) {
            spawn = calculateSpawnLocation(altarLocation);
        } else if (reviver != null) {
            spawn = calculateSpawnLocation(reviver.getLocation());
        } else {
            World world = onlineTarget != null ? onlineTarget.getWorld() : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
            spawn = world == null ? null : calculateSpawnLocation(world.getSpawnLocation());
        }
        if (spawn == null) {
            if (reviver != null) sendError(reviver, "altar-error.no-safe-location");
            return;
        }

        PendingRevival operation = new PendingRevival(
            target,
            reviver,
            altarLocation == null ? spawn : altarLocation,
            spawn,
            reviver == null ? spawn : reviver.getLocation(),
            null,
            false
        );
        pendingByTarget.put(targetId, operation);
        completeRevival(operation);
    }

    public void cancelForReviver(UUID reviverId) {
        cancelForReviver(reviverId, false);
    }

    public void cancelForReviver(UUID reviverId, boolean forceDrop) {
        if (reviverId == null) return;
        PendingRevival operation = pendingByReviver.get(reviverId);
        if (operation != null) cancelPending(operation, true, forceDrop);
    }

    public void shutdown() {
        closeOpenInventories();
        for (PendingRevival operation : new ArrayList<>(pendingByTarget.values())) {
            cancelPending(operation, true);
        }
        this.altarDisplayManager.shutdown();
        for (UUID uuid : new ArrayList<>(limboArrivals.keySet())) cancelLimboArrival(uuid);
        limboArrivals.clear();
        pendingByTarget.clear();
        pendingByReviver.clear();
    }

    private void closeOpenInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof MenuHolder holder && holder.getType() == MenuHolder.Type.ALTAR) {
                player.closeInventory();
                handleClose(player, top);
            }
        }
    }

    public void syncPlacedDisplays() {
        this.altarDisplayManager.syncPlacedDisplays();
    }

    public void syncPlacedDisplaysInChunk(org.bukkit.Chunk chunk) {
        this.altarDisplayManager.syncPlacedDisplaysInChunk(chunk);
    }

    public void forgetDisplaysInChunk(org.bukkit.Chunk chunk) {
        this.altarDisplayManager.forgetChunk(chunk);
    }

    public void syncMissingAltarDisplays() {
        this.altarDisplayManager.syncMissingAltarDisplays();
    }

    public void spawnAltarDisplay(Location location) {
        this.altarDisplayManager.spawnAltarDisplay(location);
    }

    public void removeAltarDisplay(Location location) {
        this.altarDisplayManager.removeAltarDisplay(location);
    }

    private boolean isActive(PendingRevival operation) {
        return operation != null
            && !operation.cleaned
            && pendingByTarget.get(operation.target.getUniqueId()) == operation;
    }

    private void cancelPending(PendingRevival operation, boolean refund) {
        cancelPending(operation, refund, false);
    }

    private void cancelPending(PendingRevival operation, boolean refund, boolean forceDrop) {
        finishPending(operation, refund, forceDrop);
    }

    private void finishPending(PendingRevival operation, boolean refund) {
        finishPending(operation, refund, false);
    }

    private void finishPending(PendingRevival operation, boolean refund, boolean forceDrop) {
        if (operation == null || operation.cleaned) return;
        int paymentCount = operation.journalOperation == null ? 0
            : operation.journalOperation.payments().stream().skip(1).mapToInt(p -> p.refundItem().getAmount()).sum();
        operation.cleaned = true;
        if (operation.task != null) operation.task.cancel();
        if (operation.display != null && !operation.display.isDead()) operation.display.remove();
        pendingByTarget.remove(operation.target.getUniqueId(), operation);
        if (operation.reviver != null) pendingByReviver.remove(operation.reviver.getUniqueId(), operation);
        try {
            if (refund && operation.journalOperation != null && plugin.getRecoveryJournal() != null) {
                Player refundPlayer = forceDrop ? null : operation.reviver;
                plugin.getRecoveryJournal().abort(
                    operation.journalOperation,
                    false,
                    refundPlayer,
                    operation.refundLocation
                );
            } else if (refund) {
                refund(operation, forceDrop);
            }
        } finally {
            if (plugin.getAdminTools() != null) plugin.getAdminTools().record(
                operation.external ? "SpaydisCore" : operation.reviver == null ? "Console" : operation.reviver.getName(),
                operation.target, "REVIVAL", "tokens=" + paymentCount,
                refund ? "CANCELLED_REFUND_REQUESTED" : "SUCCESS");
        }
    }

    private void refund(PendingRevival operation) {
        refund(operation, false);
    }

    private void refund(PendingRevival operation, boolean forceDrop) {
        if (operation.refundItems.isEmpty()) return;
        Player player = operation.reviver;
        Location dropLocation = player != null && player.isOnline() ? player.getLocation() : operation.refundLocation;
        for (ItemStack item : operation.refundItems) {
            if (!forceDrop && player != null && player.isOnline()) {
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
                for (ItemStack leftover : leftovers.values()) {
                    dropOrReturn(player, dropLocation, leftover);
                }
            } else if (dropLocation != null && dropLocation.getWorld() != null) {
                dropLocation.getWorld().dropItemNaturally(dropLocation, item.clone());
            }
        }
        operation.refundItems.clear();
    }

    private void dropOrReturn(Player player, Location location, ItemStack item) {
        if (item == null || item.getAmount() <= 0) return;
        if (player != null && player.isOnline()) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            for (ItemStack leftover : leftovers.values()) {
                Location safeLocation = location;
                if (safeLocation == null || safeLocation.getWorld() == null) safeLocation = player.getLocation();
                if (safeLocation != null && safeLocation.getWorld() != null) {
                    safeLocation.getWorld().dropItemNaturally(safeLocation, leftover);
                }
            }
        } else if (location != null && location.getWorld() != null) {
            location.getWorld().dropItemNaturally(location, item);
        }
    }

    private void sendError(Player player, String key, String... replacements) {
        if (player == null || !player.isOnline()) return;
        String message = plugin.msg(player, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace(replacements[i], replacements[i + 1]);
        }
        player.sendMessage(message);
        plugin.playSoundToPlayer(player, "altar.error");
    }

    private boolean isGridSlot(int raw) {
        for (int slot : grid) if (slot == raw) return true;
        return false;
    }

    private String nameOf(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private static final class LimboArrival {
        private final UUID uuid;
        private final Player player;
        private final Location cloud;
        private final Location headStart;
        private final Location headEnd;
        private final ItemDisplay display;
        private BukkitTask task;
        private int tick;

        private LimboArrival(
            UUID uuid,
            Player player,
            Location cloud,
            Location headStart,
            Location headEnd,
            ItemDisplay display
        ) {
            this.uuid = uuid;
            this.player = player;
            this.cloud = cloud;
            this.headStart = headStart;
            this.headEnd = headEnd;
            this.display = display;
        }
    }

    private static final class PendingRevival {
        private final OfflinePlayer target;
        private final Player reviver;
        private final Location altarLocation;
        private final Location spawnLocation;
        private final Location refundLocation;
        private final RecoveryJournal.Operation journalOperation;
        private final boolean external;
        private final List<ItemStack> refundItems = new ArrayList<>();
        private ItemDisplay display;
        private BukkitTask task;
        private boolean cleaned;

        private PendingRevival(
            OfflinePlayer target,
            Player reviver,
            Location altarLocation,
            Location spawnLocation,
            Location refundLocation,
            RecoveryJournal.Operation journalOperation,
            boolean external
        ) {
            this.target = target;
            this.reviver = reviver;
            this.altarLocation = altarLocation == null ? spawnLocation.clone() : altarLocation.clone();
            this.spawnLocation = spawnLocation.clone();
            this.refundLocation = refundLocation == null ? spawnLocation.clone() : refundLocation.clone();
            this.journalOperation = journalOperation;
            this.external = external;
        }
    }
}

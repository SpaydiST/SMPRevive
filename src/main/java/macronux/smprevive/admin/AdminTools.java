package macronux.smprevive.admin;

import macronux.smprevive.SMPRevive;
import macronux.smprevive.rules.ReviveRules;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Administrative inspection only. This menu never handles altar payments. */
public final class AdminTools implements Listener {
    public static final String PERMISSION = "smprevive.admin";
    private final SMPRevive plugin;
    private OperationHistory history;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z").withZone(ZoneId.of("Europe/Moscow"));

    public AdminTools(SMPRevive plugin) {
        this.plugin = plugin;
        try {
            history = new OperationHistory(plugin.getDataFolder().toPath().resolve("admin-history.log"));
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("Admin history unavailable: " + exception.getMessage());
        }
    }

    public String text(CommandSender sender, String key, Object... pairs) {
        String text = plugin.msg(sender, "admin." + key);
        for (int i = 0; i + 1 < pairs.length; i += 2) text = text.replace("%" + pairs[i] + "%", String.valueOf(pairs[i + 1]));
        return text;
    }

    public void record(String actor, OfflinePlayer target, String action, String details, String result) {
        if (history == null) return;
        try {
            history.record(actor, target == null ? null : target.getUniqueId(), target == null ? "-" : name(target), action, details, result);
        } catch (IOException | RuntimeException exception) {
            // Diagnostic logging must never roll back a completed payment or revival.
            plugin.getLogger().warning("Could not persist admin history: " + exception.getMessage());
        }
    }

    public boolean handle(CommandSender sender, String[] args, Function<String, OfflinePlayer> resolver) {
        if (!sender.hasPermission(PERMISSION)) return true;
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status", "menu" -> {
                if (args.length > 2) { sender.sendMessage(text(sender, "usage")); return true; }
                OfflinePlayer target = args.length == 2 ? resolver.apply(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) { sender.sendMessage(plugin.msg(sender, "player-not-found")); return true; }
                if (action.equals("status")) showStatus(sender, target);
                else if (sender instanceof Player player) open(player, target);
                else sender.sendMessage(text(sender, "players-only"));
            }
            case "history" -> showHistory(sender, args, resolver);
            case "diagnostics" -> {
                if (args.length != 1) sender.sendMessage(text(sender, "usage"));
                else diagnostics(sender);
            }
            case "difficulty" -> difficulty(sender, args);
            case "pending" -> {
                if (args.length != 1) { sender.sendMessage(text(sender, "recovery-usage")); return true; }
                sender.sendMessage(text(sender, "pending-summary", "count", plugin.getProtectedItemService().pendingCount(),
                    "flights", plugin.getProtectedItemService().activeFlightCount(), "payments", plugin.getRecoveryJournal().size()));
                plugin.getProtectedItemService().describePending().forEach(sender::sendMessage);
                plugin.getRecoveryJournal().describePending().forEach(sender::sendMessage);
            }
            case "recoverhead" -> {
                if (args.length < 2 || args.length > 3) { sender.sendMessage(text(sender, "recovery-usage")); return true; }
                OfflinePlayer target = resolver.apply(args[1]);
                Player recipient = args.length == 3 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
                if (target == null || recipient == null) { sender.sendMessage(plugin.msg(sender, "player-not-found")); return true; }
                boolean success = plugin.getProtectedItemService().recoverHead(target.getUniqueId(), recipient);
                sender.sendMessage(text(sender, success ? "head-recovered" : "head-recovery-failed", "player", name(target), "recipient", recipient.getName()));
                record(sender.getName(), target, "RECOVER_HEAD", recipient.getName(), success ? "QUEUED" : "REJECTED");
            }
            case "resolvepayment" -> {
                if (args.length != 3 || !List.of("refund", "complete").contains(args[2].toLowerCase(Locale.ROOT))) {
                    sender.sendMessage(text(sender, "recovery-usage")); return true;
                }
                boolean success = false;
                try { success = plugin.getRecoveryJournal().resolveLegacy(UUID.fromString(args[1]), args[2].equalsIgnoreCase("refund")); }
                catch (IllegalArgumentException ignored) { /* Invalid operation ID is reported below. */ }
                sender.sendMessage(text(sender, success ? "payment-resolved" : "payment-resolution-failed"));
                record(sender.getName(), null, "RESOLVE_PAYMENT", args[1] + " " + args[2], success ? "SUCCESS" : "REJECTED");
            }
            default -> { return false; }
        }
        return true;
    }

    private List<String> statusLines(CommandSender viewer, OfflinePlayer target) {
        UUID id = target.getUniqueId();
        var data = plugin.getDataManager();
        String token = data.getTokenMaterial(id);
        Material material = token == null ? null : Material.matchMaterial(token);
        int available = 0;
        if (viewer instanceof Player player && material != null) {
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (item != null && item.getType() == material) available += item.getAmount();
            }
        }
        int cost = plugin.getTokensRequired(id);
        return List.of(
            text(viewer, "player", "player", name(target)),
            text(viewer, "uuid", "uuid", id),
            text(viewer, "hearts", "count", data.getHearts(id), "max", plugin.getMaxHearts()),
            text(viewer, "state", "state", text(viewer, data.isDead(id) ? "dead" : "alive")),
            text(viewer, "online", "value", text(viewer, target.isOnline() ? "yes" : "no")),
            text(viewer, "allergy", "value", text(viewer, plugin.getHeartAllergyManager().isAllergic(id) ? "yes" : "no")),
            text(viewer, "pending-spawn", "value", text(viewer, data.hasPendingRevivalSpawn(id) ? "yes" : "no")),
            text(viewer, "token", "item", material == null ? text(viewer, "none") : plugin.getMaterialName(viewer, material.name())),
            text(viewer, "cost", "count", cost),
            !(viewer instanceof Player) ? text(viewer, "balance-console") : material == null ? text(viewer, "balance-unassigned")
                : text(viewer, "balance", "count", available, "missing", Math.max(0, cost - available)),
            text(viewer, "cost-note")
        );
    }

    private void showStatus(CommandSender sender, OfflinePlayer target) {
        statusLines(sender, target).forEach(sender::sendMessage);
    }

    // Panel slots.
    private static final int SLOT_SUMMARY = 4;
    private static final int SLOT_REVIVE = 19, SLOT_HEART_MINUS = 20, SLOT_HEART_PLUS = 21, SLOT_GIVE_HEART = 22,
        SLOT_ALLERGY = 23, SLOT_GIVE_ALTAR = 24;
    private static final int SLOT_PLAYERS = 28, SLOT_HISTORY = 30, SLOT_REFRESH = 31, SLOT_DIAGNOSTICS = 32, SLOT_EVENT = 34;
    private static final int PLAYER_LIST_SIZE = 45;

    /** Short card for the menu; the full dump stays in /revive status. */
    private List<String> summaryLines(Player viewer, OfflinePlayer target) {
        UUID id = target.getUniqueId();
        var data = plugin.getDataManager();
        String token = data.getTokenMaterial(id);
        Material material = token == null ? null : Material.matchMaterial(token);
        List<String> lines = new java.util.ArrayList<>();
        lines.add(text(viewer, "hearts", "count", data.getHearts(id), "max", plugin.getMaxHearts()));
        lines.add(text(viewer, "state", "state", text(viewer, data.isDead(id) ? "dead" : "alive")));
        lines.add(text(viewer, "online", "value", text(viewer, target.isOnline() ? "yes" : "no")));
        if (plugin.getHeartAllergyManager().isAllergic(id)) lines.add(text(viewer, "allergy", "value", text(viewer, "yes")));
        if (data.hasPendingRevivalSpawn(id)) lines.add(text(viewer, "pending-spawn", "value", text(viewer, "yes")));
        lines.add(text(viewer, "token", "item", material == null ? text(viewer, "none") : plugin.getMaterialName(viewer, material.name())));
        lines.add(text(viewer, "cost", "count", plugin.getTokensRequired(id)));
        return lines;
    }

    private ItemStack headItem(Player viewer, OfflinePlayer target, List<String> lore) {
        ItemStack head = item(Material.PLAYER_HEAD, name(target), lore);
        if (head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            skull.setOwningPlayer(target);
            head.setItemMeta(skull);
        }
        return head;
    }

    private void open(Player viewer, OfflinePlayer target) {
        if (!viewer.hasPermission(PERMISSION)) return;
        UUID id = target.getUniqueId();
        var data = plugin.getDataManager();
        AdminHolder holder = new AdminHolder(viewer.getUniqueId(), id, false);
        Inventory inventory = Bukkit.createInventory(holder, 36, text(viewer, "menu-title"));
        holder.inventory = inventory;
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler.clone());
        boolean dead = data.isDead(id);
        int hearts = data.getHearts(id);
        String heartInfo = text(viewer, "heart-hint", "count", hearts, "max", plugin.getMaxHearts());
        boolean allergic = plugin.getHeartAllergyManager().isAllergic(id);

        inventory.setItem(SLOT_SUMMARY, headItem(viewer, target, summaryLines(viewer, target)));
        inventory.setItem(SLOT_REVIVE, dead
            ? item(Material.EMERALD, text(viewer, "revive-button"), List.of(text(viewer, "revive-hint", "player", name(target))))
            : item(Material.BARRIER, text(viewer, "revive-button"), List.of(text(viewer, "revive-unavailable", "player", name(target)))));
        inventory.setItem(SLOT_HEART_MINUS, item(Material.REDSTONE, text(viewer, "heart-minus"), List.of(heartInfo)));
        inventory.setItem(SLOT_HEART_PLUS, item(Material.GLOWSTONE_DUST, text(viewer, "heart-plus"), List.of(heartInfo)));
        inventory.setItem(SLOT_GIVE_HEART, item(plugin.getHeartMaterial(), text(viewer, "give-heart"),
            List.of(text(viewer, "give-heart-hint", "player", name(target)))));
        inventory.setItem(SLOT_ALLERGY, item(Material.SPIDER_EYE, text(viewer, "allergy-button"),
            List.of(text(viewer, "allergy-hint", "value", text(viewer, allergic ? "yes" : "no")))));
        inventory.setItem(SLOT_GIVE_ALTAR, item(plugin.getAltarItemMaterial(), text(viewer, "give-altar"), List.of(text(viewer, "give-altar-hint"))));
        inventory.setItem(SLOT_PLAYERS, item(Material.PLAYER_HEAD, text(viewer, "players-button"), List.of(text(viewer, "players-hint"))));
        inventory.setItem(SLOT_HISTORY, item(Material.BOOK, text(viewer, "history-title"), List.of(text(viewer, "history-hint", "player", name(target)))));
        inventory.setItem(SLOT_REFRESH, item(Material.CLOCK, text(viewer, "refresh"), List.of(text(viewer, "refresh-note"))));
        inventory.setItem(SLOT_DIAGNOSTICS, item(Material.COMPARATOR, text(viewer, "diagnostics-title"), List.of(text(viewer, "diagnostics-hint"))));
        inventory.setItem(SLOT_EVENT, item(Material.FIREWORK_ROCKET, text(viewer, "event-button"), List.of(text(viewer, "event-hint"))));
        viewer.openInventory(inventory);
    }

    /** Online players, those waiting for revival first. */
    private void openPlayers(Player viewer) {
        if (!viewer.hasPermission(PERMISSION)) return;
        var data = plugin.getDataManager();
        AdminHolder holder = new AdminHolder(viewer.getUniqueId(), viewer.getUniqueId(), true);
        Inventory inventory = Bukkit.createInventory(holder, 54, text(viewer, "players-title"));
        holder.inventory = inventory;
        List<Player> players = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(java.util.Comparator.comparing((Player p) -> !data.isDead(p.getUniqueId()))
            .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (int slot = 0; slot < Math.min(PLAYER_LIST_SIZE, players.size()); slot++) {
            Player player = players.get(slot);
            List<String> lore = new java.util.ArrayList<>();
            lore.add(text(viewer, "hearts", "count", data.getHearts(player.getUniqueId()), "max", plugin.getMaxHearts()));
            lore.add(text(viewer, "state", "state", text(viewer, data.isDead(player.getUniqueId()) ? "dead" : "alive")));
            lore.add(text(viewer, "player-entry-hint"));
            inventory.setItem(slot, headItem(viewer, player, lore));
            holder.listed.put(slot, player.getUniqueId());
        }
        viewer.openInventory(inventory);
    }

    private void adjustHearts(Player viewer, OfflinePlayer target, int delta) {
        var data = plugin.getDataManager();
        UUID id = target.getUniqueId();
        int previous = data.getHearts(id);
        int applied = Math.max(1, Math.min(plugin.getMaxHearts(), previous + delta));
        String action = text(viewer, "act-hearts");
        if (applied == previous) { viewer.sendMessage(text(viewer, "action-failed", "action", action, "player", name(target))); return; }
        data.setHearts(id, applied);
        if (!data.saveSafely()) {
            data.setHearts(id, previous);
            viewer.sendMessage(text(viewer, "action-failed", "action", action, "player", name(target)));
            return;
        }
        Player online = target.getPlayer();
        if (online != null && online.isOnline() && !data.isDead(id)) {
            online.setMaxHealth(plugin.maxHealthForHearts(applied));
            online.setHealth(Math.min(online.getHealth(), online.getMaxHealth()));
        }
        record(viewer.getName(), target, "SET_HEARTS", previous + " -> " + applied, "SUCCESS");
        viewer.sendMessage(text(viewer, "action-done", "action", action + " " + previous + " -> " + applied, "player", name(target)));
    }

    private void toggleAllergy(Player viewer, OfflinePlayer target) {
        var allergies = plugin.getHeartAllergyManager();
        boolean enable = !allergies.isAllergic(target.getUniqueId());
        String action = text(viewer, "act-allergy");
        if (!allergies.setAllergic(target.getUniqueId(), enable)) {
            viewer.sendMessage(text(viewer, "action-failed", "action", action, "player", name(target)));
            return;
        }
        if (enable && target.getPlayer() != null && target.getPlayer().isOnline()) allergies.scanPlayer(target.getPlayer());
        record(viewer.getName(), target, "ALLERGY", enable ? "give" : "remove", "SUCCESS");
        viewer.sendMessage(text(viewer, "action-done", "action", action, "player", name(target)));
    }

    private void giveHeart(Player viewer, OfflinePlayer target) {
        Player online = target.getPlayer();
        if (online == null || !online.isOnline()) { viewer.sendMessage(text(viewer, "must-be-online", "player", name(target))); return; }
        String action = text(viewer, "act-give-heart");
        if (!plugin.getProtectedItemService().queueForPlayer(online, plugin.getItemManager().createHeart())) {
            viewer.sendMessage(text(viewer, "action-failed", "action", action, "player", name(target)));
            return;
        }
        record(viewer.getName(), target, "GIVE_HEART", "1", "SUCCESS");
        viewer.sendMessage(text(viewer, "action-done", "action", action, "player", name(target)));
    }

    private void giveAltar(Player viewer) {
        for (ItemStack rest : viewer.getInventory().addItem(plugin.getItemManager().createAltarItem()).values()) {
            viewer.getWorld().dropItemNaturally(viewer.getLocation(), rest);
        }
        record(viewer.getName(), viewer, "GIVE_ALTAR", "1", "SUCCESS");
        viewer.sendMessage(text(viewer, "action-done", "action", text(viewer, "act-altar"), "player", viewer.getName()));
    }

    private void revive(Player viewer, OfflinePlayer target) {
        if (!plugin.getDataManager().isDead(target.getUniqueId())) {
            viewer.sendMessage(text(viewer, "revive-unavailable", "player", name(target)));
            return;
        }
        plugin.getAltarGui().forceRevive(target, viewer);
        record(viewer.getName(), target, "REVIVE", "menu", "REQUESTED");
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AdminHolder holder)) return;
        boolean previouslyCancelled = event.isCancelled();
        event.setCancelled(true); // Includes shift-click, number keys, double-click and bottom inventory.
        if (previouslyCancelled || !(event.getWhoClicked() instanceof Player viewer)) return;
        if (!viewer.hasPermission(PERMISSION) || !viewer.getUniqueId().equals(holder.viewerId)) {
            Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory());
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.inventory.getSize()) return;
        if (holder.playerList) {
            UUID chosen = holder.listed.get(slot);
            if (chosen != null) reopen(viewer, holder, () -> open(viewer, Bukkit.getOfflinePlayer(chosen)));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(holder.targetId);
        switch (slot) {
            case SLOT_REVIVE -> { revive(viewer, target); reopen(viewer, holder, () -> open(viewer, target)); }
            case SLOT_HEART_MINUS -> { adjustHearts(viewer, target, -1); reopen(viewer, holder, () -> open(viewer, target)); }
            case SLOT_HEART_PLUS -> { adjustHearts(viewer, target, 1); reopen(viewer, holder, () -> open(viewer, target)); }
            case SLOT_GIVE_HEART -> giveHeart(viewer, target);
            case SLOT_ALLERGY -> { toggleAllergy(viewer, target); reopen(viewer, holder, () -> open(viewer, target)); }
            case SLOT_GIVE_ALTAR -> giveAltar(viewer);
            case SLOT_PLAYERS -> reopen(viewer, holder, () -> openPlayers(viewer));
            case SLOT_HISTORY -> showHistoryFor(viewer, holder.targetId, 1);
            case SLOT_REFRESH -> reopen(viewer, holder, () -> open(viewer, target));
            case SLOT_DIAGNOSTICS -> diagnostics(viewer);
            case SLOT_EVENT -> viewer.sendMessage(text(viewer, "event-status",
                "phase", plugin.getEventManager().phaseName(), "count", plugin.getEventManager().participantCount()));
            default -> { }
        }
    }

    /** Rebuilds a menu on the next tick, and only if the viewer still has this one open. */
    private void reopen(Player viewer, AdminHolder holder, Runnable open) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline() && viewer.getOpenInventory().getTopInventory() == holder.inventory) open.run();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AdminHolder) event.setCancelled(true);
    }

    public void closeMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof AdminHolder) player.closeInventory();
        }
    }

    private void showHistory(CommandSender sender, String[] args, Function<String, OfflinePlayer> resolver) {
        if (args.length > 3) { sender.sendMessage(text(sender, "usage")); return; }
        OfflinePlayer target = args.length >= 2 && !args[1].equalsIgnoreCase("all") ? resolver.apply(args[1]) : null;
        if (args.length >= 2 && !args[1].equalsIgnoreCase("all") && target == null) { sender.sendMessage(plugin.msg(sender, "player-not-found")); return; }
        int page;
        try { page = args.length == 3 ? Integer.parseInt(args[2]) : 1; }
        catch (NumberFormatException exception) { sender.sendMessage(text(sender, "invalid-page")); return; }
        if (page < 1 || page > 100) { sender.sendMessage(text(sender, "invalid-page")); return; }
        showHistoryFor(sender, target == null ? null : target.getUniqueId(), page);
    }

    private void showHistoryFor(CommandSender sender, UUID target, int page) {
        if (history == null) { sender.sendMessage(text(sender, "history-unavailable")); return; }
        if (!history.isPersistent()) sender.sendMessage(text(sender, "history-unavailable"));
        List<OperationHistory.Entry> entries = history.recent(target);
        int start = (page - 1) * 10;
        sender.sendMessage(text(sender, "history-page", "page", page, "pages", Math.max(1, (entries.size() + 9) / 10)));
        if (start >= entries.size()) { sender.sendMessage(text(sender, "empty")); return; }
        for (var entry : entries.subList(start, Math.min(start + 10, entries.size()))) {
            sender.sendMessage(text(sender, "history-line", "time", TIME.format(entry.time()), "actor", entry.actor(),
                "target", entry.target(), "action", entry.action(), "details", entry.details(), "result", entry.result()));
        }
    }

    public void diagnostics(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION)) return;
        long tasks = Bukkit.getScheduler().getPendingTasks().stream().filter(t -> t.getOwner() == plugin).count();
        sender.sendMessage(text(sender, "diagnostics-summary", "players", Bukkit.getOnlinePlayers().size(),
            "altars", plugin.getDataManager().getRegisteredAltars().size(), "tasks", tasks,
            "recovery", plugin.getRecoveryJournal().size(), "saves", plugin.getDataManager().getSaveCount(),
            "skipped", plugin.getDataManager().getSkippedSaveCount()));
        sender.sendMessage(text(sender, "pending-summary", "count", plugin.getProtectedItemService().pendingCount(),
            "flights", plugin.getProtectedItemService().activeFlightCount(), "payments", plugin.getRecoveryJournal().size()));
        sender.sendMessage(text(sender, "tps", "value", String.format(Locale.ROOT, "%.2f", Bukkit.getTPS()[0])));
        plugin.getPerformanceCounters().snapshot().forEach((name, timing) -> sender.sendMessage(text(sender, "timing",
            "name", name, "count", timing.count(), "average", String.format(Locale.ROOT, "%.3f", timing.averageMillis()),
            "max", String.format(Locale.ROOT, "%.3f", timing.maximumMillis()))));
        plugin.getPerformanceCounters().counts().forEach((name, value) -> sender.sendMessage(text(sender,"counter","name",name,"value",value)));
        sender.sendMessage(text(sender, "measure-note"));
    }

    private void difficulty(CommandSender sender, String[] args) {
        if (args.length != 2 || !List.of("easy", "normal", "hard").contains(args[1].toLowerCase(Locale.ROOT))) {
            sender.sendMessage(text(sender, "difficulty-usage")); return;
        }
        if (plugin.getEventManager().isRunning()) { sender.sendMessage(text(sender, "event-running")); return; }
        String preset = args[1].toLowerCase(Locale.ROOT);
        int defaultCost = switch (preset) { case "easy" -> 80; case "hard" -> 320; default -> 160; };
        int defaultRadius = switch (preset) { case "easy" -> 250; case "hard" -> 1000; default -> 500; };
        int cost = plugin.getConfig().getInt("admin-presets." + preset + ".tokens-required", defaultCost);
        int radius = plugin.getConfig().getInt("admin-presets." + preset + ".event-spawn-radius", defaultRadius);
        if (cost < 1 || cost > ReviveRules.MAX_TOKENS_REQUIRED || radius < 0 || radius > 100000) { sender.sendMessage(text(sender, "invalid-preset")); return; }
        Object oldCost = plugin.getConfig().get("tokens-required");
        Object oldRadius = plugin.getConfig().get("event.spawn-radius");
        plugin.getConfig().set("tokens-required", cost);
        plugin.getConfig().set("event.spawn-radius", radius);
        if (!plugin.saveConfigSafely()) {
            plugin.getConfig().set("tokens-required", oldCost);
            plugin.getConfig().set("event.spawn-radius", oldRadius);
            sender.sendMessage(text(sender, "save-failed")); return;
        }
        plugin.reloadRuntime();
        record(sender.getName(), null, "DIFFICULTY", preset + "; tokens=" + cost + "; radius=" + radius, "SUCCESS");
        sender.sendMessage(text(sender, "difficulty-applied", "preset", preset, "cost", cost, "radius", radius));
    }

    private String name(OfflinePlayer target) { return target.getName() == null ? target.getUniqueId().toString() : target.getName(); }

    private static final class AdminHolder implements InventoryHolder {
        private final UUID viewerId;
        private final UUID targetId;
        private final boolean playerList;
        private final java.util.Map<Integer, UUID> listed = new java.util.HashMap<>();
        private Inventory inventory;
        private AdminHolder(UUID viewerId, UUID targetId, boolean playerList) {
            this.viewerId = viewerId; this.targetId = targetId; this.playerList = playerList;
        }
        public Inventory getInventory() { return inventory; }
    }
}

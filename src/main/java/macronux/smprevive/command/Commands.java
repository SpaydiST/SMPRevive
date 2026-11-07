package macronux.smprevive.command;

import macronux.smprevive.DataManager;
import macronux.smprevive.SMPRevive;
import macronux.smprevive.allergy.HeartAllergyManager;
import macronux.smprevive.event.EventManager;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class Commands implements CommandExecutor, TabCompleter {
    private final SMPRevive plugin;

    public Commands(SMPRevive plugin) {
        this.plugin = plugin;
    }

    public BasicCommand asBasicCommand(String commandName) {
        return new BasicCommand() {
            @Override
            public void execute(CommandSourceStack source, String[] args) {
                Commands.this.execute(source.getSender(), commandName, args);
            }

            @Override
            public List<String> suggest(CommandSourceStack source, String[] args) {
                return Commands.this.complete(source.getSender(), commandName, args);
            }

            @Override
            public boolean canUse(CommandSender sender) {
                return sender.hasPermission("smprevive.admin");
            }

            @Override
            public String permission() {
                return "smprevive.admin";
            }
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return this.execute(sender, command.getName(), args);
    }

    /** /revive shrine list | remove: the wither skeleton shrines created by the skull ritual. */
    private void handleShrine(CommandSender sender, String[] args) {
        var rituals = this.plugin.getRitualManager();
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (action.equals("list")) {
            var shrines = rituals.shrines();
            if (shrines.isEmpty()) {
                sender.sendMessage(this.plugin.msg(sender, "ritual.shrine-list-empty"));
                return;
            }
            for (var shrine : shrines) {
                World world = Bukkit.getWorld(shrine.world());
                sender.sendMessage(this.plugin.msg(sender, "ritual.shrine-list")
                    .replace("%world%", world == null ? shrine.world().toString() : world.getName())
                    .replace("%x%", String.valueOf((int) shrine.x()))
                    .replace("%y%", String.valueOf((int) shrine.y()))
                    .replace("%z%", String.valueOf((int) shrine.z())));
            }
        } else if (action.equals("remove") && sender instanceof Player player) {
            var shrine = rituals.nearestShrine(player.getLocation(), 64.0);
            if (shrine == null) {
                sender.sendMessage(this.plugin.msg(sender, "ritual.shrine-none-near"));
            } else if (rituals.removeShrine(shrine)) {
                sender.sendMessage(this.plugin.msg(sender, "ritual.shrine-removed"));
                this.plugin.getAdminTools().record(sender.getName(), null, "SHRINE_REMOVE",
                    (int) shrine.x() + " " + (int) shrine.y() + " " + (int) shrine.z(), "SUCCESS");
            } else {
                sender.sendMessage(this.plugin.msg(sender, "ritual.unavailable"));
            }
        } else {
            sender.sendMessage(this.plugin.msg(sender, "ritual.shrine-usage"));
        }
    }

    private boolean execute(CommandSender sender, String commandName, String[] args) {
        if (!sender.hasPermission("smprevive.admin")) {
            sender.sendMessage(this.plugin.msg(sender, "no-permission"));
            return true;
        }

        if (commandName.equalsIgnoreCase("reviveitem")) {
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /reviveitem <player>");
                return true;
            }
            Player target = this.resolveOnline(sender, args[0]);
            if (target == null) {
                sender.sendMessage(this.plugin.msg(sender, "player-not-found"));
                return true;
            }
            this.plugin.getCasinoGui().open(target);
            sender.sendMessage(this.plugin.msg(sender, "cmd.casino-opened").replace("%player%", target.getName()));
            return true;
        }

        if (commandName.equalsIgnoreCase("event")) {
            this.handleEvent(sender, args);
            return true;
        }

        if (commandName.equalsIgnoreCase("newbie")) {
            this.handleNewbie(sender, args);
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(this.plugin.msg(sender, "cmd.usage"));
            sender.sendMessage(this.plugin.getAdminTools().text(sender, "usage"));
            return true;
        }

        if (args[0].equalsIgnoreCase("shrine")) {
            this.handleShrine(sender, args);
            return true;
        }

        if (this.plugin.getAdminTools().handle(sender, args, this::resolveOffline)) return true;
        int maximum = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload", "testsound" -> 1;
            case "giveblock", "giveunbreak", "tokens", "revive" -> 2;
            case "giveheart", "sethearts", "allergy" -> 3;
            default -> Integer.MAX_VALUE;
        };
        if (args.length > maximum) {
            sender.sendMessage(this.plugin.msg(sender, "cmd.usage"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload": {
                this.plugin.reloadRuntime();
                this.plugin.getAdminTools().record(sender.getName(), null, "RELOAD", "config", "SUCCESS");
                sender.sendMessage(ChatColor.GREEN + "SMPRevive config reloaded.");
                break;
            }
            case "giveblock", "giveunbreak": {
                Player t = args.length > 1 ? this.resolveOnline(sender, args[1]) : (sender instanceof Player ? (Player) sender : null);
                if (t == null) {
                    sender.sendMessage(this.plugin.msg(sender, "player-not-found"));
                    return true;
                }
                boolean unbreakable = args[0].equalsIgnoreCase("giveunbreak");
                ItemStack altar = unbreakable
                    ? this.plugin.getItemManager().createUnbreakableAltarItem()
                    : this.plugin.getItemManager().createAltarItem();
                for (ItemStack rest : t.getInventory().addItem(new ItemStack[]{altar}).values()) {
                    t.getWorld().dropItemNaturally(t.getLocation(), rest);
                }
                sender.sendMessage(ChatColor.GREEN + "Gave " + (unbreakable ? "unbreakable " : "") + "altar block to " + t.getName());
                this.plugin.getAdminTools().record(sender.getName(), t, unbreakable ? "GIVE_UNBREAKABLE_ALTAR" : "GIVE_ALTAR", "1", "SUCCESS");
                break;
            }
            case "giveheart": {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /revive giveheart <player> [amount]");
                    return true;
                }
                Player t = this.resolveOnline(sender, args[1]);
                if (t == null) {
                    sender.sendMessage(this.plugin.msg(sender, "player-not-found"));
                    return true;
                }
                int amount = args.length > 2 ? this.parseInt(args[2], -1) : 1;
                if (amount < 1) {
                    sender.sendMessage(ChatColor.RED + "Amount must be >= 1.");
                    return true;
                }
                amount = Math.min(64, amount);
                ItemStack heart = this.plugin.getItemManager().createHeart();
                heart.setAmount(amount);
                if (!this.plugin.getProtectedItemService().queueForPlayer(t, heart)) {
                    sender.sendMessage(this.plugin.msg(sender, "altar-error.transaction"));
                    return true;
                }
                sender.sendMessage(ChatColor.GREEN + "Queued " + amount + " heart(s) for " + t.getName() + "; delivery waits for inventory space.");
                this.plugin.getAdminTools().record(sender.getName(), t, "GIVE_HEART", String.valueOf(amount), "SUCCESS");
                break;
            }
            case "testsound": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can test sounds.");
                    return true;
                }
                Player p = (Player) sender;
                String[] testSounds = {
                    "entity.player.levelup",
                    "item.totem.use", 
                    "block.beacon.power_select",
                    "entity.ender_dragon.growl",
                    "block.enchantment_table.use",
                    "entity.experience_orb.pickup",
                    "item.trident.riptide_1"
                };
                int played = 0;
                Location loc = p.getLocation();
                for (String soundName : testSounds) {
                    try {
                        loc.getWorld().playSound(loc, soundName, SoundCategory.MASTER, 1.0f, 1.0f);
                        played++;
                    } catch (Exception e) {
                        sender.sendMessage(ChatColor.RED + "Failed sound: " + soundName + " - " + e.getMessage());
                    }
                }
                sender.sendMessage(ChatColor.GREEN + "Broadcast " + played + "/" + testSounds.length + " sounds at your location via world.playSound (string).");
                break;
            }
            case "tokens": {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /revive tokens <player>");
                    return true;
                }
                OfflinePlayer t = this.resolveOffline(args[1]);
                if (t == null) {
                    sender.sendMessage(this.plugin.msg(sender, "player-not-found"));
                    return true;
                }
                String tm = this.plugin.getDataManager().getTokenMaterial(t.getUniqueId());
                if (tm == null) {
                    sender.sendMessage(this.plugin.msg(sender, "no-token").replace("%player%", this.nameOf(t)));
                    return true;
                }
                sender.sendMessage(ChatColor.GREEN + this.nameOf(t) + "'s revival item: " + tm);
                break;
            }
            case "sethearts": {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /revive sethearts <player> <amount>");
                    return true;
                }
                OfflinePlayer t = this.resolveOffline(args[1]);
                if (t == null) {
                    sender.sendMessage(this.plugin.msg(sender, "player-not-found"));
                    return true;
                }
                int hearts = this.parseInt(args[2], -1);
                if (hearts < 1) {
                    sender.sendMessage(ChatColor.RED + "Hearts must be >= 1.");
                    return true;
                }
                int appliedHearts = Math.min(hearts, this.plugin.getMaxHearts());
                DataManager data = this.plugin.getDataManager();
                int previousHearts = data.getHearts(t.getUniqueId());
                data.setHearts(t.getUniqueId(), appliedHearts);
                if (!data.saveSafely()) {
                    data.setHearts(t.getUniqueId(), previousHearts);
                    sender.sendMessage(ChatColor.RED + "Failed to save players.yml.");
                    return true;
                }
                Player online = t.getPlayer();
                if (online != null && online.isOnline()) {
                    online.setMaxHealth(this.plugin.maxHealthForHearts(appliedHearts));
                    online.setHealth(online.getMaxHealth());
                }
                sender.sendMessage(ChatColor.GREEN + "Set " + this.nameOf(t) + " hearts to " + appliedHearts);
                this.plugin.getAdminTools().record(sender.getName(), t, "SET_HEARTS", previousHearts + " -> " + appliedHearts, "SUCCESS");
                break;
            }
            case "revive": {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /revive revive <player>");
                    return true;
                }
                OfflinePlayer t = this.resolveOffline(args[1]);
                if (t == null) {
                    sender.sendMessage(this.plugin.msg(sender, "player-not-found"));
                    return true;
                }
                this.plugin.getAltarGui().forceRevive(t, sender instanceof Player ? (Player) sender : null);
                break;
            }
            case "allergy": {
                this.handleAllergy(sender, args);
                break;
            }
            default: {
                sender.sendMessage(this.plugin.msg(sender, "cmd.usage"));
            }
        }
        return true;
    }

    private void handleAllergy(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /revive allergy <give|remove|list> [player]");
            return;
        }
        HeartAllergyManager allergies = this.plugin.getHeartAllergyManager();
        if (allergies == null) {
            sender.sendMessage(ChatColor.RED + "Heart allergy manager is unavailable.");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            if (args.length != 2) { sender.sendMessage(ChatColor.RED + "Usage: /revive allergy list"); return; }
            if (allergies.allergicPlayers().isEmpty()) {
                sender.sendMessage(this.plugin.msg(sender, "heart-allergy.list-empty"));
                return;
            }
            List<String> names = new ArrayList<>();
            for (UUID uuid : allergies.allergicPlayers()) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                names.add(this.nameOf(player));
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            sender.sendMessage(this.plugin.msg(sender, "heart-allergy.list").replace("%players%", String.join(", ", names)));
            return;
        }
        if (!action.equals("give") && !action.equals("remove")) {
            sender.sendMessage(ChatColor.RED + "Usage: /revive allergy <give|remove|list> [player]");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /revive allergy <give|remove> <player>");
            return;
        }
        OfflinePlayer target = this.resolveOffline(args[2]);
        if (target == null) {
            sender.sendMessage(this.plugin.msg(sender, "player-not-found"));
            return;
        }
        boolean enable = action.equals("give");
        boolean wasAllergic = allergies.isAllergic(target.getUniqueId());
        if (wasAllergic == enable) {
            sender.sendMessage(this.plugin.msg(sender, enable ? "heart-allergy.already" : "heart-allergy.not-active")
                .replace("%player%", this.nameOf(target)));
            return;
        }
        if (!allergies.setAllergic(target.getUniqueId(), enable)) {
            sender.sendMessage(ChatColor.RED + "Failed to save allergies.yml.");
            return;
        }
        if (enable && target.getPlayer() != null && target.getPlayer().isOnline()) {
            allergies.scanPlayer(target.getPlayer());
        }
        sender.sendMessage(this.plugin.msg(sender, enable ? "heart-allergy.given" : "heart-allergy.removed")
            .replace("%player%", this.nameOf(target)));
        this.plugin.getAdminTools().record(sender.getName(), target, "ALLERGY", action, "SUCCESS");
    }

    private Player resolveOnline(CommandSender sender, String arg) {
        if (arg.startsWith("@")) {
            try {
                List<?> list = Bukkit.selectEntities(sender, arg);
                if (!list.isEmpty() && list.get(0) instanceof Player) {
                    return (Player) list.get(0);
                }
            } catch (Exception ignored) {}
            return null;
        }
        return Bukkit.getPlayerExact(arg);
    }

    private OfflinePlayer resolveOffline(String arg) {
        if (arg == null || arg.isBlank()) return null;
        Player online = Bukkit.getPlayerExact(arg);
        if (online != null) return online;
        try {
            java.util.UUID uuid = java.util.UUID.fromString(arg);
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            return player.hasPlayedBefore() || this.plugin.getDataManager().hasPlayerData(uuid) ? player : null;
        } catch (IllegalArgumentException ignored) {
            // Resolve a name only from the server's known offline-player cache.
        }
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() != null && player.getName().equalsIgnoreCase(arg)) {
                return player;
            }
        }
        return null;
    }

    private int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void handleEvent(CommandSender sender, String[] args) {
        var admin = this.plugin.getAdminTools();
        var event = this.plugin.getEventManager();
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(admin.text(sender, "event-status", "phase", event.phaseName(), "count", event.participantCount()));
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
            boolean guideStopped = event.stopGuide() | this.plugin.getRitualManager().clearHint();
            if (!event.isRunning()) {
                sender.sendMessage(admin.text(sender, guideStopped ? "guide-stopped" : "event-idle"));
                return;
            }
            event.stop(true);
            admin.record(sender.getName(), null, "EVENT_STOP", "border restored", "SUCCESS");
            sender.sendMessage(admin.text(sender, "event-stopped"));
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can start the event.");
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("preview")) {
            Player player = (Player) sender;
            List<String> participants = event.preview(player.getWorld());
            sender.sendMessage(admin.text(sender, "event-preview", "world", player.getWorld().getName(),
                "border", Math.round(player.getWorld().getWorldBorder().getSize()), "target", 10000,
                "radius", this.plugin.getConfig().getInt("event.spawn-radius", 0), "count", participants.size()));
            sender.sendMessage(admin.text(sender, "event-participants", "players", String.join(", ", participants)));
            if (event.isRunning()) sender.sendMessage(admin.text(sender, "event-running"));
            if (player.getWorld().getWorldBorder().getSize() > 10000) sender.sendMessage(admin.text(sender, "border-too-large"));
            sender.sendMessage(admin.text(sender, "radius-note"));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("skull")) {
            this.handleSkull((Player) sender, args);
            return;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("newbie") || args[0].equalsIgnoreCase("newbies"))) {
            if (args.length >= 2 && args[1].equalsIgnoreCase("particles")) {
                this.handleGuide((Player) sender, args);
                return;
            }
            if (args.length != 5 || !args[1].equalsIgnoreCase("start")) {
                sender.sendMessage("/event newbie start x y z"); return;
            }
            try {
                double x=Double.parseDouble(args[2]), y=Double.parseDouble(args[3]), z=Double.parseDouble(args[4]);
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x)>29999900 || Math.abs(z)>29999900
                    || y<((Player)sender).getWorld().getMinHeight() || y>((Player)sender).getWorld().getMaxHeight()-4)
                    throw new IllegalArgumentException();
                sender.sendMessage("Newbie event: " + event.startNewbies((Player)sender,
                    new Location(((Player)sender).getWorld(),x,y,z)));
            } catch (IllegalArgumentException error) { sender.sendMessage("/event newbie start x y z — invalid coordinates"); }
            return;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("start")) {
            sender.sendMessage(admin.text(sender, "event-usage"));
            return;
        }

        EventManager.StartResult result = this.plugin.getEventManager().start((Player) sender);
        switch (result) {
            case STARTED -> sender.sendMessage(ChatColor.GREEN + "Event started.");
            case ALREADY_RUNNING -> sender.sendMessage(ChatColor.YELLOW + "An event is already running.");
            case NO_PLAYERS -> sender.sendMessage(ChatColor.RED + "There are no eligible players in your world.");
            case BORDER_TOO_LARGE -> sender.sendMessage(ChatColor.RED + "This world's border is already larger than 10,000 blocks.");
            case SETUP_FAILED -> sender.sendMessage(ChatColor.RED + "The event could not be started. Check the server log.");
        }
        admin.record(sender.getName(), null, "EVENT_START", ((Player) sender).getWorld().getName(), result.name());
    }

    /**
     * /event skull <x y z|stop>: a translucent glowing bobbing skull at the point plus the same guide line as
     * /event newbie particles. Both disappear as soon as a skull ritual starts.
     */
    private void handleSkull(Player sender, String[] args) {
        var admin = this.plugin.getAdminTools();
        var event = this.plugin.getEventManager();
        var rituals = this.plugin.getRitualManager();
        if (args.length == 2 && args[1].equalsIgnoreCase("stop")) {
            boolean hint = rituals.clearHint();
            boolean guide = event.stopGuide();
            sender.sendMessage(admin.text(sender, hint || guide ? "skull-stopped" : "skull-idle"));
            return;
        }
        if (args.length != 4) { sender.sendMessage(admin.text(sender, "skull-usage")); return; }
        World world = sender.getWorld();
        Location target;
        try {
            double x = Double.parseDouble(args[1]), y = Double.parseDouble(args[2]), z = Double.parseDouble(args[3]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 29999900 || Math.abs(z) > 29999900
                || y < world.getMinHeight() || y > world.getMaxHeight()) {
                throw new IllegalArgumentException();
            }
            target = new Location(world, x, y, z);
        } catch (IllegalArgumentException error) {
            sender.sendMessage(admin.text(sender, "skull-invalid"));
            return;
        }
        rituals.showHint(target);
        int players = event.startGuide(world, target);
        admin.record(sender.getName(), null, "EVENT_SKULL", args[1] + " " + args[2] + " " + args[3], "STARTED");
        sender.sendMessage(admin.text(sender, "skull-started", "x", args[1], "y", args[2], "z", args[3], "count", players));
    }

    /** /event newbie particles <x y z|stop>: sparse black particles pointing every player in the sender's world to a point. */
    private void handleGuide(Player sender, String[] args) {
        var admin = this.plugin.getAdminTools();
        var event = this.plugin.getEventManager();
        if (args.length == 3 && args[2].equalsIgnoreCase("stop")) {
            sender.sendMessage(admin.text(sender, event.stopGuide() ? "guide-stopped" : "guide-idle"));
            return;
        }
        if (args.length != 5) { sender.sendMessage(admin.text(sender, "guide-usage")); return; }
        World world = sender.getWorld();
        Location target;
        try {
            double x = Double.parseDouble(args[2]), y = Double.parseDouble(args[3]), z = Double.parseDouble(args[4]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 29999900 || Math.abs(z) > 29999900
                || y < world.getMinHeight() || y > world.getMaxHeight()) {
                throw new IllegalArgumentException();
            }
            target = new Location(world, x, y, z);
        } catch (IllegalArgumentException error) {
            sender.sendMessage(admin.text(sender, "guide-invalid"));
            return;
        }
        int players = event.startGuide(world, target);
        admin.record(sender.getName(), null, "EVENT_GUIDE", args[2] + " " + args[3] + " " + args[4], "STARTED");
        sender.sendMessage(admin.text(sender, "guide-started", "x", args[2], "y", args[3], "z", args[4], "count", players));
    }

    private String nameOf(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return this.complete(sender, command.getName(), args);
    }

    /** /newbie entering <on|off|status>: whether first-time joiners are held in Limbo. */
    private void handleNewbie(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("entering")
            || args.length > 2) {
            sender.sendMessage(ChatColor.YELLOW + "/newbie entering <on|off|status>");
            return;
        }
        String mode = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (mode) {
            case "on", "off" -> {
                boolean enabled = mode.equals("on");
                if (!this.plugin.setNewbieEntering(enabled)) {
                    sender.sendMessage(ChatColor.RED + "Could not save config.yml; check the server log.");
                    return;
                }
                sender.sendMessage(ChatColor.GREEN + "Newbie entering is now " + (enabled ? "ON" : "OFF")
                    + (enabled ? ": new players wait in Limbo." : ": new players spawn normally."));
            }
            case "status" -> sender.sendMessage(ChatColor.YELLOW + "Newbie entering is "
                + (this.plugin.isNewbieEntering() ? "ON" : "OFF") + ".");
            default -> sender.sendMessage(ChatColor.YELLOW + "/newbie entering <on|off|status>");
        }
    }

    private List<String> complete(CommandSender sender, String commandName, String[] args) {
        if (!sender.hasPermission("smprevive.admin")) return List.of();
        if (commandName.equalsIgnoreCase("newbie")) {
            String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> choices = args.length <= 1 ? List.of("entering")
                : args.length == 2 ? List.of("on", "off", "status") : List.of();
            return choices.stream().filter(choice -> choice.startsWith(prefix)).toList();
        }
        return CommandSuggestions.complete(commandName, args, Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
    }
}

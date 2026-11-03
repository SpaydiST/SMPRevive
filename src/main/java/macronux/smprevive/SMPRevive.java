package macronux.smprevive;

import macronux.smprevive.advancement.AdvancementManager;
import macronux.smprevive.admin.AdminTools;
import macronux.smprevive.admin.PerformanceCounters;
import macronux.smprevive.allergy.HeartAllergyManager;
import macronux.smprevive.command.Commands;
import macronux.smprevive.config.LocaleManager;
import macronux.smprevive.config.SoundConfig;
import macronux.smprevive.event.EventManager;
import macronux.smprevive.event.EventSpawnLocator;
import macronux.smprevive.gui.CasinoGui;
import macronux.smprevive.item.ItemManager;
import macronux.smprevive.recovery.RecoveryJournal;
import macronux.smprevive.rules.ReviveRules;
import macronux.smprevive.protection.ProtectedItemService;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Plugin composition root and compatibility facade for the documented SMPRevive v1 hooks. */
public final class SMPRevive extends JavaPlugin {
    private DataManager dataManager;
    private ItemManager itemManager;
    private CasinoGui casinoGui;
    private AltarGui altarGui;
    private LocaleManager localeManager;
    private SoundConfig soundConfig;
    private AdvancementManager advancementManager;
    private ReviveListener reviveListener;
    private HeartAllergyManager heartAllergyManager;
    private RecoveryJournal recoveryJournal;
    private EventSpawnLocator eventSpawnLocator;
    private EventManager eventManager;
    private AdminTools adminTools;
    private ProtectedItemService protectedItemService;
    private final PerformanceCounters performanceCounters = new PerformanceCounters();
    private BukkitTask altarDisplaySyncTask;
    private int minHearts;
    private int maxHearts;
    private int startHearts;
    private int tokensRequired;
    private int casinoDurationTicks;
    private int casinoPeriodTicks;
    private Material altarBlockMaterial;
    private Material altarHitboxMaterial;
    private Material heartMaterial;
    private Material altarItemMaterial;
    private GameMode defaultGameMode;
    private int spawnRadius;
    private boolean freeFirstDeath;
    private final List<Material> casinoPool = new ArrayList<>();
    private List<String> disabledWorlds = new ArrayList<>();
    private final Set<String> invalidSoundWarnings = new HashSet<>();

    public void onEnable() {
        this.saveDefaultConfig();
        this.loadSettings();
        this.localeManager = new LocaleManager(this);
        this.localeManager.load();
        this.advancementManager = new AdvancementManager(this);
        this.soundConfig = new SoundConfig(this);
        this.dataManager = new DataManager(this);
        if (this.dataManager.isLoadFailed()) {
            this.getLogger().severe("SMPRevive is disabled because players.yml could not be loaded safely.");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.recoveryJournal = new RecoveryJournal(this);
        this.itemManager = new ItemManager(this);
        this.protectedItemService = new ProtectedItemService(this);
        if (this.protectedItemService.isLoadFailed()) {
            getLogger().severe("Protected delivery data could not be loaded; refusing to discard pending items.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.eventSpawnLocator = new EventSpawnLocator(this);
        this.casinoGui = new CasinoGui(this);
        this.altarGui = new AltarGui(this);
        this.reviveListener = new ReviveListener(this);
        this.heartAllergyManager = new HeartAllergyManager(this);
        this.eventManager = new EventManager(this);
        this.adminTools = new AdminTools(this);
        this.itemManager.registerAltarRecipe();
        this.getServer().getPluginManager().registerEvents(this.adminTools, this);
        this.reviveListener.registerHandlers();
        getServer().getPluginManager().registerEvents(protectedItemService,this);
        protectedItemService.start();
        this.getServer().getPluginManager().registerEvents((Listener) this.eventManager, (Plugin) this);
        this.heartAllergyManager.start();
        this.recoveryJournal.recoverAll(this.dataManager);
        Commands commands = new Commands(this);
        this.registerCommand("reviveitem", commands.asBasicCommand("reviveitem"));
        this.registerCommand("revive", commands.asBasicCommand("revive"));
        this.registerCommand("event", commands.asBasicCommand("event"));
        Bukkit.getScheduler().runTask(this, () -> {
            this.reviveListener.initializeOnlinePlayers();
            this.reviveListener.syncAltarDisplays();
        });
        this.altarDisplaySyncTask = Bukkit.getScheduler().runTaskTimer(
            this,
            () -> this.reviveListener.syncMissingAltarDisplays(),
            100L,
            100L
        );
        this.getLogger().info("SMPRevive v" + this.getPluginMeta().getVersion() + " enabled!");
    }

    public void onDisable() {
        if (itemManager != null) Bukkit.removeRecipe(itemManager.altarRecipeKey());
        if (protectedItemService != null) protectedItemService.shutdown();
        if (this.adminTools != null) this.adminTools.closeMenus();
        if (this.eventManager != null) {
            this.eventManager.stop(true);
        }
        if (this.altarDisplaySyncTask != null) {
            this.altarDisplaySyncTask.cancel();
            this.altarDisplaySyncTask = null;
        }
        if (this.reviveListener != null) {
            this.reviveListener.shutdown();
        }
        if (this.heartAllergyManager != null) {
            this.heartAllergyManager.shutdown();
        }
        if (this.casinoGui != null) {
            this.casinoGui.shutdown();
        }
        if (this.dataManager != null) {
            if (!this.dataManager.saveSafely()) {
                this.getLogger().warning("SMPRevive disabled with unsaved player data");
            }
        }
    }

    public void loadSettings() {
        this.reloadConfig();
        if (soundConfig != null) {
            soundConfig.reload();
        }
        this.minHearts = this.getConfig().getInt("min-hearts", 5);
        this.maxHearts = this.getConfig().getInt("max-hearts", 20);
        this.startHearts = this.getConfig().getInt("start-hearts", 10);
        ReviveRules.HeartRange hearts = ReviveRules.normalizeHearts(this.minHearts, this.startHearts, this.maxHearts);
        this.minHearts = hearts.min();
        this.startHearts = hearts.start();
        this.maxHearts = hearts.max();
        this.tokensRequired = ReviveRules.normalizeTokensRequired(this.getConfig().getInt("tokens-required", 8));
        ReviveRules.TimerRange timing = ReviveRules.normalizeCasinoTiming(
            this.getConfig().getInt("casino-duration-ticks", 60),
            this.getConfig().getInt("casino-period-ticks", 2)
        );
        this.casinoDurationTicks = timing.durationTicks();
        this.casinoPeriodTicks = timing.periodTicks();
        boolean configChanged = false;
        if (this.getConfig().getInt("min-hearts", this.minHearts) != this.minHearts) {
            this.getConfig().set("min-hearts", this.minHearts);
            configChanged = true;
        }
        if (this.getConfig().getInt("start-hearts", this.startHearts) != this.startHearts) {
            this.getConfig().set("start-hearts", this.startHearts);
            configChanged = true;
        }
        if (this.getConfig().getInt("max-hearts", this.maxHearts) != this.maxHearts) {
            this.getConfig().set("max-hearts", this.maxHearts);
            configChanged = true;
        }
        if (this.getConfig().getInt("tokens-required", this.tokensRequired) != this.tokensRequired) {
            this.getConfig().set("tokens-required", this.tokensRequired);
            configChanged = true;
        }
        String configuredAltarBlock = this.getConfig().getString("altar-block-material", "BEACON");
        this.altarBlockMaterial = this.safeMaterial(configuredAltarBlock, Material.BEACON, true);
        if (!sameMaterialName(configuredAltarBlock, this.altarBlockMaterial)) {
            this.getConfig().set("altar-block-material", this.altarBlockMaterial.name());
            configChanged = true;
        }
        String altarHitboxName = this.getConfig().getString("altar-hitbox-material", "BARRIER");
        this.altarHitboxMaterial = this.safeMaterial(altarHitboxName, Material.BARRIER, true);
        if (!sameMaterialName(altarHitboxName, this.altarHitboxMaterial)) {
            this.getConfig().set("altar-hitbox-material", this.altarHitboxMaterial.name());
            configChanged = true;
        }
        String configuredAltarItem = this.getConfig().getString("altar-item-material", "BEACON");
        this.altarItemMaterial = this.safeMaterial(configuredAltarItem, Material.BEACON, false);
        if (!sameMaterialName(configuredAltarItem, this.altarItemMaterial)) {
            this.getConfig().set("altar-item-material", this.altarItemMaterial.name());
            configChanged = true;
        }
        String configuredHeart = this.getConfig().getString("heart-material", "HEART_OF_THE_SEA");
        this.heartMaterial = this.safeMaterial(configuredHeart, Material.HEART_OF_THE_SEA, false);
        if (!sameMaterialName(configuredHeart, this.heartMaterial)) {
            this.getConfig().set("heart-material", this.heartMaterial.name());
            configChanged = true;
        }
        this.spawnRadius = ReviveRules.normalizeSpawnRadius(this.getConfig().getInt("spawn-radius", 15));
        this.freeFirstDeath = this.getConfig().getBoolean("free-first-death", true);
        try {
            String configuredGameMode = this.getConfig().getString("default-gamemode", "SURVIVAL");
            this.defaultGameMode = GameMode.valueOf(
                (configuredGameMode == null ? "SURVIVAL" : configuredGameMode.trim()).toUpperCase()
            );
        } catch (IllegalArgumentException e) {
            this.defaultGameMode = GameMode.SURVIVAL;
        }
        this.casinoPool.clear();
        for (String s : this.getConfig().getStringList("casino-pool")) {
            Material m = s == null ? null : Material.matchMaterial(s.trim());
            if (isUsableMaterial(m, false)) {
                this.casinoPool.add(m);
            }
        }
        if (this.casinoPool.isEmpty()) {
            this.casinoPool.add(Material.DIAMOND);
            this.casinoPool.add(Material.EMERALD);
            this.casinoPool.add(Material.GOLD_INGOT);
            this.casinoPool.add(Material.IRON_INGOT);
            this.casinoPool.add(Material.NETHERITE_INGOT);
        }
        this.disabledWorlds = new ArrayList<>(this.getConfig().getStringList("disabled-worlds"));
        if (configChanged) {
            if (!this.saveConfigSafely()) {
                this.getLogger().warning("Failed to persist normalized config.yml");
            }
        }
        if (this.localeManager != null) {
            this.localeManager.setDefaultLocale(this.getConfig().getString("default-locale", "en_us"));
        }
        if (this.itemManager != null) {
            this.itemManager.clearTemplates();
            this.itemManager.registerAltarRecipe();
        }
        if (this.dataManager != null) this.dataManager.reloadAltarIndex();
    }

    public ProtectedItemService getProtectedItemService() { return protectedItemService; }

    private Material safeMaterial(String name, Material fallback, boolean block) {
        Material m = name == null ? null : Material.matchMaterial(name.trim());
        return isUsableMaterial(m, block) ? m : fallback;
    }

    private boolean isUsableMaterial(Material material, boolean block) {
        return material != null
            && material != Material.AIR
            && (block ? material.isBlock() : material.isItem());
    }

    private boolean sameMaterialName(String configured, Material material) {
        if (configured == null || material == null) return false;
        String value = configured.trim();
        return material.name().equalsIgnoreCase(value)
            || material.getKey().toString().equalsIgnoreCase(value);
    }

    public String msg(String key) {
        if (localeManager != null) return localeManager.getMessage(key);
        return key;
    }

    public String msg(Player player, String key) {
        if (localeManager != null) return localeManager.getMessage(player, key);
        return msg(key);
    }

    public String msg(CommandSender sender, String key) {
        if (localeManager != null) return localeManager.getMessage(sender, key);
        return msg(key);
    }

    public List<String> lore(String key) {
        if (localeManager != null) return localeManager.getMessageList(key);
        return new ArrayList<>();
    }

    public List<String> lore(Player player, String key) {
        if (localeManager != null) return localeManager.getMessageList(player, key);
        return lore(key);
    }

    public List<String> lore(CommandSender sender, String key) {
        if (localeManager != null) return localeManager.getMessageList(sender, key);
        return lore(key);
    }

    public DataManager getDataManager() { return this.dataManager; }
    public ItemManager getItemManager() { return this.itemManager; }
    public CasinoGui getCasinoGui() { return this.casinoGui; }
    public AltarGui getAltarGui() { return this.altarGui; }
    public LocaleManager getLocaleManager() { return this.localeManager; }
    public SoundConfig getSoundConfig() { return this.soundConfig; }
    public AdvancementManager getAdvancementManager() { return this.advancementManager; }
    public RecoveryJournal getRecoveryJournal() { return this.recoveryJournal; }
    public HeartAllergyManager getHeartAllergyManager() { return this.heartAllergyManager; }
    public EventSpawnLocator getEventSpawnLocator() { return this.eventSpawnLocator; }
    public EventManager getEventManager() { return this.eventManager; }
    public AdminTools getAdminTools() { return this.adminTools; }
    public PerformanceCounters getPerformanceCounters() { return this.performanceCounters; }
    // These reflection entry points are consumed by SpaydisCore; keep their names and signatures stable.
    public int getIntegrationApiVersion() { return 1; }
    public boolean hasPlayerData(UUID uuid) {
        return this.dataManager != null && this.dataManager.hasPlayerData(uuid);
    }
    public boolean isPlayerDead(UUID uuid) {
        return this.dataManager != null && this.dataManager.isDead(uuid);
    }
    public boolean isFirstDeathPending(Player player) {
        return this.freeFirstDeath
            && player != null
            && this.dataManager != null
            && !this.dataManager.hasDied(player.getUniqueId());
    }
    public boolean reviveFromLimbo(Player target, Location spawnLocation) {
        return Bukkit.isPrimaryThread()
            && this.altarGui != null
            && this.dataManager != null
            && this.altarGui.reviveFromLimbo(target, spawnLocation);
    }
    public int getMinHearts() { return this.minHearts; }
    public int getMaxHearts() { return this.maxHearts; }
    public double maxHealthForHearts(int hearts) {
        return Math.max(2.0, ReviveRules.clampPlayerHearts(hearts, this.maxHearts) * 2.0);
    }
    public int getStartHearts() { return this.startHearts; }
    public int getTokensRequired() { return this.tokensRequired; }
    public int getCasinoDurationTicks() { return this.casinoDurationTicks; }
    public int getCasinoPeriodTicks() { return this.casinoPeriodTicks; }
    public Material getAltarBlockMaterial() { return this.altarBlockMaterial; }
    public Material getAltarHitboxMaterial() { return this.altarHitboxMaterial; }
    public boolean isAltarBlockMaterial(Material material) {
        return material != null && (material == this.altarBlockMaterial || material == this.altarHitboxMaterial);
    }
    public Material getHeartMaterial() { return this.heartMaterial; }
    public Material getAltarItemMaterial() { return this.altarItemMaterial; }
    public GameMode getDefaultGameMode() { return this.defaultGameMode; }
    public int getSpawnRadius() { return this.spawnRadius; }
    public boolean isFreeFirstDeath() { return this.freeFirstDeath; }
    public List<Material> getCasinoPool() { return List.copyOf(this.casinoPool); }
    public List<String> getDisabledWorlds() { return List.copyOf(this.disabledWorlds); }

    public boolean isMechanicsDisabled(World world) {
        if (world == null) return true;
        for (String disabled : this.disabledWorlds) {
            if (disabled != null && disabled.trim().equalsIgnoreCase(world.getName())) return true;
        }
        return false;
    }

    public boolean isMechanicsDisabled(Location location) {
        return location == null || this.isMechanicsDisabled(location.getWorld());
    }

    public ReviveListener getReviveListener() { return this.reviveListener; }

    public void reloadRuntime() {
        if (this.adminTools != null) this.adminTools.closeMenus();
        if (this.eventManager != null) {
            this.eventManager.stop(true);
        }
        if (this.reviveListener != null) {
            this.reviveListener.shutdownForReload();
        }
        if (this.casinoGui != null) {
            this.casinoGui.shutdown();
        }
        try {
            this.loadSettings();
            if (this.localeManager != null) {
                this.localeManager.load();
            }
        } catch (RuntimeException error) {
            this.getLogger().severe("Reload failed, restoring runtime with the previous settings: " + error);
        }
        if (this.recoveryJournal != null && this.dataManager != null) {
            this.recoveryJournal.recoverAll(this.dataManager);
        }
        if (this.reviveListener != null) {
            this.reviveListener.initializeOnlinePlayers();
            this.reviveListener.syncAltarDisplays();
        }
    }

    public boolean saveConfigSafely() {
        File configFile = new File(this.getDataFolder(), "config.yml");
        File temporary = new File(this.getDataFolder(), "config.yml.tmp");
        try {
            this.getConfig().save(temporary);
            try {
                Files.move(
                    temporary.toPath(),
                    configFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            this.getLogger().warning("Failed to save config.yml: " + ex.getMessage());
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException ignored) {
                // Preserve the last valid config if cleanup is unavailable.
            }
            return false;
        }
    }

    public String getMaterialName(CommandSender sender, String materialKey) {
        if (localeManager != null) {
            return localeManager.getMessage(sender, "materials." + materialKey);
        }
        return materialKey;
    }

    public String getMaterialName(Player player, String materialKey) {
        if (localeManager != null) {
            return localeManager.getMessage(player, "materials." + materialKey);
        }
        return materialKey;
    }

    public void playSound(Location loc, String configPath) {
        if (loc == null || loc.getWorld() == null || soundConfig == null || !soundConfig.isEnabled()) return;
        if (!soundConfig.hasSound(configPath)) return;
        String sound = soundConfig.getSound(configPath);
        float volume = (float) soundConfig.getVolume(configPath);
        float pitch = soundConfig.getPitch(configPath);
        try {
            loc.getWorld().playSound(loc, sound, SoundCategory.MASTER, volume, pitch);
        } catch (IllegalArgumentException ex) {
            warnInvalidSound(configPath, sound, ex);
        }
    }

    public void playSoundToNearby(Location loc, String configPath) {
        if (loc == null || loc.getWorld() == null || soundConfig == null || !soundConfig.isEnabled()) return;
        if (!soundConfig.hasSound(configPath)) return;
        String sound = soundConfig.getSound(configPath);
        float volume = (float) soundConfig.getVolume(configPath);
        float pitch = soundConfig.getPitch(configPath);
        try {
            loc.getWorld().playSound(loc, sound, SoundCategory.MASTER, volume, pitch);
        } catch (IllegalArgumentException ex) {
            warnInvalidSound(configPath, sound, ex);
        }
    }

    public void playSoundToPlayer(Player player, String configPath) {
        if (player == null || !player.isOnline() || soundConfig == null || !soundConfig.isEnabled()) return;
        if (!soundConfig.hasSound(configPath)) return;
        String sound = soundConfig.getSound(configPath);
        float volume = (float) soundConfig.getVolume(configPath);
        float pitch = soundConfig.getPitch(configPath);
        try {
            player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
        } catch (IllegalArgumentException ex) {
            warnInvalidSound(configPath, sound, ex);
        }
    }

    private void warnInvalidSound(String path, String sound, IllegalArgumentException exception) {
        String key = path + "=" + sound;
        if (this.invalidSoundWarnings.add(key)) {
            this.getLogger().warning("Ignoring invalid sound '" + sound + "' at " + path + ": " + exception.getMessage());
        }
    }
}

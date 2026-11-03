package macronux.smprevive.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class SoundConfig {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private File configFile;
    private boolean enabled;
    private double masterVolume;

    public SoundConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        configFile = new File(plugin.getDataFolder(), "sounds.yml");
        if (!configFile.exists()) {
            plugin.saveResource("sounds.yml", false);
        }
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(configFile);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            // Never merge defaults into (and so overwrite) a file the admin may still want to repair.
            plugin.getLogger().warning("Cannot read sounds.yml, keeping previous sound settings: " + e.getMessage());
            if (config == null) config = loaded;
            enabled = config.getBoolean("enabled", true);
            masterVolume = clamp(config.getDouble("master-volume", 1.0), 0.0, 2.0);
            return;
        }
        config = loaded;
        applyDefaults();

        enabled = config.getBoolean("enabled", true);
        masterVolume = clamp(config.getDouble("master-volume", 1.0), 0.0, 2.0);
    }

    public void reload() {
        load();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getMasterVolume() {
        return masterVolume;
    }

    public double getVolume(String path) {
        if (!enabled) return 0.0;
        double v = clamp(config.getDouble(path + ".volume", 1.0), 0.0, 2.0);
        return clamp(v * masterVolume, 0.0, 4.0);
    }

    public float getPitch(String path) {
        return (float) clamp(config.getDouble(path + ".pitch", 1.0), 0.5, 2.0);
    }

    public String getSound(String path) {
        return config.getString(path + ".sound", "");
    }

    public boolean hasSound(String path) {
        return config.isString(path + ".sound") && !config.getString(path + ".sound", "").isBlank();
    }

    public Map<String, Object> getSoundData(String path) {
        Map<String, Object> data = new HashMap<>();
        if (config.isConfigurationSection(path)) {
            org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection(path);
            if (section != null) {
                data.put("sound", section.getString("sound", ""));
                data.put("volume", section.getDouble("volume", 1.0) * masterVolume);
                data.put("pitch", (float) section.getDouble("pitch", 1.0));
            }
        }
        return data;
    }

    public void saveDefault() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save sounds.yml: " + e.getMessage());
        }
    }

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private void applyDefaults() {
        try (InputStream input = plugin.getResource("sounds.yml")) {
            if (input == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(input, StandardCharsets.UTF_8)
            );
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to merge sounds.yml defaults: " + e.getMessage());
        }
    }
}

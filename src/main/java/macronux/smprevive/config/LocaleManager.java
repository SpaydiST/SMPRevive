package macronux.smprevive.config;

import macronux.smprevive.SMPRevive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class LocaleManager {
    private final SMPRevive plugin;
    private final Map<String, JsonObject> locales = new HashMap<>();
    private String defaultLocale;

    public LocaleManager(SMPRevive plugin) {
        this.plugin = plugin;
        this.defaultLocale = plugin.getConfig().getString("default-locale", "en_us").toLowerCase();
    }

    public void load() {
        this.setDefaultLocale(this.plugin.getConfig().getString("default-locale", "en_us"));
        extractLangFile("lang/en_us.json");
        extractLangFile("lang/ru_ru.json");
        // Parse into a scratch map so a corrupt file on reload keeps the previously loaded locale.
        Map<String, JsonObject> loaded = new HashMap<>();
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (langDir.exists()) {
            File[] files = langDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".json")) {
                        String locale = f.getName().replace(".json", "").toLowerCase();
                        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                            JsonObject obj = new Gson().fromJson(r, JsonObject.class);
                            if (obj != null) {
                                loaded.put(locale, obj);
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to load locale: " + f.getName());
                            JsonObject previous = locales.get(locale);
                            if (previous != null) loaded.put(locale, previous);
                        }
                    }
                }
            }
        }
        locales.clear();
        locales.putAll(loaded);
        plugin.getLogger().info("Loaded " + locales.size() + " locale(s). Default: " + defaultLocale);
    }

    private void extractLangFile(String path) {
        File langDir = new File(plugin.getDataFolder(), "lang");
        File target = new File(langDir, new File(path).getName());
        langDir.mkdirs();
        try (InputStream is = plugin.getResource(path)) {
            if (is == null) return;
            byte[] data = is.readAllBytes();
            JsonObject defaults = new Gson().fromJson(new String(data, StandardCharsets.UTF_8), JsonObject.class);
            if (!target.exists()) {
                try (OutputStream os = new FileOutputStream(target)) {
                    os.write(data);
                }
                return;
            }

            JsonObject current;
            try (Reader reader = new InputStreamReader(new FileInputStream(target), StandardCharsets.UTF_8)) {
                current = new Gson().fromJson(reader, JsonObject.class);
            }
            if (current == null) current = new JsonObject();
            if (mergeMissing(current, defaults)) {
                Gson pretty = new GsonBuilder().setPrettyPrinting().create();
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
                    pretty.toJson(current, writer);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to extract: " + path);
        }
    }

    private boolean mergeMissing(JsonObject target, JsonObject defaults) {
        if (defaults == null) return false;
        boolean changed = false;
        for (Map.Entry<String, JsonElement> entry : defaults.entrySet()) {
            String key = entry.getKey();
            JsonElement defaultValue = entry.getValue();
            if (!target.has(key)) {
                target.add(key, defaultValue.deepCopy());
                changed = true;
            } else if (target.get(key).isJsonObject() && defaultValue.isJsonObject()) {
                changed |= mergeMissing(target.getAsJsonObject(key), defaultValue.getAsJsonObject());
            }
        }
        return changed;
    }

    private String resolveLocale(Player player) {
        if (player == null) return defaultLocale;
        String loc = player.getLocale();
        if (loc == null || loc.isEmpty()) return defaultLocale;
        return loc.toLowerCase();
    }

    private String resolveLocale(CommandSender sender) {
        if (sender instanceof Player) return resolveLocale((Player) sender);
        return defaultLocale;
    }

    private String lookup(String locale, String key) {
        JsonObject obj = locales.get(locale);
        JsonElement el = find(obj, key);
        if (el != null) {
            if (el.isJsonPrimitive()) {
                return ChatColor.translateAlternateColorCodes('&', el.getAsString());
            }
        }
        if (!locale.equals(defaultLocale)) {
            return lookup(defaultLocale, key);
        }
        return key;
    }

    private List<String> lookupList(String locale, String key) {
        JsonObject obj = locales.get(locale);
        JsonElement el = find(obj, key);
        if (el != null) {
            if (el.isJsonArray()) {
                List<String> result = new ArrayList<>();
                for (JsonElement e : el.getAsJsonArray()) {
                    if (!e.isJsonPrimitive()) continue;
                    result.add(ChatColor.translateAlternateColorCodes('&', e.getAsString()));
                }
                return result;
            }
        }
        if (!locale.equals(defaultLocale)) {
            return lookupList(defaultLocale, key);
        }
        return Collections.singletonList(key);
    }

    private JsonElement find(JsonObject object, String key) {
        if (object == null || key == null) return null;
        if (object.has(key)) return object.get(key);
        JsonElement current = object;
        for (String part : key.split("\\.")) {
            if (!current.isJsonObject() || !current.getAsJsonObject().has(part)) return null;
            current = current.getAsJsonObject().get(part);
        }
        return current;
    }

    public String getMessage(Player player, String key) {
        return lookup(resolveLocale(player), key);
    }

    public String getMessage(CommandSender sender, String key) {
        return lookup(resolveLocale(sender), key);
    }

    public String getMessage(String key) {
        return lookup(defaultLocale, key);
    }

    public List<String> getMessageList(Player player, String key) {
        return lookupList(resolveLocale(player), key);
    }

    public List<String> getMessageList(CommandSender sender, String key) {
        return lookupList(resolveLocale(sender), key);
    }

    public List<String> getMessageList(String key) {
        return lookupList(defaultLocale, key);
    }

    public void setDefaultLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            this.defaultLocale = "en_us";
        } else {
            this.defaultLocale = locale.toLowerCase(Locale.ROOT);
        }
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public Set<String> getAvailableLocales() {
        return locales.keySet();
    }
}

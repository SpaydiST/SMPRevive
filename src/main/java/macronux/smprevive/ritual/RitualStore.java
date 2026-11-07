package macronux.smprevive.ritual;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import macronux.smprevive.data.AtomicStateFile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Persistent state of the skull ritual: finished shrines (they survive restarts) and rituals that are still
 * running, so a crash can give the skull and the donated heart back.
 */
public final class RitualStore {
    /** A finished shrine; the coordinates are the centre of the floating head. */
    public record Shrine(String id, UUID world, double x, double y, double z) {}

    /** A running ritual: the altar block and the donor whose heart was taken, if any. */
    public record Active(String id, UUID world, int x, int y, int z, UUID donor) {}

    private final Logger logger;
    private final Map<String, Shrine> shrines = new LinkedHashMap<>();
    private final Map<String, Active> actives = new LinkedHashMap<>();
    private AtomicStateFile state;
    private boolean loadFailed;

    public RitualStore(File dataFolder, Logger logger) {
        this.logger = logger;
        File file = new File(dataFolder, "shrines.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            if (file.isFile()) yaml.load(file);
            read(yaml);
            state = new AtomicStateFile(file.toPath(), file.isFile() ? write() : null);
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            loadFailed = true;
            logger.warning("Failed to load shrines.yml; refusing to overwrite it: " + exception.getMessage());
        }
    }

    public boolean isLoadFailed() {
        return loadFailed;
    }

    public synchronized List<Shrine> shrines() {
        return new ArrayList<>(shrines.values());
    }

    public synchronized List<Active> actives() {
        return new ArrayList<>(actives.values());
    }

    public synchronized Active active(String id) {
        return actives.get(id);
    }

    public synchronized boolean addActive(Active active) {
        actives.put(active.id(), active);
        if (save()) return true;
        actives.remove(active.id());
        return false;
    }

    public synchronized boolean setDonor(String id, UUID donor) {
        Active before = actives.get(id);
        if (before == null) return false;
        actives.put(id, new Active(id, before.world(), before.x(), before.y(), before.z(), donor));
        if (save()) return true;
        actives.put(id, before);
        return false;
    }

    public synchronized boolean removeActive(String id) {
        Active before = actives.remove(id);
        if (before == null) return true;
        if (save()) return true;
        actives.put(id, before);
        return false;
    }

    /** Turns a running ritual into a shrine in one write. */
    public synchronized boolean complete(String activeId, Shrine shrine) {
        Active before = actives.remove(activeId);
        shrines.put(shrine.id(), shrine);
        if (save()) return true;
        shrines.remove(shrine.id());
        if (before != null) actives.put(activeId, before);
        return false;
    }

    public synchronized boolean removeShrine(String id) {
        Shrine before = shrines.remove(id);
        if (before == null) return true;
        if (save()) return true;
        shrines.put(id, before);
        return false;
    }

    private boolean save() {
        if (loadFailed || state == null) return false;
        try {
            state.save(write());
            return true;
        } catch (IOException | RuntimeException exception) {
            logger.warning("Failed to save shrines.yml: " + exception.getMessage());
            return false;
        }
    }

    private String write() {
        YamlConfiguration out = new YamlConfiguration();
        for (Shrine shrine : shrines.values()) {
            String path = "shrines." + shrine.id();
            out.set(path + ".world", shrine.world().toString());
            out.set(path + ".x", shrine.x());
            out.set(path + ".y", shrine.y());
            out.set(path + ".z", shrine.z());
        }
        for (Active active : actives.values()) {
            String path = "active." + active.id();
            out.set(path + ".world", active.world().toString());
            out.set(path + ".x", active.x());
            out.set(path + ".y", active.y());
            out.set(path + ".z", active.z());
            if (active.donor() != null) out.set(path + ".donor", active.donor().toString());
        }
        return out.saveToString();
    }

    private void read(YamlConfiguration yaml) {
        ConfigurationSection shrineSection = yaml.getConfigurationSection("shrines");
        if (shrineSection != null) {
            for (String id : shrineSection.getKeys(false)) {
                ConfigurationSection s = shrineSection.getConfigurationSection(id);
                if (s == null) continue;
                shrines.put(id, new Shrine(id, UUID.fromString(s.getString("world", "")),
                    s.getDouble("x"), s.getDouble("y"), s.getDouble("z")));
            }
        }
        ConfigurationSection activeSection = yaml.getConfigurationSection("active");
        if (activeSection != null) {
            for (String id : activeSection.getKeys(false)) {
                ConfigurationSection s = activeSection.getConfigurationSection(id);
                if (s == null) continue;
                String donor = s.getString("donor");
                actives.put(id, new Active(id, UUID.fromString(s.getString("world", "")),
                    s.getInt("x"), s.getInt("y"), s.getInt("z"), donor == null ? null : UUID.fromString(donor)));
            }
        }
    }
}

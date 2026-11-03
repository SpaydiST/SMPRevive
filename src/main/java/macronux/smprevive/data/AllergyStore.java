package macronux.smprevive.data;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Persistent allergy membership; a failed read must never become an empty successful save. */
public final class AllergyStore {
    private final File file;
    private final Logger logger;
    private final YamlConfiguration yaml = new YamlConfiguration();
    private final Set<UUID> players = new LinkedHashSet<>();
    private boolean loadFailed;

    public AllergyStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        if (!file.exists()) return;
        try {
            yaml.load(file);
            if (yaml.contains("heart-allergic") && !yaml.isList("heart-allergic")) {
                throw new IllegalArgumentException("heart-allergic must be a list of UUIDs");
            }
            boolean malformed = false;
            for (String value : yaml.getStringList("heart-allergic")) {
                try {
                    players.add(UUID.fromString(value));
                } catch (IllegalArgumentException invalid) {
                    malformed = true;
                    logger.warning("Ignoring malformed allergy UUID: " + value);
                }
            }
            // Keep every valid entry active, but block writes so the unreadable one is not silently dropped.
            if (malformed) loadFailed = true;
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            loadFailed = true;
            logger.warning("Cannot safely load allergies.yml; writes disabled: " + exception.getMessage());
        }
    }

    public boolean isAllergic(UUID player) { return players.contains(player); }
    public Set<UUID> players() { return Set.copyOf(players); }

    public boolean setAllergic(UUID player, boolean allergic) {
        if (player == null || loadFailed) return false;
        boolean previous = players.contains(player);
        if (previous == allergic) return true;
        if (allergic) players.add(player); else players.remove(player);
        if (save()) return true;
        if (previous) players.add(player); else players.remove(player);
        return false;
    }

    public boolean save() {
        if (loadFailed) return false;
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        yaml.set("heart-allergic", players.stream().map(UUID::toString).toList());
        try {
            yaml.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            logger.warning("Failed to save allergies.yml: " + exception.getMessage());
            try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { }
            return false;
        }
    }
}

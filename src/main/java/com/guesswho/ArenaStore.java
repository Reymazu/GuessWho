package com.guesswho;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Menyimpan posisi kamera dan kursi ke arena.yml */
public final class ArenaStore {

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration yml;

    ArenaStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arena.yml");
        load();
    }

    void load() {
        yml = YamlConfiguration.loadConfiguration(file);
    }

    private void save() {
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Gagal menyimpan arena.yml: " + e.getMessage());
        }
    }

    Location getCamera() {
        Location l = yml.getLocation("camera");
        return (l != null && l.getWorld() != null) ? l : null;
    }

    void setCamera(Location l) {
        yml.set("camera", l);
        save();
    }

    void setSeat(int number, Location l) {
        yml.set("seats." + number, l);
        save();
    }

    void clearSeats() {
        yml.set("seats", null);
        save();
    }

    /** Kursi diurutkan berdasarkan nomor (1, 2, 3, ...). */
    List<Location> getSeats() {
        ConfigurationSection section = yml.getConfigurationSection("seats");
        if (section == null) {
            return new ArrayList<>();
        }
        Map<Integer, Location> sorted = new TreeMap<>();
        for (String key : section.getKeys(false)) {
            try {
                Location l = yml.getLocation("seats." + key);
                if (l != null && l.getWorld() != null) {
                    sorted.put(Integer.parseInt(key), l);
                }
            } catch (NumberFormatException ignored) {
                // abaikan key yang bukan angka
            }
        }
        return new ArrayList<>(sorted.values());
    }
}

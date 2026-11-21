package com.guesswho;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Membaca tema dari plugins/GuessWho/themes/&lt;nama-tema&gt;/*.png
 * Nama file (tanpa ekstensi) otomatis menjadi jawaban yang benar.
 */
public final class ThemeManager {

    private final JavaPlugin plugin;
    private final File dir;
    private final Map<String, List<File>> themes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    ThemeManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "themes");
    }

    void reload() {
        themes.clear();
        if (!dir.exists()) {
            dir.mkdirs();
            createExample();
        }
        File[] folders = dir.listFiles(File::isDirectory);
        if (folders == null) {
            return;
        }
        Arrays.sort(folders);
        for (File folder : folders) {
            File[] images = folder.listFiles(f -> {
                String n = f.getName().toLowerCase(Locale.ROOT);
                return f.isFile() && (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg"));
            });
            if (images != null && images.length > 0) {
                Arrays.sort(images);
                themes.put(folder.getName(), new ArrayList<>(Arrays.asList(images)));
            }
        }
        plugin.getLogger().info("Tema dimuat: " + themes.keySet());
    }

    private void createExample() {
        File example = new File(dir, "contoh");
        example.mkdirs();
        try {
            String readme = "Taruh file PNG/JPG di folder ini. Nama file = jawaban yang benar.\n"
                    + "Contoh: creeper.png -> jawabannya \"creeper\".\n"
                    + "Setelah menambah gambar, jalankan /gw reload.\n"
                    + "Folder ini tidak dihitung sebagai tema sampai ada gambarnya.\n";
            Files.writeString(new File(example, "BACA-SAYA.txt").toPath(), readme, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // tidak penting
        }
    }

    List<String> names() {
        return new ArrayList<>(themes.keySet());
    }

    /** Daftar file gambar tema, atau null kalau tema tidak ada. */
    List<File> files(String theme) {
        return themes.get(theme);
    }

    /** Mengubah nama file menjadi jawaban: "ender_man.png" -> "ender man". */
    static String answerOf(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) {
            n = n.substring(0, dot);
        }
        return n.replace('_', ' ').replace('-', ' ').trim();
    }
}

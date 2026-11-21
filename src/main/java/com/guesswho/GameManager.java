package com.guesswho;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Mengelola lobby dan satu game yang sedang berjalan. */
public final class GameManager {

    private final GuessWhoPlugin plugin;
    private final Set<UUID> lobby = new LinkedHashSet<>();
    private Game game;

    GameManager(GuessWhoPlugin plugin) {
        this.plugin = plugin;
    }

    Game game() {
        return game;
    }

    boolean isRunning() {
        return game != null;
    }

    boolean join(Player p) {
        return lobby.add(p.getUniqueId());
    }

    boolean leave(Player p) {
        return lobby.remove(p.getUniqueId());
    }

    List<Player> lobbyPlayers() {
        List<Player> list = new ArrayList<>();
        for (UUID id : lobby) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                list.add(p);
            }
        }
        return list;
    }

    /** @return pesan error, atau null kalau berhasil. */
    String start(String themeName) {
        if (game != null) {
            return "&cGame sudah berjalan. Pakai /gw stop dulu.";
        }
        Location camera = plugin.arena().getCamera();
        if (camera == null) {
            return "&cKamera belum diset. Berdiri di tengah meja lalu ketik /gw setcamera.";
        }
        List<Location> seats = plugin.arena().getSeats();
        List<Player> players = lobbyPlayers();
        if (players.size() < 2) {
            return "&cMinimal 2 pemain. Pemain harus ketik /gw join (atau host pakai /gw joinall).";
        }
        if (seats.size() < players.size()) {
            return "&cKursi kurang: ada " + seats.size() + " kursi untuk " + players.size()
                    + " pemain. Atur dengan /gw setseat <nomor>.";
        }

        String theme = themeName;
        List<String> names = plugin.themes().names();
        if (theme.equalsIgnoreCase("random")) {
            if (names.isEmpty()) {
                return "&cBelum ada tema. Taruh gambar di plugins/GuessWho/themes/<nama-tema>/";
            }
            theme = names.get((int) (Math.random() * names.size()));
        }
        List<File> files = plugin.themes().files(theme);
        if (files == null) {
            return "&cTema \"" + theme + "\" tidak ada. Lihat daftar dengan /gw themes.";
        }
        if (files.size() < players.size()) {
            return "&cTema \"" + theme + "\" hanya punya " + files.size() + " gambar, butuh minimal "
                    + players.size() + ".";
        }

        List<File> pick = new ArrayList<>(files);
        Collections.shuffle(pick); // pengacakan gambar ke pemain
        game = new Game(plugin, players, pick, camera, seats);
        Text.broadcast("&7Tema: &b" + theme.toLowerCase(Locale.ROOT));
        game.start();
        return null;
    }

    void clear(Game g) {
        if (game == g) {
            game = null;
        }
    }

    void shutdown() {
        if (game != null) {
            game.stop();
        }
    }

    void handleQuit(Player p) {
        lobby.remove(p.getUniqueId());
        if (game != null && game.isParticipant(p.getUniqueId())) {
            game.removePlayer(p);
        }
    }
}

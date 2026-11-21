package com.guesswho;

import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class GwCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList(
            "help", "join", "leave", "lobby", "joinall", "themes", "reload", "start", "stop", "next",
            "pass", "guess", "reveal", "setcamera", "setseat", "clearseats", "arena");

    private final GuessWhoPlugin plugin;

    GwCommand(GuessWhoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            help(s);
            return true;
        }
        GameManager gm = plugin.manager();
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(s);

            case "join" -> {
                if (!(s instanceof Player p)) {
                    Text.send(s, "&cHanya untuk pemain.");
                    return true;
                }
                if (gm.isRunning()) {
                    Text.send(s, "&cGame sedang berjalan.");
                } else if (gm.join(p)) {
                    Text.send(s, "&aKamu masuk lobby. Pemain di lobby: &e" + gm.lobbyPlayers().size());
                } else {
                    Text.send(s, "&eKamu sudah ada di lobby.");
                }
            }
            case "leave" -> {
                if (!(s instanceof Player p)) {
                    Text.send(s, "&cHanya untuk pemain.");
                    return true;
                }
                if (gm.isRunning() && gm.game().isParticipant(p.getUniqueId())) {
                    gm.game().removePlayer(p);
                    gm.leave(p);
                } else if (gm.leave(p)) {
                    Text.send(s, "&aKamu keluar dari lobby.");
                } else {
                    Text.send(s, "&eKamu tidak ada di lobby.");
                }
            }
            case "lobby" -> {
                StringBuilder sb = new StringBuilder("&6Lobby: &f");
                for (Player p : gm.lobbyPlayers()) {
                    sb.append(p.getName()).append(' ');
                }
                Text.send(s, sb.toString());
            }
            case "joinall" -> {
                if (!host(s)) {
                    return true;
                }
                if (gm.isRunning()) {
                    Text.send(s, "&cGame sedang berjalan.");
                    return true;
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    gm.join(p);
                }
                Text.send(s, "&aSemua pemain online dimasukkan ke lobby (" + gm.lobbyPlayers().size()
                        + "). Host yang tidak ikut main: /gw leave.");
            }

            case "themes" -> {
                List<String> names = plugin.themes().names();
                if (names.isEmpty()) {
                    Text.send(s, "&cBelum ada tema. Taruh gambar di plugins/GuessWho/themes/<nama-tema>/");
                } else {
                    for (String n : names) {
                        Text.send(s, "&b" + n + " &7(" + plugin.themes().files(n).size() + " gambar)");
                    }
                }
            }
            case "reload" -> {
                if (!host(s)) {
                    return true;
                }
                plugin.reloadConfig();
                plugin.themes().reload();
                plugin.arena().load();
                Text.send(s, "&aConfig, tema, dan arena dimuat ulang.");
            }

            case "start" -> {
                if (!host(s)) {
                    return true;
                }
                String err = gm.start(a.length > 1 ? a[1] : "random");
                if (err != null) {
                    Text.send(s, err);
                }
            }
            case "stop" -> {
                if (!host(s)) {
                    return true;
                }
                if (gm.isRunning()) {
                    gm.game().stop();
                    Text.send(s, "&aGame dihentikan.");
                } else {
                    Text.send(s, "&eTidak ada game yang berjalan.");
                }
            }
            case "next" -> {
                if (!running(s)) {
                    return true;
                }
                if (s instanceof Player p && gm.game().isActive(p)) {
                    gm.game().pass(p); // pemain giliran boleh skip giliran sendiri
                } else if (host(s)) {
                    gm.game().skip();
                }
            }
            case "pass" -> {
                if (!(s instanceof Player p) || !running(s)) {
                    return true;
                }
                String err = gm.game().pass(p);
                if (err != null) {
                    Text.send(s, err);
                }
            }
            case "guess" -> {
                if (!(s instanceof Player p) || !running(s)) {
                    return true;
                }
                if (a.length < 2) {
                    Text.send(s, "&cPakai: /gw guess <jawaban>");
                    return true;
                }
                String text = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                String err = gm.game().guess(p, text);
                if (err != null) {
                    Text.send(s, err);
                }
            }
            case "reveal" -> {
                if (!host(s) || !running(s)) {
                    return true;
                }
                String err = gm.game().reveal(a.length > 1 ? a[1] : null);
                if (err != null) {
                    Text.send(s, err);
                }
            }

            case "setcamera" -> {
                if (!host(s)) {
                    return true;
                }
                if (!(s instanceof Player p)) {
                    Text.send(s, "&cHanya untuk pemain.");
                    return true;
                }
                plugin.arena().setCamera(p.getLocation());
                Text.send(s, "&aTitik kamera diset di posisimu (tengah meja).");
            }
            case "setseat" -> {
                if (!host(s)) {
                    return true;
                }
                if (!(s instanceof Player p)) {
                    Text.send(s, "&cHanya untuk pemain.");
                    return true;
                }
                if (a.length < 2) {
                    Text.send(s, "&cPakai: /gw setseat <nomor 1-8>");
                    return true;
                }
                int n;
                try {
                    n = Integer.parseInt(a[1]);
                } catch (NumberFormatException ex) {
                    Text.send(s, "&cNomor kursi harus angka.");
                    return true;
                }
                if (n < 1 || n > 16) {
                    Text.send(s, "&cNomor kursi 1 sampai 16.");
                    return true;
                }
                plugin.arena().setSeat(n, p.getLocation());
                Text.send(s, "&aKursi " + n + " diset di posisimu.");
            }
            case "clearseats" -> {
                if (!host(s)) {
                    return true;
                }
                plugin.arena().clearSeats();
                Text.send(s, "&aSemua kursi dihapus.");
            }
            case "arena" -> {
                if (!host(s)) {
                    return true;
                }
                Location cam = plugin.arena().getCamera();
                Text.send(s, "&6Kamera: &f" + (cam == null ? "belum diset" : fmt(cam)));
                int i = 1;
                for (Location l : plugin.arena().getSeats()) {
                    Text.send(s, "&6Kursi " + i++ + ": &f" + fmt(l));
                }
            }
            default -> help(s);
        }
        return true;
    }

    private boolean host(CommandSender s) {
        if (!s.hasPermission("guesswho.host")) {
            Text.send(s, "&cKamu bukan host.");
            return false;
        }
        return true;
    }

    private boolean running(CommandSender s) {
        if (!plugin.manager().isRunning()) {
            Text.send(s, "&cTidak ada game yang berjalan.");
            return false;
        }
        return true;
    }

    private static String fmt(Location l) {
        return String.format(Locale.ROOT, "%s %.1f %.1f %.1f", l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }

    private void help(CommandSender s) {
        Text.send(s, "&6&lGuess Who Am I &7- perintah:");
        Text.send(s, "&e/gw join &7- masuk lobby   &e/gw leave &7- keluar");
        Text.send(s, "&7Saat giliranmu: &fketik di chat &7untuk bertanya");
        Text.send(s, "&e/gw guess <jawaban> &7- tebak (salah = nyawa -1)");
        Text.send(s, "&e/gw next &7- skip giliranmu   &e/gw themes &7- daftar tema");
        if (s.hasPermission("guesswho.host")) {
            Text.send(s, "&6Host: &e/gw setcamera, /gw setseat <n>, /gw clearseats, /gw arena");
            Text.send(s, "&6Host: &e/gw joinall, /gw start <tema|random>, /gw next, /gw reveal [pemain], /gw stop, /gw reload");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String sub : SUBS) {
                if (sub.startsWith(a[0].toLowerCase(Locale.ROOT))) {
                    out.add(sub);
                }
            }
        } else if (a.length == 2 && a[0].equalsIgnoreCase("start")) {
            out.add("random");
            out.addAll(plugin.themes().names());
            out.removeIf(n -> !n.toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT)));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("reveal")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}

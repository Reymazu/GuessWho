package com.guesswho;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;

/** Helper kecil untuk kirim pesan dengan kode warna &amp; */
final class Text {

    private Text() {
    }

    static Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    static void send(CommandSender to, String msg) {
        to.sendMessage(c(msg));
    }

    static void broadcast(String msg) {
        Bukkit.getServer().broadcast(c(msg));
    }

    static void title(Player p, String title, String subtitle) {
        p.showTitle(Title.title(
                c(title),
                c(subtitle),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))));
    }

    static void actionBar(Player p, String msg) {
        p.sendActionBar(c(msg));
    }
}

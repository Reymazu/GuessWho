package com.guesswho;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class GameListener implements Listener {

    private final GuessWhoPlugin plugin;

    GameListener(GuessWhoPlugin plugin) {
        this.plugin = plugin;
    }

    private Game game() {
        return plugin.manager().game();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.manager().handleQuit(e.getPlayer());
    }

    /** Pemain game tidak boleh berpindah tempat, tapi boleh menoleh. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Game g = game();
        if (g == null || !g.isParticipant(e.getPlayer().getUniqueId())) {
            return;
        }
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            e.setTo(fixed);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Game g = game();
        if (g != null && g.isParticipant(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        Game g = game();
        if (g != null && g.isGameEntity(e.getRightClicked())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        Game g = game();
        if (g != null && g.isGameEntity(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Game g = game();
        if (g != null && g.isGameBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }
}

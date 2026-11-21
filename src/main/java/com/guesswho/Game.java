package com.guesswho;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Satu sesi game. Cara kerja kamera (aman untuk Java dan Bedrock via Geyser):
 * <ul>
 *   <li>Semua pemain yang tidak sedang giliran dikunci di titik kamera (tengah meja), tidak terlihat,
 *       dan arah pandangnya diputar tiap tick ke kursi pemain giliran.</li>
 *   <li>Pemain giliran dipindah ke kursinya dan bebas melihat sekeliling (first person).</li>
 *   <li>Gambar tiap pemain dipasang di item frame, disembunyikan dari pemiliknya lewat hideEntity.</li>
 * </ul>
 */
public final class Game {

    private record PlayerState(Location loc, GameMode mode, boolean allowFlight, boolean flying,
                               boolean invulnerable, boolean collidable) {
    }

    static final class Participant {
        final UUID id;
        final String name;
        final File imageFile;
        final String answer;
        final Location seat;
        ItemFrame frame;
        Block barrier;
        BlockData oldBarrierData;
        boolean guessed;
        boolean left;
        PlayerState saved;

        Participant(UUID id, String name, File imageFile, Location seat) {
            this.id = id;
            this.name = name;
            this.imageFile = imageFile;
            this.answer = ThemeManager.answerOf(imageFile);
            this.seat = seat;
        }
    }

    private final GuessWhoPlugin plugin;
    private final Location camera;
    private final List<Participant> parts = new ArrayList<>();
    private final List<Participant> guessOrder = new ArrayList<>();

    private final float smooth;
    private final int turnDelay;
    private final int turnSeconds;
    private final int imageHeight;
    private final double lookHeight;
    private final boolean wrongEndsTurn;

    private BukkitTask task;
    private Participant active;
    private Participant pending;
    private int pendingTicks;
    private int turnTicksLeft;
    private int tickCount;
    private float camYaw;
    private float camPitch;
    private float targetYaw;
    private float targetPitch;
    private boolean ending;
    private boolean stopped;

    Game(GuessWhoPlugin plugin, List<Player> players, List<File> images, Location camera, List<Location> seats) {
        this.plugin = plugin;
        this.camera = camera;
        var cfg = plugin.getConfig();
        this.smooth = (float) Math.max(0.02, Math.min(1.0, cfg.getDouble("camera-speed", 0.12)));
        this.turnDelay = Math.max(0, cfg.getInt("turn-delay-ticks", 40));
        this.turnSeconds = Math.max(0, cfg.getInt("turn-seconds", 0));
        this.imageHeight = cfg.getInt("image-height", 2);
        this.lookHeight = cfg.getDouble("look-height", 1.9);
        this.wrongEndsTurn = cfg.getBoolean("wrong-guess-ends-turn", true);

        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            parts.add(new Participant(p.getUniqueId(), p.getName(), images.get(i), seats.get(i)));
        }
    }

    // ------------------------------------------------------------------ start / stop

    void start() {
        for (Participant p : parts) {
            setupImage(p);
        }
        for (Participant p : parts) {
            Player pl = Bukkit.getPlayer(p.id);
            if (pl == null) {
                continue;
            }
            p.saved = save(pl);
            applyViewer(pl);
        }
        // Gambar disembunyikan dari pemiliknya sendiri (kunci utama minigame ini)
        for (Participant p : parts) {
            Player pl = Bukkit.getPlayer(p.id);
            if (pl != null && p.frame != null) {
                pl.hideEntity(plugin, p.frame);
            }
        }

        Participant first = parts.get((int) (Math.random() * parts.size()));
        aim(first);
        camYaw = targetYaw;
        camPitch = targetPitch;
        pending = first;
        pendingTicks = turnDelay + 20;

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Text.broadcast("&6&lGuess Who Am I &7dimulai! &fPemain: &e" + parts.size()
                + "&f. Tebak gambar di atas kepalamu!");
    }

    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (task != null) {
            task.cancel();
        }
        for (Participant p : parts) {
            Player pl = Bukkit.getPlayer(p.id);
            if (pl != null && pl.isOnline() && !p.left) {
                restore(pl, p);
            }
            cleanupImage(p);
        }
        plugin.manager().clear(this);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        tickCount++;

        // Kamera berputar halus ke target
        float dy = wrap(targetYaw - camYaw);
        float step = dy * smooth;
        float min = 0.5f;
        if (Math.abs(step) < min) {
            step = Math.copySign(Math.min(min, Math.abs(dy)), dy);
        }
        camYaw = wrap(camYaw + step);
        float dp = targetPitch - camPitch;
        camPitch = Math.abs(dp) < 0.1f ? targetPitch : camPitch + dp * smooth;

        Location camLoc = camera.clone();
        camLoc.setYaw(camYaw);
        camLoc.setPitch(camPitch);
        for (Participant p : parts) {
            if (p.left || p == active) {
                continue;
            }
            Player pl = Bukkit.getPlayer(p.id);
            if (pl != null && pl.isOnline()) {
                pl.teleport(camLoc);
            }
        }

        // Giliran berikutnya menunggu kamera sampai
        if (pending != null) {
            if (--pendingTicks <= 0) {
                Participant next = pending;
                pending = null;
                activate(next);
            }
        } else if (active != null && turnTicksLeft > 0) {
            if (--turnTicksLeft == 0) {
                Text.broadcast("&cWaktu habis untuk &e" + active.name + "&c!");
                advanceTurn();
            }
        }

        if (tickCount % 20 == 0 && !ending) {
            String bar;
            if (active == null) {
                bar = "&7Kamera berputar...";
            } else if (turnSeconds > 0) {
                bar = "&eGiliran: &f" + active.name + " &7(" + (turnTicksLeft / 20) + "s)";
            } else {
                bar = "&eGiliran: &f" + active.name;
            }
            for (Participant p : parts) {
                Player pl = Bukkit.getPlayer(p.id);
                if (pl != null && pl.isOnline() && !p.left) {
                    Text.actionBar(pl, bar);
                }
            }
        }
    }

    // ------------------------------------------------------------------ giliran

    private Participant nextAfter(Participant cur) {
        int n = parts.size();
        int idx = cur == null ? -1 : parts.indexOf(cur);
        for (int i = 1; i <= n; i++) {
            Participant c = parts.get(((idx + i) % n + n) % n);
            if (!c.guessed && !c.left) {
                return c;
            }
        }
        return null;
    }

    private int remaining() {
        int r = 0;
        for (Participant p : parts) {
            if (!p.guessed && !p.left) {
                r++;
            }
        }
        return r;
    }

    private void activate(Participant p) {
        Player pl = Bukkit.getPlayer(p.id);
        if (pl == null || !pl.isOnline() || p.left) {
            p.left = true;
            advanceTurn();
            return;
        }
        active = p;
        Location seatLoc = p.seat.clone();
        seatLoc.setYaw(yawTo(p.seat, camera));
        seatLoc.setPitch(0f);
        pl.teleport(seatLoc);
        applyActive(pl);
        turnTicksLeft = turnSeconds * 20;
        Text.title(pl, "&a&lGiliran kamu!", "&7Tanya atau tebak: &f/gw guess <jawaban>");
        Text.broadcast("&eGiliran &f" + p.name + "&e untuk bertanya / menebak.");
    }

    /** Selesaikan giliran sekarang dan putar kamera ke pemain berikutnya. */
    private void advanceTurn() {
        if (ending || stopped) {
            return;
        }
        Participant prev = active;
        active = null;
        turnTicksLeft = 0;
        if (prev != null) {
            Player pl = Bukkit.getPlayer(prev.id);
            if (pl != null && pl.isOnline() && !prev.left) {
                applyViewer(pl);
            }
        }
        if (remaining() == 0) {
            finish();
            return;
        }
        Participant next = nextAfter(prev);
        if (next == null) {
            finish();
            return;
        }
        pending = next;
        pendingTicks = turnDelay;
        aim(next);
    }

    // ------------------------------------------------------------------ aksi pemain / host

    String guess(Player player, String text) {
        Participant p = of(player.getUniqueId());
        if (p == null || p != active) {
            return "&cBukan giliranmu.";
        }
        if (norm(text).equals(norm(p.answer))) {
            p.guessed = true;
            guessOrder.add(p);
            // Gambar sekarang boleh dilihat pemiliknya
            if (p.frame != null) {
                player.showEntity(plugin, p.frame);
            }
            Text.broadcast("&a&lBENAR! &f" + p.name + " &aadalah &e" + p.answer + "&a!");
            advanceTurn();
            return null;
        }
        Text.broadcast("&c" + p.name + " menebak \"" + text + "\" - salah!");
        if (wrongEndsTurn) {
            advanceTurn();
        }
        return null;
    }

    String pass(Player player) {
        Participant p = of(player.getUniqueId());
        if (p == null || p != active) {
            return "&cBukan giliranmu.";
        }
        advanceTurn();
        return null;
    }

    void skip() {
        if (pending != null) {
            return; // kamera masih berputar
        }
        advanceTurn();
    }

    /** Tampilkan jawaban satu pemain (atau semua kalau name == null). */
    String reveal(String name) {
        boolean any = false;
        for (Participant p : parts) {
            if (name != null && !p.name.equalsIgnoreCase(name)) {
                continue;
            }
            any = true;
            Text.broadcast("&e" + p.name + " &7= &f" + p.answer);
            Player pl = Bukkit.getPlayer(p.id);
            if (pl != null && p.frame != null) {
                pl.showEntity(plugin, p.frame);
            }
        }
        return any ? null : "&cPemain tidak ditemukan di game ini.";
    }

    void removePlayer(Player player) {
        Participant p = of(player.getUniqueId());
        if (p == null || p.left) {
            return;
        }
        restore(player, p);
        p.left = true;
        cleanupImage(p);
        Text.broadcast("&e" + p.name + " &7keluar dari game.");
        if (remaining() < 2 && !ending) {
            Text.broadcast("&cPemain tersisa kurang dari 2, game dihentikan.");
            Bukkit.getScheduler().runTask(plugin, this::stop);
            return;
        }
        if (p == active) {
            advanceTurn();
        } else if (p == pending) {
            pending = nextAfter(p);
            if (pending != null) {
                aim(pending);
            }
        }
    }

    private void finish() {
        if (ending) {
            return;
        }
        ending = true;
        active = null;
        pending = null;
        Text.broadcast("&6&lGame selesai! &7Urutan yang berhasil menebak:");
        int rank = 1;
        for (Participant p : guessOrder) {
            Text.broadcast("&e#" + rank++ + " &f" + p.name + " &7(" + p.answer + ")");
        }
        for (Participant p : parts) {
            if (!p.guessed && !p.left) {
                Text.broadcast("&c" + p.name + " &7belum menebak: &f" + p.answer);
            }
            Player pl = Bukkit.getPlayer(p.id);
            if (pl != null && p.frame != null && !p.left) {
                pl.showEntity(plugin, p.frame);
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::stop, 100L);
    }

    // ------------------------------------------------------------------ query

    boolean isParticipant(UUID id) {
        Participant p = of(id);
        return p != null && !p.left;
    }

    boolean isGameEntity(Entity e) {
        for (Participant p : parts) {
            if (p.frame != null && p.frame.getUniqueId().equals(e.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    boolean isGameBlock(Block b) {
        for (Participant p : parts) {
            if (p.barrier != null && p.barrier.equals(b)) {
                return true;
            }
        }
        return false;
    }

    private Participant of(UUID id) {
        for (Participant p : parts) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ gambar

    private void setupImage(Participant p) {
        World w = camera.getWorld();
        final ItemStack map;
        try {
            map = MapImages.create(w, p.imageFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Gagal memuat gambar " + p.imageFile.getName() + ": " + e.getMessage());
            return;
        }
        Block barrier = w.getBlockAt(p.seat.getBlockX(), p.seat.getBlockY() + imageHeight, p.seat.getBlockZ());
        final BlockFace face = faceToward(p.seat, camera);
        Block frameBlock = barrier.getRelative(face);

        // Barrier = penopang item frame (tidak terlihat)
        p.barrier = barrier;
        p.oldBarrierData = barrier.getBlockData();
        barrier.setType(Material.BARRIER, false);

        p.frame = w.spawn(frameBlock.getLocation(), ItemFrame.class, f -> {
            f.setFacingDirection(face, true);
            f.setItem(map, false);
            f.setVisible(false);
            f.setFixed(true);
            f.setInvulnerable(true);
            f.setPersistent(false);
        });
    }

    private void cleanupImage(Participant p) {
        if (p.frame != null) {
            p.frame.remove();
            p.frame = null;
        }
        if (p.barrier != null && p.oldBarrierData != null) {
            p.barrier.setBlockData(p.oldBarrierData, false);
            p.barrier = null;
        }
    }

    private static BlockFace faceToward(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    // ------------------------------------------------------------------ status pemain

    private PlayerState save(Player p) {
        return new PlayerState(p.getLocation(), p.getGameMode(), p.getAllowFlight(), p.isFlying(),
                p.isInvulnerable(), p.isCollidable());
    }

    /** Pemain penonton: terkunci di titik kamera, tidak terlihat. */
    private void applyViewer(Player p) {
        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setInvulnerable(true);
        p.setCollidable(false);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, false));
        Location l = camera.clone();
        l.setYaw(camYaw);
        l.setPitch(camPitch);
        p.teleport(l);
    }

    /** Pemain giliran: berdiri di kursi, terlihat, first person. */
    private void applyActive(Player p) {
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.setFlying(false);
        p.setAllowFlight(false);
    }

    private void restore(Player p, Participant part) {
        PlayerState s = part.saved;
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (s == null) {
            return;
        }
        p.setGameMode(s.mode());
        p.setAllowFlight(s.allowFlight());
        p.setFlying(s.allowFlight() && s.flying());
        p.setInvulnerable(s.invulnerable());
        p.setCollidable(s.collidable());
        p.teleport(s.loc());
    }

    // ------------------------------------------------------------------ matematika kamera

    private void aim(Participant p) {
        double eyeY = camera.getY() + 1.62;
        double dx = p.seat.getX() - camera.getX();
        double dy = (p.seat.getY() + lookHeight) - eyeY;
        double dz = p.seat.getZ() - camera.getZ();
        targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        targetPitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    private static float yawTo(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static float wrap(float angle) {
        float a = (angle + 180f) % 360f;
        if (a < 0) {
            a += 360f;
        }
        return a - 180f;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]", "");
    }
}

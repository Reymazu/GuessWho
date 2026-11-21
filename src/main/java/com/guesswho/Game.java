package com.guesswho;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

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
        ArmorStand body;
        Block barrier;
        BlockData oldBarrierData;
        boolean guessed;
        boolean left;
        boolean eliminated;
        int lives;
        PlayerState saved;
        Scoreboard prevBoard;

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
    private final int maxLives;

    private Scoreboard board;
    private BossBar bossBar;
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
        this.maxLives = Math.max(1, cfg.getInt("lives", 3));

        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            Participant part = new Participant(p.getUniqueId(), p.getName(), images.get(i), seats.get(i));
            part.lives = maxLives;
            parts.add(part);
        }
    }

    // ------------------------------------------------------------------ start / stop

    void start() {
        for (Participant p : parts) {
            setupImage(p);
            setupBody(p);
        }

        board = Bukkit.getScoreboardManager().getNewScoreboard();
        bossBar = BossBar.bossBar(Text.c("&7Mempersiapkan..."), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

        for (Participant p : parts) {
            Player pl = Bukkit.getPlayer(p.id);
            if (pl == null) {
                continue;
            }
            p.saved = save(pl);
            p.prevBoard = pl.getScoreboard();
            applyViewer(pl);
            pl.setScoreboard(board);
            pl.showBossBar(bossBar);
        }
        // Gambar disembunyikan dari pemiliknya sendiri (kunci utama minigame ini)
        for (Participant p : parts) {
            Player pl = Bukkit.getPlayer(p.id);
            if (pl != null && p.frame != null) {
                pl.hideEntity(plugin, p.frame);
            }
            // Tubuh pengganti juga disembunyikan dari pemiliknya (supaya tidak menghalangi pandangan)
            if (pl != null && p.body != null) {
                pl.hideEntity(plugin, p.body);
            }
        }
        updateBoard();

        Participant first = parts.get((int) (Math.random() * parts.size()));
        aim(first);
        camYaw = targetYaw;
        camPitch = targetPitch;
        pending = first;
        pendingTicks = turnDelay + 20;

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Text.broadcast("&6&lGuess Who Am I &7dimulai! &fPemain: &e" + parts.size()
                + "&f. Tebak gambar di atas kepalamu!");
        Text.broadcast("&7Tiap pemain punya &c" + maxLives + " nyawa &7(" + hearts(maxLives, maxLives)
                + "&7). Tebakan salah = nyawa berkurang, habis = tereliminasi.");
        Text.broadcast("&7Saat giliranmu: &fketik langsung di chat &7untuk bertanya, &e/gw guess <jawaban> &7untuk menebak, &e/gw next &7untuk skip giliran.");
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
                restoreUi(pl, p);
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

        if (tickCount % 10 == 0 && !ending) {
            if (bossBar != null) {
                if (active == null) {
                    bossBar.name(Text.c("&7Kamera berputar..."));
                    bossBar.progress(1f);
                } else if (turnSeconds > 0) {
                    bossBar.name(Text.c("&eGiliran: &f" + active.name + " " + heartsOf(active)
                            + " &7(" + (turnTicksLeft / 20) + "s)"));
                    bossBar.progress(Math.max(0f, Math.min(1f, turnTicksLeft / (float) (turnSeconds * 20))));
                } else {
                    bossBar.name(Text.c("&eGiliran: &f" + active.name + " " + heartsOf(active)));
                    bossBar.progress(1f);
                }
            }
            for (Participant p : parts) {
                Player pl = Bukkit.getPlayer(p.id);
                if (pl != null && pl.isOnline() && !p.left) {
                    if (p.eliminated) {
                        Text.actionBar(pl, "&4&lKamu tereliminasi &7- menonton");
                    } else if (p.guessed) {
                        Text.actionBar(pl, "&a&lKamu sudah menebak dengan benar!");
                    } else if (p == active) {
                        Text.actionBar(pl, "&cNyawa: " + heartsOf(p) + " &7| &fketik di chat = tanya &7| &e/gw guess &7| &e/gw next");
                    } else {
                        Text.actionBar(pl, "&cNyawamu: " + heartsOf(p));
                    }
                }
            }
        }
        if (tickCount % 20 == 0 && !ending) {
            updateBoard();
        }
    }

    // ------------------------------------------------------------------ nyawa & UI

    private static String hearts(int lives, int max) {
        return "&c" + "♥".repeat(Math.max(0, lives)) + "&8" + "♥".repeat(Math.max(0, max - lives));
    }

    private String heartsOf(Participant p) {
        return hearts(p.lives, maxLives);
    }

    private void updateBoard() {
        if (board == null) {
            return;
        }
        Objective obj = board.getObjective("gw");
        if (obj != null) {
            obj.unregister();
        }
        obj = board.registerNewObjective("gw", Criteria.DUMMY, Text.c("&6&lGuess Who"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (Participant p : parts) {
            if (p.left) {
                continue;
            }
            String line;
            if (p.eliminated) {
                line = "&c✖ &7" + p.name;
            } else if (p.guessed) {
                line = "&a✔ &f" + p.name;
            } else if (p == active) {
                line = "&e▶ &f" + p.name + " " + heartsOf(p);
            } else {
                line = "&f" + p.name + " " + heartsOf(p);
            }
            obj.getScore(line.replace('&', '§')).setScore(p.eliminated || p.guessed ? 0 : p.lives);
        }
    }

    private void updateNameTag(Participant p) {
        if (p.body != null) {
            p.body.customName(Text.c("&f" + p.name + " " + (p.eliminated ? "&4✖" : heartsOf(p))));
            p.body.setCustomNameVisible(true);
        }
    }

    private void restoreUi(Player pl, Participant p) {
        if (bossBar != null) {
            pl.hideBossBar(bossBar);
        }
        if (p.prevBoard != null) {
            pl.setScoreboard(p.prevBoard);
        } else {
            pl.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void loseLife(Participant p, Player pl) {
        p.lives--;
        Location fx = p.seat.clone().add(0, 1.2, 0);
        World w = fx.getWorld();
        w.spawnParticle(Particle.DAMAGE_INDICATOR, fx, 15, 0.3, 0.4, 0.3, 0.1);
        w.spawnParticle(Particle.ANGRY_VILLAGER, fx.clone().add(0, 0.8, 0), 3, 0.2, 0.1, 0.2, 0);
        w.playSound(fx, Sound.ENTITY_PLAYER_HURT, 1f, 0.8f);
        if (pl != null && pl.isOnline()) {
            pl.playSound(pl.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.2f);
            pl.showTitle(net.kyori.adventure.title.Title.title(
                    Text.c("&c&l-1 ♥"),
                    Text.c(p.lives > 0 ? "&7Sisa nyawa: " + heartsOf(p) : "&4Nyawamu habis!"),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1200),
                            java.time.Duration.ofMillis(400))));
        }
        if (p.lives <= 0) {
            p.eliminated = true;
            w.strikeLightningEffect(fx);
            w.spawnParticle(Particle.EXPLOSION_EMITTER, fx, 1);
            w.spawnParticle(Particle.SMOKE, fx, 30, 0.4, 0.6, 0.4, 0.02);
            Text.broadcast("&4&l✖ " + p.name + " tereliminasi! &7Jawabannya: &f" + p.answer);
            if (pl != null && p.frame != null) {
                pl.showEntity(plugin, p.frame);
            }
        }
        updateNameTag(p);
        updateBoard();
    }

    // ------------------------------------------------------------------ giliran

    private Participant nextAfter(Participant cur) {
        int n = parts.size();
        int idx = cur == null ? -1 : parts.indexOf(cur);
        for (int i = 1; i <= n; i++) {
            Participant c = parts.get(((idx + i) % n + n) % n);
            if (!c.guessed && !c.left && !c.eliminated) {
                return c;
            }
        }
        return null;
    }

    private int remaining() {
        int r = 0;
        for (Participant p : parts) {
            if (!p.guessed && !p.left && !p.eliminated) {
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
        Text.title(pl, "&a&lGiliran kamu!", "&7Ketik di chat untuk bertanya");
        Text.broadcast("&eGiliran &f" + p.name + " &c" + heartsOf(p) + "&e untuk bertanya / menebak.");
        Text.send(pl, "&6&l--- Giliranmu ---");
        Text.send(pl, "&f• Ketik langsung di chat &7untuk bertanya ke pemain lain (jawab ya/tidak).");
        Text.send(pl, "&f• &e/gw guess <jawaban> &7untuk menebak. Salah = nyawa -1 (sisa " + heartsOf(p) + "&7).");
        Text.send(pl, "&f• &e/gw next &7untuk skip giliran.");
        updateBoard();
    }

    boolean isActive(Player player) {
        return active != null && active.id.equals(player.getUniqueId());
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
            Location fx = p.seat.clone().add(0, 1.2, 0);
            fx.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, fx, 40, 0.4, 0.6, 0.4, 0.3);
            fx.getWorld().playSound(fx, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            updateNameTag(p);
            advanceTurn();
            return null;
        }
        Text.broadcast("&c" + p.name + " menebak \"" + text + "\" - salah!");
        loseLife(p, player);
        if (p.eliminated || wrongEndsTurn) {
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
        restoreUi(player, p);
        p.left = true;
        cleanupImage(p);
        updateBoard();
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
            if (p.eliminated) {
                Text.broadcast("&4✖ " + p.name + " &7tereliminasi: &f" + p.answer);
            } else if (!p.guessed && !p.left) {
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
            if (p.body != null && p.body.getUniqueId().equals(e.getUniqueId())) {
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
        // Barrier harus di atas kepala (tinggi pemain 1.8) supaya pemain tidak terpaksa merangkak,
        // termasuk kalau kursi berdiri di atas slab / posisi pecahan.
        int barrierY = Math.max(p.seat.getBlockY() + imageHeight, (int) Math.ceil(p.seat.getY() + 1.9));
        Block barrier = w.getBlockAt(p.seat.getBlockX(), barrierY, p.seat.getBlockZ());
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

    /** Tubuh pengganti di kursi (kepala pemain + nama + nyawa). Pemain aslinya invisible. */
    private void setupBody(Participant p) {
        World w = camera.getWorld();
        Location loc = p.seat.clone();
        loc.setYaw(yawTo(p.seat, camera));
        loc.setPitch(0f);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(p.id));
            head.setItemMeta(meta);
        }
        p.body = w.spawn(loc, ArmorStand.class, a -> {
            a.setBasePlate(false);
            a.setArms(false);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setPersistent(false);
            a.setSilent(true);
            a.getEquipment().setHelmet(head);
            a.getEquipment().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
            a.getEquipment().setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
            a.getEquipment().setBoots(new ItemStack(Material.LEATHER_BOOTS));
        });
        updateNameTag(p);
    }

    private void cleanupImage(Participant p) {
        if (p.body != null) {
            p.body.remove();
            p.body = null;
        }
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
        p.setPose(Pose.STANDING, false);
        emptyHand(p);
        Location l = camera.clone();
        l.setYaw(camYaw);
        l.setPitch(camPitch);
        p.teleport(l);
    }

    /** Pemain giliran: berdiri di kursi, terlihat, first person. */
    private void applyActive(Player p) {
        // Tetap invisible: client tidak menggambar tangan kosong first-person kalau pemain invisible.
        // Tubuh yang dilihat pemain lain diwakili ArmorStand (lihat setupBody).
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, false));
        emptyHand(p);
        p.setFlying(false);
        p.setAllowFlight(false);
        // Kunci pose berdiri supaya tidak merangkak
        p.setPose(Pose.STANDING, true);
    }

    /** Pindah ke slot hotbar kosong supaya tidak ada item yang terlihat di tangan. */
    private void emptyHand(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) {
                inv.setHeldItemSlot(i);
                return;
            }
        }
    }

    private void restore(Player p, Participant part) {
        PlayerState s = part.saved;
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.setPose(Pose.STANDING, false);
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

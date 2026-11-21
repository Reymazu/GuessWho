package com.guesswho;

import org.bukkit.plugin.java.JavaPlugin;

public final class GuessWhoPlugin extends JavaPlugin {

    private ArenaStore arena;
    private ThemeManager themes;
    private GameManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        arena = new ArenaStore(this);
        themes = new ThemeManager(this);
        themes.reload();
        manager = new GameManager(this);

        GwCommand command = new GwCommand(this);
        if (getCommand("gw") != null) {
            getCommand("gw").setExecutor(command);
            getCommand("gw").setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    ArenaStore arena() {
        return arena;
    }

    ThemeManager themes() {
        return themes;
    }

    GameManager manager() {
        return manager;
    }
}

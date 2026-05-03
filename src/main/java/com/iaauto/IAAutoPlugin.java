package com.iaauto;

import com.iaauto.command.IAAutoCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class IAAutoPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginCommand command = getCommand("nap");
        if (command == null) {
            getLogger().severe("Command 'nap' is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        IAAutoCommand commandHandler = new IAAutoCommand(this);
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
    }
}

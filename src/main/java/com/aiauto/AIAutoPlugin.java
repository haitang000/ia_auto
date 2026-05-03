package com.aiauto;

import com.aiauto.command.AIAutoCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AIAutoPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginCommand command = getCommand("aiauto");
        if (command == null) {
            getLogger().severe("Command 'aiauto' is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        AIAutoCommand commandHandler = new AIAutoCommand(this);
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
    }
}

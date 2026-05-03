package com.iaauto.command;

import com.iaauto.IAAutoPlugin;
import com.iaauto.git.GitPushException;
import com.iaauto.git.GitPushService;
import com.iaauto.git.PushResult;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class IAAutoCommand implements CommandExecutor, TabCompleter {
    private static final String PREFIX = ChatColor.AQUA + "[IAAuto] " + ChatColor.RESET;
    private static final List<String> SUBCOMMANDS = List.of("push", "reload");

    private final IAAutoPlugin plugin;
    private final AtomicBoolean pushRunning = new AtomicBoolean(false);

    public IAAutoCommand(IAAutoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sendUsage(sender, label);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if ("push".equals(subcommand)) {
            push(sender);
            return true;
        }

        if ("reload".equals(subcommand)) {
            reload(sender);
            return true;
        }

        sendUsage(sender, label);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream()
                .filter(subcommand -> subcommand.startsWith(prefix))
                .filter(subcommand -> sender.hasPermission("iaauto." + subcommand))
                .toList();
    }

    private void push(CommandSender sender) {
        if (!sender.hasPermission("iaauto.push")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission to run this command.");
            return;
        }

        if (!pushRunning.compareAndSet(false, true)) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "A push is already running.");
            return;
        }

        sender.sendMessage(PREFIX + ChatColor.GRAY + "Starting git push for ItemsAdder generated.zip...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PushResult result = null;
            Exception failure = null;

            try {
                result = new GitPushService(plugin).pushGeneratedZip();
            } catch (GitPushException exception) {
                failure = exception;
            } catch (Exception exception) {
                failure = exception;
                plugin.getLogger().log(Level.SEVERE, "Unexpected error while pushing generated.zip.", exception);
            } finally {
                pushRunning.set(false);
            }

            PushResult finalResult = result;
            Exception finalFailure = failure;
            Bukkit.getScheduler().runTask(plugin, () -> reportPushResult(sender, finalResult, finalFailure));
        });
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("iaauto.reload")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission to run this command.");
            return;
        }

        plugin.reloadConfig();
        sender.sendMessage(PREFIX + ChatColor.GREEN + "Configuration reloaded.");
    }

    private void reportPushResult(CommandSender sender, PushResult result, Exception failure) {
        if (failure != null) {
            sender.sendMessage(PREFIX + ChatColor.RED + compactFailure(failure));
            return;
        }

        if (result == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Push finished without a result.");
            return;
        }

        if (result.committed()) {
            sender.sendMessage(PREFIX + ChatColor.GREEN + "generated.zip was committed and pushed to origin/" + result.branch() + ".");
        } else {
            sender.sendMessage(PREFIX + ChatColor.GREEN + "No file changes were found; origin/" + result.branch() + " was checked.");
        }
        sender.sendMessage(PREFIX + ChatColor.GRAY + "Repository: " + result.repositoryDirectory());
        sender.sendMessage(PREFIX + ChatColor.GRAY + "Tracked file: " + result.repositoryFile());
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "Usage: /" + label + " push");
        if (sender.hasPermission("iaauto.reload")) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "Usage: /" + label + " reload");
        }
    }

    private String compactFailure(Exception failure) {
        String message = Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()).trim();
        if (message.length() <= 500) {
            return message;
        }
        return message.substring(0, 497) + "...";
    }
}

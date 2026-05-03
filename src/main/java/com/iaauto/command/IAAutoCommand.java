package com.iaauto.command;

import com.iaauto.IAAutoPlugin;
import com.iaauto.git.GitPushException;
import com.iaauto.git.GitPushService;
import com.iaauto.git.PushProgress;
import com.iaauto.git.PushResult;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class IAAutoCommand implements CommandExecutor, TabCompleter {
    private static final String PREFIX = ChatColor.AQUA + "[IAAuto] " + ChatColor.RESET;
    private static final List<String> SUBCOMMANDS = List.of("start", "push", "reload");
    private static final long TICKS_PER_SECOND = 20L;

    private final IAAutoPlugin plugin;
    private final AtomicBoolean operationRunning = new AtomicBoolean(false);

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
        if ("start".equals(subcommand)) {
            start(sender);
            return true;
        }

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

        if (!operationRunning.compareAndSet(false, true)) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "An IAAuto operation is already running.");
            return;
        }

        runPush(sender);
    }

    private void start(CommandSender sender) {
        if (!sender.hasPermission("iaauto.start")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission to run this command.");
            return;
        }

        if (!operationRunning.compareAndSet(false, true)) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "An IAAuto operation is already running.");
            return;
        }

        String packCommand = configuredPackCommand();
        sender.sendMessage(PREFIX + ChatColor.GRAY + "Running /" + packCommand + "...");

        boolean dispatched;
        try {
            dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), packCommand);
        } catch (CommandException exception) {
            operationRunning.set(false);
            plugin.getLogger().log(Level.SEVERE, "Failed to run /" + packCommand + ".", exception);
            sender.sendMessage(PREFIX + ChatColor.RED + "Failed to run /" + packCommand + ": " + compactFailure(exception));
            return;
        }

        if (!dispatched) {
            operationRunning.set(false);
            sender.sendMessage(PREFIX + ChatColor.RED + "Command /" + packCommand + " was not found or could not be run.");
            return;
        }

        long delaySeconds = configuredPushDelaySeconds();
        if (delaySeconds <= 0L) {
            sender.sendMessage(PREFIX + ChatColor.GRAY + "Starting /iaauto push...");
            runPush(sender);
            return;
        }

        sender.sendMessage(PREFIX + ChatColor.GRAY + "Starting /iaauto push in " + delaySeconds + " seconds...");
        Bukkit.getScheduler().runTaskLater(plugin, () -> runPush(sender), delaySeconds * TICKS_PER_SECOND);
    }

    private void runPush(CommandSender sender) {
        sender.sendMessage(PREFIX + ChatColor.GRAY + "Starting git push for ItemsAdder generated.zip...");
        PushProgressDisplay progressDisplay = new PushProgressDisplay(sender);
        progressDisplay.start();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PushResult result = null;
            Exception failure = null;

            try {
                result = new GitPushService(plugin, progressDisplay::update).pushGeneratedZip();
            } catch (GitPushException exception) {
                failure = exception;
            } catch (Exception exception) {
                failure = exception;
                plugin.getLogger().log(Level.SEVERE, "Unexpected error while pushing generated.zip.", exception);
            } finally {
                operationRunning.set(false);
            }

            PushResult finalResult = result;
            Exception finalFailure = failure;
            Bukkit.getScheduler().runTask(plugin, () -> {
                progressDisplay.finish(finalFailure);
                reportPushResult(sender, finalResult, finalFailure);
            });
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

    private String configuredPackCommand() {
        String command = plugin.getConfig().getString("start.pack-command");
        if (command == null) {
            command = plugin.getConfig().getString("start.iapack-command", "iazip");
        }
        command = Objects.toString(command, "iazip").trim();
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        return command.isEmpty() ? "iazip" : command;
    }

    private long configuredPushDelaySeconds() {
        return Math.max(0L, plugin.getConfig().getLong("start.push-delay-seconds", 10L));
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
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "Usage: /" + label + " start");
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

    private final class PushProgressDisplay {
        private static final int TITLE_LIMIT = 64;

        private final CommandSender sender;
        private BossBar bossBar;
        private int lastLoggedPercent = -1;
        private String lastLoggedMessage = "";
        private double lastVisibleProgress;
        private boolean finished;

        private PushProgressDisplay(CommandSender sender) {
            this.sender = sender;
        }

        private void start() {
            plugin.getLogger().info("[push 0%] Starting git push for ItemsAdder generated.zip");

            if (!(sender instanceof Player player)) {
                return;
            }

            bossBar = Bukkit.createBossBar(
                    ChatColor.AQUA + "IAAuto push: starting...",
                    BarColor.BLUE,
                    BarStyle.SEGMENTED_20
            );
            bossBar.setProgress(0.0D);
            bossBar.addPlayer(player);
        }

        private void update(PushProgress progress) {
            ProgressSnapshot snapshot = recordProgress(progress);

            if (bossBar == null) {
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (finished) {
                    return;
                }

                bossBar.setColor(BarColor.BLUE);
                bossBar.setTitle(ChatColor.AQUA + "IAAuto push " + snapshot.percent() + "% " + ChatColor.WHITE + snapshot.message());
                bossBar.setProgress(snapshot.progress());
            });
        }

        private void finish(Exception failure) {
            finished = true;
            if (failure == null) {
                logProgress(100, "Push finished");
            } else {
                plugin.getLogger().warning("[push failed] " + compactProgressMessage(compactFailure(failure)));
            }

            if (bossBar == null) {
                return;
            }

            if (failure == null) {
                bossBar.setColor(BarColor.GREEN);
                bossBar.setTitle(ChatColor.GREEN + "IAAuto push complete");
                bossBar.setProgress(1.0D);
            } else {
                bossBar.setColor(BarColor.RED);
                bossBar.setTitle(ChatColor.RED + "IAAuto push failed");
                bossBar.setProgress(1.0D);
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> bossBar.removeAll(), 60L);
        }

        private synchronized ProgressSnapshot recordProgress(PushProgress progress) {
            lastVisibleProgress = Math.max(lastVisibleProgress, progress.progress());
            int percent = (int) Math.round(lastVisibleProgress * 100.0D);
            String message = compactProgressMessage(progress.message());
            logProgress(percent, message);
            return new ProgressSnapshot(lastVisibleProgress, percent, message);
        }

        private synchronized void logProgress(int percent, String message) {
            if (percent == lastLoggedPercent && Objects.equals(message, lastLoggedMessage)) {
                return;
            }

            lastLoggedPercent = percent;
            lastLoggedMessage = message;
            plugin.getLogger().info("[push " + percent + "%] " + message);
        }

        private String compactProgressMessage(String message) {
            String compact = Objects.toString(message, "Working...").replace('\r', ' ').replace('\n', ' ').trim();
            if (compact.length() <= TITLE_LIMIT) {
                return compact;
            }
            return compact.substring(0, TITLE_LIMIT - 3) + "...";
        }

        private record ProgressSnapshot(double progress, int percent, String message) {
        }
    }
}

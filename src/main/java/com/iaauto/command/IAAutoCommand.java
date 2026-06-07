package com.iaauto.command;

import com.iaauto.IAAutoPlugin;
import com.iaauto.git.GitPushException;
import com.iaauto.git.GitPushService;
import com.iaauto.git.PushProgress;
import com.iaauto.git.PushResult;
import com.iaauto.i18n.Messages;
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
    private static final String PREFIX = ChatColor.AQUA + "[NekoAutoPack] " + ChatColor.RESET;
    private static final List<String> SUBCOMMANDS = List.of("help", "start", "push", "git", "reload");
    private static final List<String> GIT_ACTIONS = List.of("get", "set", "clear");
    private static final List<GitSetting> GIT_SETTINGS = List.of(
            new GitSetting("submit-method", "git.submit-method"),
            new GitSetting("executable", "git.executable"),
            new GitSetting("github-cli-executable", "git.github-cli-executable"),
            new GitSetting("remote-url", "git.remote-url"),
            new GitSetting("branch", "git.branch"),
            new GitSetting("source-file", "git.source-file"),
            new GitSetting("repository-directory", "git.repository-directory"),
            new GitSetting("repository-file", "git.repository-file"),
            new GitSetting("commit-message", "git.commit-message"),
            new GitSetting("timeout-seconds", "git.timeout-seconds"),
            new GitSetting("proxy.http", "git.proxy.http"),
            new GitSetting("proxy.https", "git.proxy.https"),
            new GitSetting("proxy.no-proxy", "git.proxy.no-proxy"),
            new GitSetting("author.name", "git.author.name"),
            new GitSetting("author.email", "git.author.email")
    );
    private static final long TICKS_PER_SECOND = 20L;
    private static final int MAX_PUSH_RETRIES = 3;
    private static final long PUSH_RETRY_DELAY_MILLIS = 2000L;

    private final IAAutoPlugin plugin;
    private final AtomicBoolean operationRunning = new AtomicBoolean(false);

    public IAAutoCommand(IAAutoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if ("help".equals(subcommand)) {
            sendHelp(sender, label);
            return true;
        }

        if ("start".equals(subcommand)) {
            if (args.length != 1) {
                sendHelp(sender, label);
                return true;
            }
            start(sender);
            return true;
        }

        if ("push".equals(subcommand)) {
            if (args.length != 1) {
                sendHelp(sender, label);
                return true;
            }
            push(sender);
            return true;
        }

        if ("git".equals(subcommand)) {
            git(sender, label, args);
            return true;
        }

        if ("reload".equals(subcommand)) {
            if (args.length != 1) {
                sendHelp(sender, label);
                return true;
            }
            reload(sender);
            return true;
        }

        sendHelp(sender, label);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                    .filter(subcommand -> subcommand.startsWith(prefix))
                    .filter(subcommand -> sender.hasPermission("nap." + subcommand))
                    .toList();
        }

        if (args.length == 2 && "git".equalsIgnoreCase(args[0]) && sender.hasPermission("nap.git")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return GIT_ACTIONS.stream()
                    .filter(action -> action.startsWith(prefix))
                    .toList();
        }

        if (args.length == 3 && "git".equalsIgnoreCase(args[0]) && sender.hasPermission("nap.git")) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if ("get".equals(action) || "set".equals(action) || "clear".equals(action)) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return GIT_SETTINGS.stream()
                        .map(GitSetting::name)
                        .filter(name -> name.startsWith(prefix))
                        .toList();
            }
        }

        return Collections.emptyList();
    }

    private void push(CommandSender sender) {
        if (!sender.hasPermission("nap.push")) {
            sender.sendMessage(PREFIX + ChatColor.RED + messages().text("command.permission-denied"));
            return;
        }

        if (!operationRunning.compareAndSet(false, true)) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + messages().text("command.operation-running"));
            return;
        }

        runPush(sender);
    }

    private void start(CommandSender sender) {
        if (!sender.hasPermission("nap.start")) {
            sender.sendMessage(PREFIX + ChatColor.RED + messages().text("command.permission-denied"));
            return;
        }

        if (!operationRunning.compareAndSet(false, true)) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + messages().text("command.operation-running"));
            return;
        }

        String packCommand = configuredPackCommand();
        GitPushService.SourceFileSnapshot sourceBeforePack;
        try {
            sourceBeforePack = new GitPushService(plugin).snapshotSourceFile();
        } catch (GitPushException exception) {
            operationRunning.set(false);
            sender.sendMessage(PREFIX + ChatColor.RED + compactFailure(exception));
            return;
        }

        sender.sendMessage(PREFIX + ChatColor.GRAY + messages().text("command.running-pack", packCommand));

        boolean dispatched;
        try {
            dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), packCommand);
        } catch (CommandException exception) {
            operationRunning.set(false);
            plugin.getLogger().log(Level.SEVERE, messages().text("log.pack-failed", packCommand), exception);
            sender.sendMessage(PREFIX + ChatColor.RED + messages().text("command.pack-failed", packCommand, compactFailure(exception)));
            return;
        }

        if (!dispatched) {
            operationRunning.set(false);
            sender.sendMessage(PREFIX + ChatColor.RED + messages().text("command.pack-missing", packCommand));
            return;
        }

        long delaySeconds = configuredPushDelaySeconds();
        if (delaySeconds <= 0L) {
            sender.sendMessage(PREFIX + ChatColor.GRAY + messages().text("command.starting-push"));
            runPush(sender, sourceBeforePack);
            return;
        }

        sender.sendMessage(PREFIX + ChatColor.GRAY + messages().text("command.starting-push-delayed", delaySeconds));
        Bukkit.getScheduler().runTaskLater(plugin, () -> runPush(sender, sourceBeforePack), delaySeconds * TICKS_PER_SECOND);
    }

    private void runPush(CommandSender sender) {
        runPush(sender, null);
    }

    private void runPush(CommandSender sender, GitPushService.SourceFileSnapshot sourceBeforePack) {
        Messages messages = messages();
        sender.sendMessage(PREFIX + ChatColor.GRAY + messages.text("command.git-push-start"));
        PushProgressDisplay progressDisplay = new PushProgressDisplay(sender, messages);
        progressDisplay.start();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PushResult result = null;
            Exception failure = null;

            int totalAttempts = MAX_PUSH_RETRIES + 1;
            for (int attempt = 1; attempt <= totalAttempts; attempt++) {
                try {
                    result = new GitPushService(plugin, progressDisplay::update).pushGeneratedZip(sourceBeforePack);
                    failure = null;
                    break;
                } catch (GitPushException exception) {
                    failure = exception;
                } catch (Exception exception) {
                    failure = exception;
                    plugin.getLogger().log(Level.SEVERE, messages.text("log.unexpected-push-error"), exception);
                }

                if (attempt >= totalAttempts || !shouldRetryPushFailure(failure)) {
                    break;
                }

                announcePushRetry(sender, messages, attempt, failure);
                if (!sleepBeforeRetry()) {
                    failure = new GitPushException(messages.text("git.error.wait-interrupted"));
                    break;
                }
            }

            operationRunning.set(false);
            PushResult finalResult = result;
            Exception finalFailure = failure;
            Bukkit.getScheduler().runTask(plugin, () -> {
                progressDisplay.finish(finalFailure);
                reportPushResult(sender, finalResult, finalFailure);
            });
        });
    }

    private boolean shouldRetryPushFailure(Exception failure) {
        if (failure instanceof GitPushException gitFailure) {
            return gitFailure.retryable();
        }
        return false;
    }

    private void announcePushRetry(CommandSender sender, Messages messages, int retryNumber, Exception failure) {
        String message = messages.text("command.push-retrying", retryNumber, MAX_PUSH_RETRIES);
        plugin.getLogger().warning("[push retry " + retryNumber + "/" + MAX_PUSH_RETRIES + "] "
                + compactFailure(failure));
        Bukkit.getScheduler().runTask(plugin, () ->
                sender.sendMessage(PREFIX + ChatColor.YELLOW + message));
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(PUSH_RETRY_DELAY_MILLIS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("nap.reload")) {
            sender.sendMessage(PREFIX + ChatColor.RED + messages().text("command.permission-denied"));
            return;
        }

        plugin.reloadConfig();
        sender.sendMessage(PREFIX + ChatColor.GREEN + messages().text("command.reload-complete"));
    }

    private void git(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("nap.git")) {
            sender.sendMessage(PREFIX + ChatColor.RED + messages().text("command.permission-denied"));
            return;
        }

        if (args.length < 3) {
            sendGitHelp(sender, label);
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        GitSetting setting = findGitSetting(args[2]);
        if (setting == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Unknown git setting: " + args[2]);
            sendGitSettings(sender);
            return;
        }

        if ("get".equals(action)) {
            if (args.length != 3) {
                sendGitHelp(sender, label);
                return;
            }
            showGitSetting(sender, setting);
            return;
        }

        if ("set".equals(action)) {
            if (args.length < 4) {
                sendGitHelp(sender, label);
                return;
            }
            setGitSetting(sender, setting, joinArguments(args, 3));
            return;
        }

        if ("clear".equals(action)) {
            if (args.length != 3) {
                sendGitHelp(sender, label);
                return;
            }
            clearGitSetting(sender, setting);
            return;
        }

        sendGitHelp(sender, label);
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
            sender.sendMessage(PREFIX + ChatColor.RED + messages().text("command.push-no-result"));
            return;
        }

        if (result.committed()) {
            sender.sendMessage(PREFIX + ChatColor.GREEN + messages().text("command.push-committed", result.branch()));
        } else {
            sender.sendMessage(PREFIX + ChatColor.GREEN + messages().text("command.push-no-changes", result.branch()));
        }
        sender.sendMessage(PREFIX + ChatColor.GRAY + messages().text("command.repository", result.repositoryDirectory()));
        sender.sendMessage(PREFIX + ChatColor.GRAY + messages().text("command.tracked-file", result.repositoryFile()));
    }

    private void sendHelp(CommandSender sender, String label) {
        if (!sender.hasPermission("nap.help")) {
            sender.sendMessage(PREFIX + ChatColor.RED + messages().text("command.permission-denied"));
            return;
        }

        Messages messages = messages();
        sender.sendMessage(PREFIX + ChatColor.GOLD + messages.text("help.header"));
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " help" + ChatColor.GRAY + " - " + messages.text("help.help"));
        if (sender.hasPermission("nap.start")) {
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " start" + ChatColor.GRAY + " - " + messages.text("help.start"));
        }
        if (sender.hasPermission("nap.push")) {
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " push" + ChatColor.GRAY + " - " + messages.text("help.push"));
        }
        if (sender.hasPermission("nap.git")) {
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " git" + ChatColor.GRAY + " - Configure git settings.");
        }
        if (sender.hasPermission("nap.reload")) {
            sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload" + ChatColor.GRAY + " - " + messages.text("help.reload"));
        }
    }

    private void sendGitHelp(CommandSender sender, String label) {
        sender.sendMessage(PREFIX + ChatColor.GOLD + "Git configuration commands:");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " git get <setting>" + ChatColor.GRAY + " - Show a git setting.");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " git set <setting> <value>" + ChatColor.GRAY + " - Save a git setting.");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " git clear <setting>" + ChatColor.GRAY + " - Clear a git setting.");
        sendGitSettings(sender);
    }

    private void sendGitSettings(CommandSender sender) {
        sender.sendMessage(ChatColor.GRAY + "Settings: " + String.join(", ", GIT_SETTINGS.stream()
                .map(GitSetting::name)
                .toList()));
    }

    private void showGitSetting(CommandSender sender, GitSetting setting) {
        Object value = plugin.getConfig().get(setting.path());
        sender.sendMessage(PREFIX + ChatColor.YELLOW + setting.name() + ChatColor.GRAY + " = " + ChatColor.WHITE + Objects.toString(value, ""));
    }

    private void setGitSetting(CommandSender sender, GitSetting setting, String value) {
        if ("timeout-seconds".equals(setting.name())) {
            try {
                plugin.getConfig().set(setting.path(), Math.max(5L, Long.parseLong(value.trim())));
            } catch (NumberFormatException exception) {
                sender.sendMessage(PREFIX + ChatColor.RED + "timeout-seconds must be a number.");
                return;
            }
        } else if ("submit-method".equals(setting.name())) {
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            if (!"git".equals(normalized) && !"github-cli".equals(normalized) && !"gh".equals(normalized)) {
                sender.sendMessage(PREFIX + ChatColor.RED + "submit-method must be git or github-cli.");
                return;
            }
            plugin.getConfig().set(setting.path(), "gh".equals(normalized) ? "github-cli" : normalized);
        } else {
            plugin.getConfig().set(setting.path(), value.trim());
        }

        plugin.saveConfig();
        sender.sendMessage(PREFIX + ChatColor.GREEN + "Saved " + setting.name() + ".");
    }

    private void clearGitSetting(CommandSender sender, GitSetting setting) {
        if ("timeout-seconds".equals(setting.name())) {
            plugin.getConfig().set(setting.path(), null);
        } else {
            plugin.getConfig().set(setting.path(), "");
        }

        plugin.saveConfig();
        sender.sendMessage(PREFIX + ChatColor.GREEN + "Cleared " + setting.name() + ".");
    }

    private GitSetting findGitSetting(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return GIT_SETTINGS.stream()
                .filter(setting -> setting.name().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private String joinArguments(String[] args, int startIndex) {
        StringBuilder value = new StringBuilder();
        for (int index = startIndex; index < args.length; index++) {
            if (!value.isEmpty()) {
                value.append(' ');
            }
            value.append(args[index]);
        }
        return value.toString();
    }

    private Messages messages() {
        return Messages.from(plugin.getConfig());
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
        private final Messages messages;
        private BossBar bossBar;
        private int lastLoggedPercent = -1;
        private String lastLoggedMessage = "";
        private double lastVisibleProgress;
        private boolean finished;

        private PushProgressDisplay(CommandSender sender, Messages messages) {
            this.sender = sender;
            this.messages = messages;
        }

        private void start() {
            plugin.getLogger().info("[push 0%] " + messages.text("progress.initial"));

            if (!(sender instanceof Player player)) {
                return;
            }

            bossBar = Bukkit.createBossBar(
                    ChatColor.AQUA + messages.text("boss.starting"),
                    BarColor.BLUE,
                    BarStyle.SEGMENTED_20
            );
            bossBar.setProgress(0.0D);
            bossBar.addPlayer(player);
        }

        private void update(PushProgress progress) {
            ProgressSnapshot snapshot = recordProgress(progress);

            if (progress.notifySender()) {
                String senderMessage = Objects.toString(progress.message(), messages.text("progress.default")).trim();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!finished && !senderMessage.isBlank()) {
                        sender.sendMessage(PREFIX + ChatColor.YELLOW + senderMessage);
                    }
                });
            }

            if (bossBar == null) {
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (finished) {
                    return;
                }

                bossBar.setColor(BarColor.BLUE);
                bossBar.setTitle(ChatColor.AQUA + messages.text("boss.progress", snapshot.percent(), ChatColor.WHITE + snapshot.message()));
                bossBar.setProgress(snapshot.progress());
            });
        }

        private void finish(Exception failure) {
            finished = true;
            if (failure == null) {
                logProgress(100, messages.text("progress.finished"));
            } else {
                plugin.getLogger().warning("[push failed] " + compactProgressMessage(compactFailure(failure)));
            }

            if (bossBar == null) {
                return;
            }

            if (failure == null) {
                bossBar.setColor(BarColor.GREEN);
                bossBar.setTitle(ChatColor.GREEN + messages.text("boss.complete"));
                bossBar.setProgress(1.0D);
            } else {
                bossBar.setColor(BarColor.RED);
                bossBar.setTitle(ChatColor.RED + messages.text("boss.failed"));
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
            String compact = Objects.toString(message, messages.text("progress.default")).replace('\r', ' ').replace('\n', ' ').trim();
            if (compact.length() <= TITLE_LIMIT) {
                return compact;
            }
            return compact.substring(0, TITLE_LIMIT - 3) + "...";
        }

        private record ProgressSnapshot(double progress, int percent, String message) {
        }
    }

    private record GitSetting(String name, String path) {
    }
}

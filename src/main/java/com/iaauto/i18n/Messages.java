package com.iaauto.i18n;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class Messages {
    private final Language language;

    private Messages(Language language) {
        this.language = language;
    }

    public static Messages from(FileConfiguration configuration) {
        String configuredLanguage = Objects.toString(configuration.getString("language"), "en");
        return new Messages(Language.from(configuredLanguage));
    }

    public String text(String key, Object... arguments) {
        String template = language.messages.get(key);
        if (template == null) {
            template = Language.ENGLISH.messages.getOrDefault(key, key);
        }
        return String.format(Locale.ROOT, template, arguments);
    }

    private enum Language {
        ENGLISH(Map.ofEntries(
                Map.entry("command.permission-denied", "You do not have permission to run this command."),
                Map.entry("command.operation-running", "A NekoAutoPack operation is already running."),
                Map.entry("command.running-pack", "Running /%s..."),
                Map.entry("command.pack-failed", "Failed to run /%s: %s"),
                Map.entry("command.pack-missing", "Command /%s was not found or could not be run."),
                Map.entry("command.starting-push", "Starting /nap push..."),
                Map.entry("command.starting-push-delayed", "Starting /nap push in %d seconds..."),
                Map.entry("command.git-push-start", "Starting git push for ItemsAdder generated.zip..."),
                Map.entry("command.reload-complete", "Configuration reloaded."),
                Map.entry("command.push-no-result", "Push finished without a result."),
                Map.entry("command.push-committed", "generated.zip was committed and pushed to origin/%s."),
                Map.entry("command.push-no-changes", "No file changes were found; origin/%s was checked."),
                Map.entry("command.repository", "Repository: %s"),
                Map.entry("command.tracked-file", "Tracked file: %s"),
                Map.entry("help.header", "Commands:"),
                Map.entry("help.help", "Show this help message."),
                Map.entry("help.start", "Run the pack command, then push generated.zip."),
                Map.entry("help.push", "Push the latest generated.zip to git."),
                Map.entry("help.reload", "Reload the plugin configuration."),
                Map.entry("log.pack-failed", "Failed to run /%s."),
                Map.entry("log.unexpected-push-error", "Unexpected error while pushing generated.zip."),
                Map.entry("progress.initial", "Starting git push for ItemsAdder generated.zip"),
                Map.entry("progress.finished", "Push finished"),
                Map.entry("progress.default", "Working..."),
                Map.entry("boss.starting", "NekoAutoPack push: starting..."),
                Map.entry("boss.progress", "NekoAutoPack push %d%% %s"),
                Map.entry("boss.complete", "NekoAutoPack push complete"),
                Map.entry("boss.failed", "NekoAutoPack push failed"),
                Map.entry("git.progress.validating-config", "Validating git configuration"),
                Map.entry("git.progress.checking-source", "Checking ItemsAdder generated.zip"),
                Map.entry("git.progress.preparing-repository", "Preparing local repository"),
                Map.entry("git.progress.applying-author", "Applying git author configuration"),
                Map.entry("git.progress.copying-source", "Copying generated.zip into the repository"),
                Map.entry("git.progress.staging-source", "Staging generated.zip"),
                Map.entry("git.progress.checking-changes", "Checking repository changes"),
                Map.entry("git.progress.committing-source", "Committing generated.zip"),
                Map.entry("git.progress.no-changes", "No file changes found; verifying remote branch"),
                Map.entry("git.progress.pushing", "Pushing to origin/%s"),
                Map.entry("git.progress.finished", "Push finished"),
                Map.entry("git.progress.cloning", "Cloning repository"),
                Map.entry("git.progress.initializing", "Initializing local repository"),
                Map.entry("git.progress.configuring-remote", "Configuring git remote"),
                Map.entry("git.progress.checkout-branch", "Checking out branch %s"),
                Map.entry("git.progress.resetting-branch", "Resetting local branch to %s"),
                Map.entry("git.progress.rebuilding-branch", "Rebuilding unpublished local branch"),
                Map.entry("git.progress.fetching-branch", "Fetching origin/%s"),
                Map.entry("git.progress.waiting-refresh", "Waiting for generated.zip to refresh"),
                Map.entry("git.progress.waiting-writes", "Waiting for generated.zip writes to finish"),
                Map.entry("git.progress.changed-checking", "generated.zip changed while being checked; waiting again"),
                Map.entry("git.progress.changed-copying", "generated.zip changed while copying; retrying"),
                Map.entry("git.progress.git-push", "Git push"),
                Map.entry("git.error.remote-url-empty", "git.remote-url is empty. Configure it in plugins/NekoAutoPack/config.yml."),
                Map.entry("git.error.repository-not-empty", "Repository directory exists but is not an empty git repository: %s"),
                Map.entry("git.error.repository-no-parent", "Repository directory must have a parent: %s"),
                Map.entry("git.error.config-path-empty", "A configured path is empty."),
                Map.entry("git.error.invalid-config-path", "Invalid path in config: %s"),
                Map.entry("git.error.repository-file-empty", "git.repository-file is empty."),
                Map.entry("git.error.repository-file-outside", "git.repository-file must stay inside the repository directory."),
                Map.entry("git.error.source-refresh-timeout", "Source file did not refresh within %d seconds after running the pack command: %s"),
                Map.entry("git.error.source-missing", "Source file does not exist: %s"),
                Map.entry("git.error.source-stability-timeout", "Source file did not become stable within %d seconds: %s"),
                Map.entry("git.error.source-unreadable-zip", "Source file is not a complete readable zip: %s"),
                Map.entry("git.error.source-read-failed", "Failed to read source zip: %s"),
                Map.entry("git.error.copy-failed", "Failed to copy %s to %s."),
                Map.entry("git.error.source-kept-changing", "Source file kept changing while being copied: %s"),
                Map.entry("git.error.verify-copy-failed", "Failed to verify copied generated.zip."),
                Map.entry("git.error.inspect-source-failed", "Failed to inspect source file: %s"),
                Map.entry("git.error.wait-interrupted", "Interrupted while waiting for generated.zip to refresh."),
                Map.entry("git.error.inspect-directory-failed", "Failed to inspect directory: %s"),
                Map.entry("git.error.create-directory-failed", "Failed to create directory: %s"),
                Map.entry("git.error.start-failed", "Failed to start git executable '%s'."),
                Map.entry("git.error.interrupted", "Git command was interrupted: %s"),
                Map.entry("git.error.timed-out", "Git command timed out after %d seconds: %s"),
                Map.entry("git.error.output-read-failed-inline", "Failed to read git output: %s"),
                Map.entry("git.error.output-interrupted", "Interrupted while reading git output."),
                Map.entry("git.error.output-failed", "Failed to read git output."),
                Map.entry("git.error.output-timed-out", "Timed out while reading git output."),
                Map.entry("git.error.no-output", "No git output."),
                Map.entry("git.error.command-failed", "Git command failed (%s, exit %d): %s")
        )),
        CHINESE(Map.ofEntries(
                Map.entry("command.permission-denied", "你没有权限执行这个命令。"),
                Map.entry("command.operation-running", "已有一个 NekoAutoPack 操作正在运行。"),
                Map.entry("command.running-pack", "正在运行 /%s..."),
                Map.entry("command.pack-failed", "运行 /%s 失败：%s"),
                Map.entry("command.pack-missing", "命令 /%s 不存在或无法运行。"),
                Map.entry("command.starting-push", "正在启动 /nap push..."),
                Map.entry("command.starting-push-delayed", "%d 秒后启动 /nap push..."),
                Map.entry("command.git-push-start", "正在为 ItemsAdder generated.zip 执行 git 推送..."),
                Map.entry("command.reload-complete", "配置已重新加载。"),
                Map.entry("command.push-no-result", "推送已结束，但没有返回结果。"),
                Map.entry("command.push-committed", "generated.zip 已提交并推送到 origin/%s。"),
                Map.entry("command.push-no-changes", "未发现文件变更；已检查 origin/%s。"),
                Map.entry("command.repository", "仓库：%s"),
                Map.entry("command.tracked-file", "跟踪文件：%s"),
                Map.entry("help.header", "命令："),
                Map.entry("help.help", "显示这条帮助信息。"),
                Map.entry("help.start", "运行打包命令，然后推送 generated.zip。"),
                Map.entry("help.push", "将最新的 generated.zip 推送到 git。"),
                Map.entry("help.reload", "重新加载插件配置。"),
                Map.entry("log.pack-failed", "运行 /%s 失败。"),
                Map.entry("log.unexpected-push-error", "推送 generated.zip 时发生意外错误。"),
                Map.entry("progress.initial", "开始为 ItemsAdder generated.zip 执行 git 推送"),
                Map.entry("progress.finished", "推送完成"),
                Map.entry("progress.default", "处理中..."),
                Map.entry("boss.starting", "NekoAutoPack 推送：正在启动..."),
                Map.entry("boss.progress", "NekoAutoPack 推送 %d%% %s"),
                Map.entry("boss.complete", "NekoAutoPack 推送完成"),
                Map.entry("boss.failed", "NekoAutoPack 推送失败"),
                Map.entry("git.progress.validating-config", "正在验证 git 配置"),
                Map.entry("git.progress.checking-source", "正在检查 ItemsAdder generated.zip"),
                Map.entry("git.progress.preparing-repository", "正在准备本地仓库"),
                Map.entry("git.progress.applying-author", "正在应用 git 作者配置"),
                Map.entry("git.progress.copying-source", "正在将 generated.zip 复制到仓库"),
                Map.entry("git.progress.staging-source", "正在暂存 generated.zip"),
                Map.entry("git.progress.checking-changes", "正在检查仓库变更"),
                Map.entry("git.progress.committing-source", "正在提交 generated.zip"),
                Map.entry("git.progress.no-changes", "未发现文件变更，正在验证远程分支"),
                Map.entry("git.progress.pushing", "正在推送到 origin/%s"),
                Map.entry("git.progress.finished", "推送完成"),
                Map.entry("git.progress.cloning", "正在克隆仓库"),
                Map.entry("git.progress.initializing", "正在初始化本地仓库"),
                Map.entry("git.progress.configuring-remote", "正在配置 git 远程仓库"),
                Map.entry("git.progress.checkout-branch", "正在切换到分支 %s"),
                Map.entry("git.progress.resetting-branch", "正在将本地分支重置到 %s"),
                Map.entry("git.progress.rebuilding-branch", "正在重建未发布的本地分支"),
                Map.entry("git.progress.fetching-branch", "正在获取 origin/%s"),
                Map.entry("git.progress.waiting-refresh", "正在等待 generated.zip 刷新"),
                Map.entry("git.progress.waiting-writes", "正在等待 generated.zip 写入完成"),
                Map.entry("git.progress.changed-checking", "检查期间 generated.zip 发生变化，正在重新等待"),
                Map.entry("git.progress.changed-copying", "复制期间 generated.zip 发生变化，正在重试"),
                Map.entry("git.progress.git-push", "Git 推送"),
                Map.entry("git.error.remote-url-empty", "git.remote-url 为空。请在 plugins/NekoAutoPack/config.yml 中配置。"),
                Map.entry("git.error.repository-not-empty", "仓库目录已存在，但不是空的 git 仓库：%s"),
                Map.entry("git.error.repository-no-parent", "仓库目录必须有父目录：%s"),
                Map.entry("git.error.config-path-empty", "有一个配置路径为空。"),
                Map.entry("git.error.invalid-config-path", "配置中的路径无效：%s"),
                Map.entry("git.error.repository-file-empty", "git.repository-file 为空。"),
                Map.entry("git.error.repository-file-outside", "git.repository-file 必须位于仓库目录内。"),
                Map.entry("git.error.source-refresh-timeout", "运行打包命令后，源文件未在 %d 秒内刷新：%s"),
                Map.entry("git.error.source-missing", "源文件不存在：%s"),
                Map.entry("git.error.source-stability-timeout", "源文件未在 %d 秒内稳定：%s"),
                Map.entry("git.error.source-unreadable-zip", "源文件不是完整可读的 zip：%s"),
                Map.entry("git.error.source-read-failed", "读取源 zip 失败：%s"),
                Map.entry("git.error.copy-failed", "复制 %s 到 %s 失败。"),
                Map.entry("git.error.source-kept-changing", "复制期间源文件持续变化：%s"),
                Map.entry("git.error.verify-copy-failed", "验证已复制的 generated.zip 失败。"),
                Map.entry("git.error.inspect-source-failed", "检查源文件失败：%s"),
                Map.entry("git.error.wait-interrupted", "等待 generated.zip 刷新时被中断。"),
                Map.entry("git.error.inspect-directory-failed", "检查目录失败：%s"),
                Map.entry("git.error.create-directory-failed", "创建目录失败：%s"),
                Map.entry("git.error.start-failed", "启动 git 可执行文件 '%s' 失败。"),
                Map.entry("git.error.interrupted", "Git 命令被中断：%s"),
                Map.entry("git.error.timed-out", "Git 命令在 %d 秒后超时：%s"),
                Map.entry("git.error.output-read-failed-inline", "读取 git 输出失败：%s"),
                Map.entry("git.error.output-interrupted", "读取 git 输出时被中断。"),
                Map.entry("git.error.output-failed", "读取 git 输出失败。"),
                Map.entry("git.error.output-timed-out", "读取 git 输出超时。"),
                Map.entry("git.error.no-output", "没有 git 输出。"),
                Map.entry("git.error.command-failed", "Git 命令失败（%s，退出码 %d）：%s")
        ));

        private final Map<String, String> messages;

        Language(Map<String, String> messages) {
            this.messages = messages;
        }

        private static Language from(String configuredLanguage) {
            String normalized = configuredLanguage.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (normalized) {
                case "zh", "zh_cn", "zh_hans", "cn", "chinese" -> CHINESE;
                default -> ENGLISH;
            };
        }
    }
}

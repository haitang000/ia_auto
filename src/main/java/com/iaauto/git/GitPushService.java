package com.iaauto.git;

import com.iaauto.IAAutoPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public final class GitPushService {
    private final Path serverRoot;
    private final GitConfig config;

    public GitPushService(IAAutoPlugin plugin) {
        this.serverRoot = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        this.config = GitConfig.from(plugin.getConfig());
    }

    public PushResult pushGeneratedZip() throws GitPushException {
        if (isBlank(config.remoteUrl())) {
            throw new GitPushException("git.remote-url is empty. Configure it in plugins/IAAuto/config.yml.");
        }

        Path sourceFile = resolveServerPath(config.sourceFile());
        if (!Files.isRegularFile(sourceFile)) {
            throw new GitPushException("Source file does not exist: " + sourceFile);
        }

        Path repositoryDirectory = resolveServerPath(config.repositoryDirectory());
        ensureRepository(repositoryDirectory);
        applyAuthorConfig(repositoryDirectory);

        Path repositoryFile = resolveInsideRepository(repositoryDirectory, config.repositoryFile());
        Path repositoryFileParent = repositoryFile.getParent();
        if (repositoryFileParent != null) {
            createDirectories(repositoryFileParent);
        }
        copySourceToRepository(sourceFile, repositoryFile);

        String gitFilePath = toGitPath(repositoryDirectory.relativize(repositoryFile));
        runGitOrThrow(repositoryDirectory, "add", "--", gitFilePath);

        CommandResult status = runGitOrThrow(repositoryDirectory, "status", "--porcelain", "--", gitFilePath);
        boolean committed = !status.output().isBlank();
        if (committed) {
            runGitOrThrow(repositoryDirectory, "commit", "-m", config.commitMessage(), "--", gitFilePath);
        }

        runGitOrThrow(repositoryDirectory, "push", "-u", "origin", config.branch());
        return new PushResult(committed, config.branch(), repositoryDirectory.toString(), gitFilePath);
    }

    private void ensureRepository(Path repositoryDirectory) throws GitPushException {
        if (Files.isDirectory(repositoryDirectory.resolve(".git"))) {
            configureRemote(repositoryDirectory);
            checkoutBranch(repositoryDirectory);
            return;
        }

        if (Files.exists(repositoryDirectory) && !isDirectoryEmpty(repositoryDirectory)) {
            throw new GitPushException("Repository directory exists but is not an empty git repository: " + repositoryDirectory);
        }

        cloneRepository(repositoryDirectory);
        configureRemote(repositoryDirectory);
        checkoutBranch(repositoryDirectory);
    }

    private void cloneRepository(Path repositoryDirectory) throws GitPushException {
        Path parent = repositoryDirectory.getParent();
        if (parent == null) {
            throw new GitPushException("Repository directory must have a parent: " + repositoryDirectory);
        }

        createDirectories(parent);
        String directoryName = repositoryDirectory.getFileName().toString();
        CommandResult clone = runGit(parent, List.of("clone", config.remoteUrl(), directoryName));
        if (clone.exitCode() == 0) {
            return;
        }

        createDirectories(repositoryDirectory);
        CommandResult init = runGit(repositoryDirectory, List.of("init"));
        if (init.exitCode() != 0) {
            throw commandFailed(List.of("clone", config.remoteUrl(), directoryName), clone);
        }
    }

    private void configureRemote(Path repositoryDirectory) throws GitPushException {
        CommandResult existingRemote = runGit(repositoryDirectory, List.of("remote", "get-url", "origin"));
        if (existingRemote.exitCode() == 0) {
            if (!Objects.equals(existingRemote.output().trim(), config.remoteUrl())) {
                runGitOrThrow(repositoryDirectory, "remote", "set-url", "origin", config.remoteUrl());
            }
            return;
        }

        runGitOrThrow(repositoryDirectory, "remote", "add", "origin", config.remoteUrl());
    }

    private void checkoutBranch(Path repositoryDirectory) throws GitPushException {
        CommandResult checkoutExisting = runGit(repositoryDirectory, List.of("checkout", config.branch()));
        if (checkoutExisting.exitCode() == 0) {
            return;
        }

        runGitOrThrow(repositoryDirectory, "checkout", "-B", config.branch());
    }

    private void applyAuthorConfig(Path repositoryDirectory) throws GitPushException {
        if (!isBlank(config.authorName())) {
            runGitOrThrow(repositoryDirectory, "config", "user.name", config.authorName());
        }
        if (!isBlank(config.authorEmail())) {
            runGitOrThrow(repositoryDirectory, "config", "user.email", config.authorEmail());
        }
    }

    private Path resolveServerPath(String configuredPath) throws GitPushException {
        String pathText = configuredPath == null ? "" : configuredPath.trim();
        if (pathText.isEmpty()) {
            throw new GitPushException("A configured path is empty.");
        }

        pathText = stripServerRootPrefix(pathText);

        try {
            Path path = Path.of(pathText);
            if (path.isAbsolute()) {
                return path.toAbsolutePath().normalize();
            }
            return serverRoot.resolve(path).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new GitPushException("Invalid path in config: " + configuredPath, exception);
        }
    }

    private String stripServerRootPrefix(String configuredPath) {
        String normalized = configuredPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/plugins/")) {
            return configuredPath.substring(1);
        }
        return configuredPath;
    }

    private Path resolveInsideRepository(Path repositoryDirectory, String repositoryFile) throws GitPushException {
        if (isBlank(repositoryFile)) {
            throw new GitPushException("git.repository-file is empty.");
        }

        Path resolved = repositoryDirectory.resolve(repositoryFile).normalize();
        if (!resolved.startsWith(repositoryDirectory)) {
            throw new GitPushException("git.repository-file must stay inside the repository directory.");
        }
        return resolved;
    }

    private void copySourceToRepository(Path sourceFile, Path repositoryFile) throws GitPushException {
        try {
            Files.copy(sourceFile, repositoryFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new GitPushException("Failed to copy " + sourceFile + " to " + repositoryFile + ".", exception);
        }
    }

    private boolean isDirectoryEmpty(Path directory) throws GitPushException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (IOException exception) {
            throw new GitPushException("Failed to inspect directory: " + directory, exception);
        }
    }

    private void createDirectories(Path directory) throws GitPushException {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new GitPushException("Failed to create directory: " + directory, exception);
        }
    }

    private CommandResult runGitOrThrow(Path workingDirectory, String... arguments) throws GitPushException {
        List<String> argumentList = List.of(arguments);
        CommandResult result = runGit(workingDirectory, argumentList);
        if (result.exitCode() == 0) {
            return result;
        }
        throw commandFailed(argumentList, result);
    }

    private CommandResult runGit(Path workingDirectory, List<String> arguments) throws GitPushException {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(config.executable());
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new GitPushException("Failed to start git executable '" + config.executable() + "'.", exception);
        }

        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process.getInputStream()));
        boolean finished;
        try {
            finished = process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GitPushException("Git command was interrupted: " + describeGitCommand(arguments), exception);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new GitPushException("Git command timed out after " + config.timeoutSeconds() + " seconds: " + describeGitCommand(arguments));
        }

        String output = getOutput(outputFuture);
        return new CommandResult(process.exitValue(), output == null ? "" : output.trim());
    }

    private String readProcessOutput(InputStream inputStream) {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "Failed to read git output: " + exception.getMessage();
        }
    }

    private String getOutput(CompletableFuture<String> outputFuture) throws GitPushException {
        try {
            return outputFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitPushException("Interrupted while reading git output.", exception);
        } catch (ExecutionException exception) {
            throw new GitPushException("Failed to read git output.", exception);
        } catch (TimeoutException exception) {
            throw new GitPushException("Timed out while reading git output.", exception);
        }
    }

    private GitPushException commandFailed(List<String> arguments, CommandResult result) {
        String output = result.output().isBlank() ? "No git output." : sanitizeOutput(result.output());
        return new GitPushException("Git command failed (" + describeGitCommand(arguments) + ", exit " + result.exitCode() + "): " + output);
    }

    private String describeGitCommand(List<String> arguments) {
        return config.executable() + " " + arguments.stream()
                .map(this::redactArgument)
                .collect(Collectors.joining(" "));
    }

    private String redactArgument(String argument) {
        if (Objects.equals(argument, config.remoteUrl())) {
            return "<remote-url>";
        }
        return argument;
    }

    private String sanitizeOutput(String output) {
        String sanitized = output == null ? "" : output.trim();
        if (!isBlank(config.remoteUrl())) {
            sanitized = sanitized.replace(config.remoteUrl(), "<remote-url>");
            String redacted = redactRemoteUserInfo(config.remoteUrl());
            sanitized = sanitized.replace(redacted, "<remote-url>");
        }
        return sanitized;
    }

    private String redactRemoteUserInfo(String remoteUrl) {
        int schemeIndex = remoteUrl.indexOf("://");
        if (schemeIndex < 0) {
            return remoteUrl;
        }

        int userInfoStart = schemeIndex + 3;
        int atIndex = remoteUrl.indexOf('@', userInfoStart);
        if (atIndex < 0) {
            return remoteUrl;
        }

        return remoteUrl.substring(0, userInfoStart) + "<credentials>@" + remoteUrl.substring(atIndex + 1);
    }

    private String toGitPath(Path path) {
        return path.toString().replace(File.separatorChar, '/');
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record CommandResult(int exitCode, String output) {
    }

    private record GitConfig(
            String executable,
            String remoteUrl,
            String branch,
            String sourceFile,
            String repositoryDirectory,
            String repositoryFile,
            String commitMessage,
            long timeoutSeconds,
            String authorName,
            String authorEmail
    ) {
        private static GitConfig from(FileConfiguration configuration) {
            return new GitConfig(
                    getString(configuration, "git.executable", "git"),
                    getString(configuration, "git.remote-url", ""),
                    getString(configuration, "git.branch", "main"),
                    getString(configuration, "git.source-file", "plugins/ItemsAdder/output/generated.zip"),
                    getString(configuration, "git.repository-directory", "plugins/IAAuto/repository"),
                    getString(configuration, "git.repository-file", "generated.zip"),
                    getString(configuration, "git.commit-message", "Update ItemsAdder generated.zip"),
                    Math.max(5L, configuration.getLong("git.timeout-seconds", 120L)),
                    getString(configuration, "git.author.name", ""),
                    getString(configuration, "git.author.email", "")
            );
        }

        private static String getString(FileConfiguration configuration, String path, String defaultValue) {
            return Objects.toString(configuration.getString(path), defaultValue).trim();
        }
    }
}

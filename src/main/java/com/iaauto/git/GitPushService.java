package com.iaauto.git;

import com.iaauto.IAAutoPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class GitPushService {
    private final Path serverRoot;
    private final GitConfig config;
    private final Consumer<PushProgress> progressListener;

    public GitPushService(IAAutoPlugin plugin) {
        this(plugin, progress -> {
        });
    }

    public GitPushService(IAAutoPlugin plugin, Consumer<PushProgress> progressListener) {
        this.serverRoot = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        this.config = GitConfig.from(plugin.getConfig());
        this.progressListener = progressListener == null ? progress -> {
        } : progressListener;
    }

    public PushResult pushGeneratedZip() throws GitPushException {
        reportProgress(0.02D, "Validating git configuration");
        if (isBlank(config.remoteUrl())) {
            throw new GitPushException("git.remote-url is empty. Configure it in plugins/IAAuto/config.yml.");
        }

        reportProgress(0.08D, "Checking ItemsAdder generated.zip");
        Path sourceFile = resolveServerPath(config.sourceFile());
        if (!Files.isRegularFile(sourceFile)) {
            throw new GitPushException("Source file does not exist: " + sourceFile);
        }

        reportProgress(0.14D, "Preparing local repository");
        Path repositoryDirectory = resolveServerPath(config.repositoryDirectory());
        ensureRepository(repositoryDirectory);

        reportProgress(0.34D, "Applying git author configuration");
        applyAuthorConfig(repositoryDirectory);

        reportProgress(0.42D, "Copying generated.zip into the repository");
        Path repositoryFile = resolveInsideRepository(repositoryDirectory, config.repositoryFile());
        Path repositoryFileParent = repositoryFile.getParent();
        if (repositoryFileParent != null) {
            createDirectories(repositoryFileParent);
        }
        copySourceToRepository(sourceFile, repositoryFile);

        reportProgress(0.52D, "Staging generated.zip");
        String gitFilePath = toGitPath(repositoryDirectory.relativize(repositoryFile));
        runGitOrThrow(repositoryDirectory, "add", "--", gitFilePath);

        reportProgress(0.60D, "Checking repository changes");
        CommandResult status = runGitOrThrow(repositoryDirectory, "status", "--porcelain", "--", gitFilePath);
        boolean committed = !status.output().isBlank();
        if (committed) {
            reportProgress(0.68D, "Committing generated.zip");
            runGitOrThrow(repositoryDirectory, "commit", "-m", config.commitMessage(), "--", gitFilePath);
        } else {
            reportProgress(0.68D, "No file changes found; verifying remote branch");
        }

        reportProgress(0.76D, "Pushing to origin/" + config.branch());
        runGitOrThrow(repositoryDirectory, new ProgressRange(0.76D, 0.98D, "Git push"), "push", "--progress", "-u", "origin", config.branch());
        reportProgress(1.0D, "Push finished");
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

        reportProgress(0.18D, "Cloning repository");
        createDirectories(parent);
        String directoryName = repositoryDirectory.getFileName().toString();
        CommandResult clone = runGit(parent, List.of("clone", config.remoteUrl(), directoryName));
        if (clone.exitCode() == 0) {
            return;
        }

        reportProgress(0.24D, "Initializing local repository");
        createDirectories(repositoryDirectory);
        CommandResult init = runGit(repositoryDirectory, List.of("init"));
        if (init.exitCode() != 0) {
            throw commandFailed(List.of("clone", config.remoteUrl(), directoryName), clone);
        }
    }

    private void configureRemote(Path repositoryDirectory) throws GitPushException {
        reportProgress(0.24D, "Configuring git remote");
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
        reportProgress(0.30D, "Checking out branch " + config.branch());
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
        return runGitOrThrow(workingDirectory, null, arguments);
    }

    private CommandResult runGitOrThrow(Path workingDirectory, ProgressRange progressRange, String... arguments) throws GitPushException {
        List<String> argumentList = List.of(arguments);
        CommandResult result = runGit(workingDirectory, argumentList, progressRange);
        if (result.exitCode() == 0) {
            return result;
        }
        throw commandFailed(argumentList, result);
    }

    private CommandResult runGit(Path workingDirectory, List<String> arguments) throws GitPushException {
        return runGit(workingDirectory, arguments, null);
    }

    private CommandResult runGit(Path workingDirectory, List<String> arguments, ProgressRange progressRange) throws GitPushException {
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

        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process.getInputStream(), progressRange));
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

    private String readProcessOutput(InputStream inputStream, ProgressRange progressRange) {
        try (inputStream; Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            StringBuilder output = new StringBuilder();
            StringBuilder segment = new StringBuilder();
            int character;
            while ((character = reader.read()) != -1) {
                output.append((char) character);
                if (character == '\r' || character == '\n') {
                    flushGitOutputSegment(segment, progressRange);
                } else {
                    segment.append((char) character);
                }
            }
            flushGitOutputSegment(segment, progressRange);
            return output.toString();
        } catch (IOException exception) {
            return "Failed to read git output: " + exception.getMessage();
        }
    }

    private void flushGitOutputSegment(StringBuilder segment, ProgressRange progressRange) {
        if (segment.isEmpty()) {
            return;
        }

        String output = sanitizeOutput(segment.toString());
        segment.setLength(0);
        if (progressRange == null || output.isBlank()) {
            return;
        }

        reportGitProgress(progressRange, output);
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

    private void reportGitProgress(ProgressRange progressRange, String output) {
        int percent = findFirstPercent(output);
        if (percent >= 0) {
            double progress = scaleGitProgress(progressRange, output, percent);
            reportProgress(progress, progressRange.label() + ": " + output);
            return;
        }

        reportProgress(progressRange.start(), progressRange.label() + ": " + output);
    }

    private double scaleGitProgress(ProgressRange progressRange, String output, int percent) {
        GitProgressStage stage = GitProgressStage.from(output);
        double normalized = percent / 100.0D;
        if (stage != null) {
            normalized = stage.start() + ((stage.end() - stage.start()) * normalized);
        }
        return progressRange.start() + ((progressRange.end() - progressRange.start()) * normalized);
    }

    private int findFirstPercent(String output) {
        for (int index = 0; index < output.length(); index++) {
            if (output.charAt(index) != '%') {
                continue;
            }

            int start = index - 1;
            while (start >= 0 && Character.isDigit(output.charAt(start))) {
                start--;
            }

            if (start == index - 1) {
                continue;
            }

            try {
                int percent = Integer.parseInt(output.substring(start + 1, index));
                return Math.max(0, Math.min(100, percent));
            } catch (NumberFormatException ignored) {
                continue;
            }
        }
        return -1;
    }

    private void reportProgress(double progress, String message) {
        progressListener.accept(new PushProgress(progress, message));
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

    private record ProgressRange(double start, double end, String label) {
    }

    private enum GitProgressStage {
        ENUMERATING("Enumerating objects", 0.0D, 0.10D),
        COUNTING("Counting objects", 0.10D, 0.25D),
        COMPRESSING("Compressing objects", 0.25D, 0.55D),
        WRITING("Writing objects", 0.55D, 0.92D),
        RESOLVING("Resolving deltas", 0.92D, 1.0D);

        private final String prefix;
        private final double start;
        private final double end;

        GitProgressStage(String prefix, double start, double end) {
            this.prefix = prefix;
            this.start = start;
            this.end = end;
        }

        private double start() {
            return start;
        }

        private double end() {
            return end;
        }

        private static GitProgressStage from(String output) {
            for (GitProgressStage stage : values()) {
                if (output.contains(stage.prefix)) {
                    return stage;
                }
            }
            return null;
        }
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

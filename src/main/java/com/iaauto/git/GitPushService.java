package com.iaauto.git;

import com.iaauto.IAAutoPlugin;
import com.iaauto.i18n.Messages;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
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
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class GitPushService {
    private final Path serverRoot;
    private final GitConfig config;
    private final Messages messages;
    private final Consumer<PushProgress> progressListener;

    public GitPushService(IAAutoPlugin plugin) {
        this(plugin, progress -> {
        });
    }

    public GitPushService(IAAutoPlugin plugin, Consumer<PushProgress> progressListener) {
        this.serverRoot = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        this.config = GitConfig.from(plugin.getConfig());
        this.messages = Messages.from(plugin.getConfig());
        this.progressListener = progressListener == null ? progress -> {
        } : progressListener;
    }

    public SourceFileSnapshot snapshotSourceFile() throws GitPushException {
        return snapshotSourceFile(resolveServerPath(config.sourceFile()));
    }

    public PushResult pushGeneratedZip() throws GitPushException {
        return pushGeneratedZip(null);
    }

    public PushResult pushGeneratedZip(SourceFileSnapshot previousSourceSnapshot) throws GitPushException {
        reportProgress(0.02D, text("git.progress.validating-config"));
        if (isBlank(config.remoteUrl())) {
            throw new GitPushException(text("git.error.remote-url-empty"));
        }

        reportProgress(0.08D, text("git.progress.checking-source"));
        Path sourceFile = resolveServerPath(config.sourceFile());
        SourceFileSnapshot sourceSnapshot = waitForReadySourceFile(sourceFile, previousSourceSnapshot);

        reportProgress(0.14D, text("git.progress.preparing-repository"));
        Path repositoryDirectory = resolveServerPath(config.repositoryDirectory());
        ensureRepository(repositoryDirectory);

        reportProgress(0.34D, text("git.progress.applying-author"));
        applyAuthorConfig(repositoryDirectory);

        reportProgress(0.42D, text("git.progress.copying-source"));
        Path repositoryFile = resolveInsideRepository(repositoryDirectory, config.repositoryFile());
        Path repositoryFileParent = repositoryFile.getParent();
        if (repositoryFileParent != null) {
            createDirectories(repositoryFileParent);
        }
        copySourceToRepository(sourceFile, repositoryFile, sourceSnapshot);

        reportProgress(0.52D, text("git.progress.staging-source"));
        String gitFilePath = toGitPath(repositoryDirectory.relativize(repositoryFile));
        runGitOrThrow(repositoryDirectory, "add", "--", gitFilePath);

        reportProgress(0.60D, text("git.progress.checking-changes"));
        CommandResult status = runGitOrThrow(repositoryDirectory, "status", "--porcelain", "--", gitFilePath);
        boolean committed = !status.output().isBlank();
        if (committed) {
            reportProgress(0.68D, text("git.progress.committing-source"));
            runGitOrThrow(repositoryDirectory, "commit", "-m", config.commitMessage(), "--", gitFilePath);
        } else {
            reportProgress(0.68D, text("git.progress.no-changes"));
        }

        reportProgress(0.76D, text("git.progress.pushing", config.branch()));
        runGitOrThrow(repositoryDirectory, new ProgressRange(0.76D, 0.98D, text("git.progress.git-push")), "push", "--progress", "-u", "origin", config.branch());
        reportProgress(1.0D, text("git.progress.finished"));
        return new PushResult(committed, config.branch(), repositoryDirectory.toString(), gitFilePath);
    }

    private void ensureRepository(Path repositoryDirectory) throws GitPushException {
        if (Files.isDirectory(repositoryDirectory.resolve(".git"))) {
            configureRemote(repositoryDirectory);
            synchronizeBranch(repositoryDirectory);
            return;
        }

        if (Files.exists(repositoryDirectory) && !isDirectoryEmpty(repositoryDirectory)) {
            throw new GitPushException(text("git.error.repository-not-empty", repositoryDirectory));
        }

        cloneRepository(repositoryDirectory);
        configureRemote(repositoryDirectory);
        synchronizeBranch(repositoryDirectory);
    }

    private void cloneRepository(Path repositoryDirectory) throws GitPushException {
        Path parent = repositoryDirectory.getParent();
        if (parent == null) {
            throw new GitPushException(text("git.error.repository-no-parent", repositoryDirectory));
        }

        reportProgress(0.18D, text("git.progress.cloning"));
        createDirectories(parent);
        String directoryName = repositoryDirectory.getFileName().toString();
        CommandResult clone = runGit(parent, List.of("clone", config.remoteUrl(), directoryName));
        if (clone.exitCode() == 0) {
            return;
        }

        reportProgress(0.24D, text("git.progress.initializing"));
        createDirectories(repositoryDirectory);
        CommandResult init = runGit(repositoryDirectory, List.of("init"));
        if (init.exitCode() != 0) {
            throw commandFailed(List.of("clone", config.remoteUrl(), directoryName), clone);
        }
    }

    private void configureRemote(Path repositoryDirectory) throws GitPushException {
        reportProgress(0.24D, text("git.progress.configuring-remote"));
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
        reportProgress(0.30D, text("git.progress.checkout-branch", config.branch()));
        CommandResult checkoutExisting = runGit(repositoryDirectory, List.of("checkout", config.branch()));
        if (checkoutExisting.exitCode() == 0) {
            return;
        }

        runGitOrThrow(repositoryDirectory, "checkout", "-B", config.branch());
    }

    private void synchronizeBranch(Path repositoryDirectory) throws GitPushException {
        fetchRemoteBranch(repositoryDirectory);
        boolean hadLocalBranch = localBranchExists(repositoryDirectory);
        checkoutBranch(repositoryDirectory);

        String remoteBranch = remoteBranchRef();
        if (remoteBranchExists(repositoryDirectory, remoteBranch)) {
            reportProgress(0.32D, text("git.progress.resetting-branch", remoteBranch));
            runGitOrThrow(repositoryDirectory, "reset", "--hard", remoteBranch);
            return;
        }

        if (hadLocalBranch && hasHead(repositoryDirectory)) {
            reportProgress(0.32D, text("git.progress.rebuilding-branch"));
            rebuildUnpublishedBranch(repositoryDirectory);
        }
    }

    private void fetchRemoteBranch(Path repositoryDirectory) throws GitPushException {
        reportProgress(0.28D, text("git.progress.fetching-branch", config.branch()));
        List<String> fetchArguments = List.of("fetch", "--prune", "origin",
                "+refs/heads/" + config.branch() + ":" + remoteBranchRef());
        CommandResult fetch = runGit(repositoryDirectory, fetchArguments);
        if (fetch.exitCode() == 0) {
            return;
        }

        CommandResult remoteHead = runGit(repositoryDirectory,
                List.of("ls-remote", "--exit-code", "--heads", "origin", config.branch()));
        if (remoteHead.exitCode() == 2) {
            return;
        }

        throw commandFailed(fetchArguments, fetch);
    }

    private boolean remoteBranchExists(Path repositoryDirectory, String remoteBranch) throws GitPushException {
        return runGit(repositoryDirectory, List.of("rev-parse", "--verify", "--quiet", remoteBranch)).exitCode() == 0;
    }

    private boolean localBranchExists(Path repositoryDirectory) throws GitPushException {
        return runGit(repositoryDirectory,
                List.of("show-ref", "--verify", "--quiet", "refs/heads/" + config.branch())).exitCode() == 0;
    }

    private boolean hasHead(Path repositoryDirectory) throws GitPushException {
        return runGit(repositoryDirectory, List.of("rev-parse", "--verify", "--quiet", "HEAD")).exitCode() == 0;
    }

    private void rebuildUnpublishedBranch(Path repositoryDirectory) throws GitPushException {
        String temporaryBranch = "nap-rebuild-" + System.nanoTime();
        runGitOrThrow(repositoryDirectory, "reset", "--hard");
        runGitOrThrow(repositoryDirectory, "checkout", "--orphan", temporaryBranch);
        runGitOrThrow(repositoryDirectory, "rm", "-r", "--cached", "--ignore-unmatch", ".");
        runGitOrThrow(repositoryDirectory, "branch", "-D", config.branch());
        runGitOrThrow(repositoryDirectory, "symbolic-ref", "HEAD", "refs/heads/" + config.branch());
    }

    private String remoteBranchRef() {
        return "refs/remotes/origin/" + config.branch();
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
            throw new GitPushException(text("git.error.config-path-empty"));
        }

        pathText = stripServerRootPrefix(pathText);

        try {
            Path path = Path.of(pathText);
            if (path.isAbsolute()) {
                return path.toAbsolutePath().normalize();
            }
            return serverRoot.resolve(path).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new GitPushException(text("git.error.invalid-config-path", configuredPath), exception);
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
            throw new GitPushException(text("git.error.repository-file-empty"));
        }

        Path resolved = repositoryDirectory.resolve(repositoryFile).normalize();
        if (!resolved.startsWith(repositoryDirectory)) {
            throw new GitPushException(text("git.error.repository-file-outside"));
        }
        return resolved;
    }

    private SourceFileSnapshot waitForReadySourceFile(Path sourceFile, SourceFileSnapshot previousSourceSnapshot) throws GitPushException {
        SourceFileSnapshot comparableSnapshot = previousSourceSnapshot;
        if (comparableSnapshot != null && !Objects.equals(comparableSnapshot.path(), sourceFile)) {
            comparableSnapshot = null;
        }

        if (comparableSnapshot != null) {
            reportProgress(0.10D, text("git.progress.waiting-refresh"));
            waitForSourceRefresh(sourceFile, comparableSnapshot);
        }

        reportProgress(0.12D, text("git.progress.waiting-writes"));
        return waitForCompleteSourceFile(sourceFile);
    }

    private void waitForSourceRefresh(Path sourceFile, SourceFileSnapshot previousSnapshot) throws GitPushException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.sourceRefreshTimeoutSeconds());
        while (true) {
            SourceFileSnapshot currentSnapshot = snapshotSourceFile(sourceFile);
            if (currentSnapshot.regularFile() && !currentSnapshot.hasSameFileState(previousSnapshot)) {
                return;
            }

            if (System.nanoTime() >= deadline) {
                throw new GitPushException(text("git.error.source-refresh-timeout", config.sourceRefreshTimeoutSeconds(), sourceFile));
            }

            sleepUntilNextSourceCheck();
        }
    }

    private SourceFileSnapshot waitForStableSourceFile(Path sourceFile) throws GitPushException {
        long stableNanos = TimeUnit.SECONDS.toNanos(config.sourceStableSeconds());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.sourceRefreshTimeoutSeconds());
        SourceFileSnapshot lastSnapshot = null;
        long stableSince = -1L;

        while (true) {
            SourceFileSnapshot currentSnapshot = snapshotSourceFile(sourceFile);
            if (currentSnapshot.regularFile()) {
                if (lastSnapshot != null && currentSnapshot.hasSameFileState(lastSnapshot)) {
                    if (stableSince < 0L) {
                        stableSince = System.nanoTime();
                    }
                    if (System.nanoTime() - stableSince >= stableNanos) {
                        return currentSnapshot;
                    }
                } else {
                    lastSnapshot = currentSnapshot;
                    stableSince = System.nanoTime();
                }
            }

            if (System.nanoTime() >= deadline) {
                if (lastSnapshot == null) {
                    throw new GitPushException(text("git.error.source-missing", sourceFile));
                }
                throw new GitPushException(text("git.error.source-stability-timeout", config.sourceRefreshTimeoutSeconds(), sourceFile));
            }

            sleepUntilNextSourceCheck();
        }
    }

    private SourceFileSnapshot waitForCompleteSourceFile(Path sourceFile) throws GitPushException {
        while (true) {
            SourceFileSnapshot stableSnapshot = waitForStableSourceFile(sourceFile);
            waitForReadableZip(sourceFile);

            SourceFileSnapshot checkedSnapshot = snapshotSourceFile(sourceFile);
            if (checkedSnapshot.hasSameFileState(stableSnapshot)) {
                return checkedSnapshot;
            }

            reportProgress(0.13D, text("git.progress.changed-checking"));
        }
    }

    private void waitForReadableZip(Path sourceFile) throws GitPushException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.sourceRefreshTimeoutSeconds());
        while (true) {
            if (canOpenZip(sourceFile)) {
                return;
            }

            if (System.nanoTime() >= deadline) {
                throw new GitPushException(text("git.error.source-unreadable-zip", sourceFile));
            }

            sleepUntilNextSourceCheck();
        }
    }

    private boolean canOpenZip(Path sourceFile) throws GitPushException {
        try (ZipFile ignored = new ZipFile(sourceFile.toFile())) {
            return true;
        } catch (ZipException | FileNotFoundException | NoSuchFileException | NotDirectoryException exception) {
            return false;
        } catch (IOException exception) {
            throw new GitPushException(text("git.error.source-read-failed", sourceFile), exception);
        }
    }

    private void copySourceToRepository(Path sourceFile, Path repositoryFile, SourceFileSnapshot expectedSnapshot) throws GitPushException {
        SourceFileSnapshot stableSnapshot = expectedSnapshot;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Files.copy(sourceFile, repositoryFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                throw new GitPushException(text("git.error.copy-failed", sourceFile, repositoryFile), exception);
            }

            SourceFileSnapshot copiedSnapshot = snapshotSourceFile(sourceFile);
            if (copiedSnapshot.hasSameFileState(stableSnapshot) && filesMatch(sourceFile, repositoryFile)) {
                return;
            }

            reportProgress(0.44D, text("git.progress.changed-copying"));
            stableSnapshot = waitForCompleteSourceFile(sourceFile);
        }

        throw new GitPushException(text("git.error.source-kept-changing", sourceFile));
    }

    private boolean filesMatch(Path sourceFile, Path repositoryFile) throws GitPushException {
        try {
            return Files.mismatch(sourceFile, repositoryFile) == -1L;
        } catch (IOException exception) {
            throw new GitPushException(text("git.error.verify-copy-failed"), exception);
        }
    }

    private SourceFileSnapshot snapshotSourceFile(Path sourceFile) throws GitPushException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(sourceFile, BasicFileAttributes.class);
            return new SourceFileSnapshot(
                    sourceFile,
                    attributes.isRegularFile(),
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    Objects.toString(attributes.fileKey(), "")
            );
        } catch (NoSuchFileException | NotDirectoryException exception) {
            return SourceFileSnapshot.missing(sourceFile);
        } catch (IOException exception) {
            throw new GitPushException(text("git.error.inspect-source-failed", sourceFile), exception);
        }
    }

    private void sleepUntilNextSourceCheck() throws GitPushException {
        try {
            Thread.sleep(config.sourcePollIntervalMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitPushException(text("git.error.wait-interrupted"), exception);
        }
    }

    private boolean isDirectoryEmpty(Path directory) throws GitPushException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (IOException exception) {
            throw new GitPushException(text("git.error.inspect-directory-failed", directory), exception);
        }
    }

    private void createDirectories(Path directory) throws GitPushException {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new GitPushException(text("git.error.create-directory-failed", directory), exception);
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
        addGitProxyConfig(command);
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        applyProxyEnvironment(builder);

        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new GitPushException(text("git.error.start-failed", config.executable()), exception);
        }

        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process.getInputStream(), progressRange));
        boolean finished;
        try {
            finished = process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GitPushException(text("git.error.interrupted", describeGitCommand(arguments)), exception);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new GitPushException(text("git.error.timed-out", config.timeoutSeconds(), describeGitCommand(arguments)));
        }

        String output = getOutput(outputFuture);
        return new CommandResult(process.exitValue(), output == null ? "" : output.trim());
    }

    private void addGitProxyConfig(List<String> command) {
        String proxy = firstConfigured(config.httpsProxy(), config.httpProxy());
        addGitConfig(command, "http.proxy", proxy);
        addGitConfig(command, "http.noProxy", config.noProxy());
    }

    private void addGitConfig(List<String> command, String name, String value) {
        if (isBlank(value)) {
            return;
        }

        command.add("-c");
        command.add(name + "=" + value);
    }

    private void applyProxyEnvironment(ProcessBuilder builder) {
        if (!isBlank(config.httpProxy())) {
            builder.environment().put("HTTP_PROXY", config.httpProxy());
            builder.environment().put("http_proxy", config.httpProxy());
        }
        if (!isBlank(config.httpsProxy())) {
            builder.environment().put("HTTPS_PROXY", config.httpsProxy());
            builder.environment().put("https_proxy", config.httpsProxy());
        }
        if (!isBlank(config.noProxy())) {
            builder.environment().put("NO_PROXY", config.noProxy());
            builder.environment().put("no_proxy", config.noProxy());
        }
    }

    private String firstConfigured(String first, String second) {
        return isBlank(first) ? second : first;
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
            return text("git.error.output-read-failed-inline", exception.getMessage());
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
            throw new GitPushException(text("git.error.output-interrupted"), exception);
        } catch (ExecutionException exception) {
            throw new GitPushException(text("git.error.output-failed"), exception);
        } catch (TimeoutException exception) {
            throw new GitPushException(text("git.error.output-timed-out"), exception);
        }
    }

    private GitPushException commandFailed(List<String> arguments, CommandResult result) {
        String output = result.output().isBlank() ? text("git.error.no-output") : sanitizeOutput(result.output());
        return new GitPushException(text("git.error.command-failed", describeGitCommand(arguments), result.exitCode(), output));
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
        if (argument.startsWith("http.proxy=")) {
            return "http.proxy=<proxy>";
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
        sanitized = redactConfiguredProxy(sanitized, config.httpProxy());
        sanitized = redactConfiguredProxy(sanitized, config.httpsProxy());
        return sanitized;
    }

    private String redactConfiguredProxy(String output, String proxy) {
        if (isBlank(proxy)) {
            return output;
        }

        String sanitized = output.replace(proxy, "<proxy>");
        String redacted = redactRemoteUserInfo(proxy);
        return sanitized.replace(redacted, "<proxy>");
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

    private String text(String key, Object... arguments) {
        return messages.text(key, arguments);
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

    public record SourceFileSnapshot(
            Path path,
            boolean regularFile,
            long size,
            FileTime lastModifiedTime,
            String fileKey
    ) {
        private static SourceFileSnapshot missing(Path path) {
            return new SourceFileSnapshot(path, false, -1L, FileTime.fromMillis(0L), "");
        }

        private boolean hasSameFileState(SourceFileSnapshot other) {
            return other != null
                    && regularFile == other.regularFile
                    && size == other.size
                    && Objects.equals(lastModifiedTime, other.lastModifiedTime)
                    && Objects.equals(fileKey, other.fileKey);
        }
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
            String httpProxy,
            String httpsProxy,
            String noProxy,
            String authorName,
            String authorEmail,
            long sourceRefreshTimeoutSeconds,
            long sourceStableSeconds,
            long sourcePollIntervalMillis
    ) {
        private static GitConfig from(FileConfiguration configuration) {
            return new GitConfig(
                    getString(configuration, "git.executable", "git"),
                    getString(configuration, "git.remote-url", ""),
                    getString(configuration, "git.branch", "main"),
                    getString(configuration, "git.source-file", "plugins/ItemsAdder/output/generated.zip"),
                    getString(configuration, "git.repository-directory", "plugins/NekoAutoPack/repository"),
                    getString(configuration, "git.repository-file", "generated.zip"),
                    getString(configuration, "git.commit-message", "Update ItemsAdder generated.zip"),
                    Math.max(5L, configuration.getLong("git.timeout-seconds", 120L)),
                    getString(configuration, "git.proxy.http", ""),
                    getString(configuration, "git.proxy.https", ""),
                    getString(configuration, "git.proxy.no-proxy", ""),
                    getString(configuration, "git.author.name", ""),
                    getString(configuration, "git.author.email", ""),
                    Math.max(1L, configuration.getLong("start.source-refresh-timeout-seconds", 120L)),
                    Math.max(0L, configuration.getLong("start.source-stable-seconds", 2L)),
                    Math.max(100L, configuration.getLong("start.source-poll-interval-millis", 500L))
            );
        }

        private static String getString(FileConfiguration configuration, String path, String defaultValue) {
            return Objects.toString(configuration.getString(path), defaultValue).trim();
        }
    }
}

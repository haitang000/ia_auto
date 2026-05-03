# IAAuto

IAAuto is a Paper plugin for Minecraft 1.20.6 and newer. It adds `/iaauto start`, which runs ItemsAdder's `/iazip`, then runs `/iaauto push`. The push command copies `plugins/ItemsAdder/output/generated.zip` into a local git repository and pushes it to a configured GitHub remote.

## Build

Use Java 21.

```bash
gradle clean build
```

or:

```bash
mvn clean package
```

The plugin jar will be under `build/libs/` for Gradle or `target/` for Maven.

## Server Setup

1. Put the built jar into the server `plugins` folder.
2. Start the server once so `plugins/IAAuto/config.yml` is created.
3. Set `git.remote-url` to your GitHub repository URL.
4. Make sure the server process can run local `git` and already has GitHub authentication configured, such as SSH keys or Git Credential Manager.
5. Run `/iaauto start`.

Use `/iaauto help` or `/iaauto` in game or from the console to list the commands available to the sender.

Default source file:

```yaml
git:
  source-file: "plugins/ItemsAdder/output/generated.zip"
```

Default repository location:

```yaml
git:
  repository-directory: "plugins/IAAuto/repository"
  repository-file: "generated.zip"
```

IAAuto treats `repository-directory` as a managed checkout. Before copying the latest `generated.zip`, it fetches the configured branch and resets local unpublished history to the remote branch. If a previous GitHub push failed because the zip was too large, the rejected local commit will not be included again after you generate a smaller zip and rerun `/iaauto push`.

`/iaauto start` runs `/iazip` from the console, then waits before pushing. Before the git copy starts, IAAuto also waits until `generated.zip` has refreshed compared with the file state from before `/iazip`, then waits for the file to become stable so an old or half-written pack is not uploaded. Increase these values if your pack needs more time to finish:

```yaml
start:
  pack-command: "iazip"
  push-delay-seconds: 10
  source-refresh-timeout-seconds: 120
  source-stable-seconds: 2
  source-poll-interval-millis: 500
```

Permissions:

- `iaauto.help` for `/iaauto help`
- `iaauto.start` for `/iaauto start`
- `iaauto.push` for `/iaauto push`
- `iaauto.reload` for `/iaauto reload`

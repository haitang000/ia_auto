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

`/iaauto start` runs `/iazip` from the console, then waits before pushing. Increase the delay if your pack needs more time to finish:

```yaml
start:
  pack-command: "iazip"
  push-delay-seconds: 10
```

Permissions:

- `iaauto.start` for `/iaauto start`
- `iaauto.push` for `/iaauto push`
- `iaauto.reload` for `/iaauto reload`

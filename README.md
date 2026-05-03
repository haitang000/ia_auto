# AIAuto

AIAuto is a Paper plugin for Minecraft 1.20.6 and newer. It adds `/aiauto push`, which copies `plugins/ItemsAdder/output/generated.zip` into a local git repository and pushes it to a configured GitHub remote.

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
2. Start the server once so `plugins/AIAuto/config.yml` is created.
3. Set `git.remote-url` to your GitHub repository URL.
4. Make sure the server process can run local `git` and already has GitHub authentication configured, such as SSH keys or Git Credential Manager.
5. Run `/aiauto push`.

Default source file:

```yaml
git:
  source-file: "plugins/ItemsAdder/output/generated.zip"
```

Default repository location:

```yaml
git:
  repository-directory: "plugins/AIAuto/repository"
  repository-file: "generated.zip"
```

Permissions:

- `aiauto.push` for `/aiauto push`
- `aiauto.reload` for `/aiauto reload`

# NekoAutoPack

> [!WARNING]
> This plugin requires ItemsAdder as a dependency.

NekoAutoPack is a Paper plugin for Minecraft 1.20.6 and newer. It adds `/nap start`, which runs ItemsAdder's `/iazip`, then runs `/nap push`. The push command copies `plugins/ItemsAdder/output/generated.zip` into a local git repository and pushes it to a configured GitHub remote.

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
2. Start the server once so `plugins/NekoAutoPack/config.yml` is created.
3. Set `git.remote-url` to your GitHub repository URL.
4. Make sure the server process can run local `git` and already has GitHub authentication configured, such as SSH keys or Git Credential Manager.
5. Run `/nap start`.

Use `/nap help` or `/nap` in game or from the console to list the commands available to the sender.

You can also configure git settings from the console or in game:

```text
/nap git set remote-url https://github.com/owner/repository.git
/nap git set branch main
/nap git set proxy.https http://127.0.0.1:7890
/nap git clear proxy.https
/nap git set author.name NekoAutoPack
/nap git set author.email bot@example.com
/nap git get remote-url
/nap git clear author.email
```

Supported git settings are `executable`, `remote-url`, `branch`, `source-file`, `repository-directory`, `repository-file`, `commit-message`, `timeout-seconds`, `proxy.http`, `proxy.https`, `proxy.no-proxy`, `author.name`, and `author.email`.

Language defaults to English. Use `en` or `zh_cn`:

```yaml
language: "en"
```

Default source file:

```yaml
git:
  source-file: "plugins/ItemsAdder/output/generated.zip"
```

Default repository location:

```yaml
git:
  repository-directory: "plugins/NekoAutoPack/repository"
  repository-file: "generated.zip"
```

NekoAutoPack treats `repository-directory` as a managed checkout. Before copying the latest `generated.zip`, it fetches the configured branch and resets local unpublished history to the remote branch. If a previous GitHub push failed because the zip was too large, the rejected local commit will not be included again after you generate a smaller zip and rerun `/nap push`.

`/nap start` runs `/iazip` from the console, then waits before pushing. Before the git copy starts, NekoAutoPack also waits until `generated.zip` has refreshed compared with the file state from before `/iazip`, then waits for the file to become stable so an old or half-written pack is not uploaded. Increase these values if your pack needs more time to finish:

```yaml
start:
  pack-command: "iazip"
  push-delay-seconds: 10
  source-refresh-timeout-seconds: 120
  source-stable-seconds: 2
  source-poll-interval-millis: 500
```

Permissions:

- `nap.help` for `/nap help`
- `nap.start` for `/nap start`
- `nap.push` for `/nap push`
- `nap.git` for `/nap git`
- `nap.reload` for `/nap reload`

---

# NekoAutoPack 中文版

> [!WARNING]
> 本插件需要 ItemsAdder 插件作为前置。

NekoAutoPack 是一个适用于 Minecraft 1.20.6+ 的 Paper 插件。`/nap start` 会先执行 ItemsAdder 的 `/iazip`，再通过 `/nap push` 将 `plugins/ItemsAdder/output/generated.zip` 复制到本地 git 仓库并推送到 GitHub。

## 构建

需要 Java 21。

```bash
gradle clean build
```

或者：

```bash
mvn clean package
```

插件 jar 会生成在 `build/libs/` 或 `target/`。

## 服务器设置

1. 将构建好的 jar 放入服务器的 `plugins` 文件夹。
2. 启动一次服务器，生成 `plugins/NekoAutoPack/config.yml`。
3. 设置 `git.remote-url` 为你的 GitHub 仓库 URL。
4. 确保服务器进程可以运行 `git`，并已配置 GitHub 认证，例如 SSH 密钥或 Git Credential Manager。
5. 运行 `/nap start`。

使用 `/nap help` 或 `/nap` 查看可用命令。

插件消息默认使用英语。将 `language` 设置为 `zh_cn` 可以切换为中文：

```yaml
language: "zh_cn"
```

默认源文件：

```yaml
git:
  source-file: "plugins/ItemsAdder/output/generated.zip"
```

默认仓库位置：

```yaml
git:
  repository-directory: "plugins/NekoAutoPack/repository"
  repository-file: "generated.zip"
```

`repository-directory` 会被视为插件管理的 git 检出目录。每次复制前，插件会拉取配置分支，并将本地未发布历史重置到远程分支，避免上一次失败的本地提交被重复推送。

`/nap start` 会从控制台运行 `/iazip`，等待 `generated.zip` 刷新并稳定后再推送。资源包生成较慢时，可以调大这些配置：

```yaml
start:
  pack-command: "iazip"
  push-delay-seconds: 10
  source-refresh-timeout-seconds: 120
  source-stable-seconds: 2
  source-poll-interval-millis: 500
```

权限：

- `nap.help` 用于 `/nap help`
- `nap.start` 用于 `/nap start`
- `nap.push` 用于 `/nap push`
- `nap.reload` 用于 `/nap reload`

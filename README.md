# ZHM world backup helper —— Paper 26.2 存档备份插件

> 插件显示名：**ZHM world backup helper**　当前版本：**1.0.1**　主命令：**`/worldbackup`**
>
> 说明：Paper 不允许插件 `name` 字段包含空格（会报 `Restricted name, cannot use 0x20`），
> 因此 `plugin.yml` 中 `name: ZHMWorldBackupHelper`（数据目录为 `plugins/ZHMWorldBackupHelper/`），
> 并通过 `prefix: ZHM world backup helper` 让服务器日志显示完整名称。

自动把服务器存档（`world`）用 **7z** 压缩，支持上传到 **WebDAV 网盘**（坚果云 / Nextcloud / Alist / 群晖 等），
上传成功后可按配置删除本地文件，并自动按保留数量清理旧备份。
备份进度同时显示在 **BOSS 血条栏** 与 **服务器后台**。

> 已在 **Paper 26.2 (build 130) + Java 25** 上完成端到端实测（见文末「验证结果」）。

## 下载

- **直接用现成 jar**：到本仓库的 [Releases](../../releases) 页面下载 `ZHM-world-backup-helper-1.0.1.jar`；
- **自己构建**：见下方[从源码构建](#从源码构建)（需要 JDK 25，三种方式任选）。

打包好的 jar 已内置 7z 压缩引擎，服务器上**无需安装 7-Zip 也能直接使用**。

---

## 目录

- [功能特性](#功能特性)
- [环境要求](#环境要求)
- [安装](#安装)
- [快速开始](#快速开始)
- [配置文件详解](#配置文件详解)
- [WebDAV 网盘配置示例](#webdav-网盘配置示例)
- [命令与权限](#命令与权限)
- [进度显示](#进度显示)
- [备份机制与数据安全](#备份机制与数据安全)
- [压缩引擎对比](#压缩引擎对比)
- [保留策略与自动清理](#保留策略与自动清理)
- [如何恢复存档](#如何恢复存档)
- [从源码构建](#从源码构建)
- [常见问题](#常见问题)
- [项目结构](#项目结构)
- [验证结果](#验证结果)

---

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 7z 压缩 | 优先调用系统 7-Zip（固实压缩，体积最小）；未安装时自动使用**插件内置的 7z(LZMA2) 引擎**，开箱即用 |
| 多目录备份 | 默认备份 `world`；Paper 26.1+ 的下界/末地已合并进 `world/dimensions/`，无需单独备份 |
| WebDAV 上传 | 纯 JDK 实现（HttpClient），支持 Basic 认证、中文路径、HTTPS、失败重试 |
| 上传后删除本地 | `upload.delete-local-after-upload: true`，仅在**上传成功后**才删除 |
| 自动清理旧备份 | 按 `retention.keep-count` 保留最新 N 个，可按天数清理，支持远端网盘同步清理 |
| 双通道进度 | BOSS 血条栏 + 后台日志，均有百分比与已处理/总大小 |
| 安全备份 | 备份前 `save-off` + `save-all flush`，结束后 `save-on`，避免存档不一致 |
| 完整性保护 | Windows 下自动加 `-ssw` 读取被占用的区块文件；7-Zip 报「有文件被跳过」时默认判定备份失败，避免上传残缺备份 |
| 定时任务 | 支持固定间隔（分钟）与每日固定时刻（如 `04:00`）两种触发方式 |
| 命令管理 | `/worldbackup backupnow\|status\|list\|clean\|reload\|cancel`，带 Tab 补全 |
| 可取消 | 压缩中取消会终止 7z 进程并删除未完成的压缩包 |

---

## 环境要求

- **服务端**：Paper 26.2（`api-version: '26.2'`，实测 build 130）
- **Java**：**Java 25**（Paper 26.2 的 paper-api 为 class 版本 69，必须 Java 25）
- **可选**：系统安装 [7-Zip](https://www.7-zip.org/)（Windows）/ `p7zip`（Linux）以获得最小体积

---

## 安装

1. 把 `dist/ZHM-world-backup-helper-1.0.1.jar` 放进服务器的 `plugins/` 目录；
2. 启动服务器，插件会在 `plugins/ZHMWorldBackupHelper/config.yml` 生成默认配置；
3. 修改配置后执行 `/worldbackup reload`（压缩引擎相关配置建议重启服务器）；
4. 控制台或游戏内执行 `/worldbackup backupnow` 立即手动备份一次。

---

## 快速开始

最小可用配置（只备份 + 本地保留 5 份）：

```yaml
backup:
  worlds: [world]
  output-dir: backups
retention:
  enabled: true
  keep-count: 5
```

加上「压缩后上传网盘并删除本地文件」：

```yaml
upload:
  enabled: true
  url: 'https://dav.jianguoyun.com/dav/我的坚果云/minecraft-backup/'
  username: '你的邮箱'
  password: '应用密码'
  delete-local-after-upload: true
  keep-remote-count: 10
```

---

## 配置文件详解

### `backup` 备份基础

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `worlds` | `[world]` | 要备份的文件夹，相对服务器根目录或绝对路径。Paper 26.1+ 只需 `world`（维度已合并进 `world/dimensions/`） |
| `auto-detect-extra-worlds` | `true` | 自动追加仍单独存在的 `world_nether` / `world_the_end`（旧版本服务端需要，26.1+ 检测不到会自动忽略） |
| `skip-missing` | `true` | 配置的文件夹不存在时跳过并警告；`false` 则整个任务失败 |
| `output-dir` | `backups` | 本地备份输出目录 |
| `file-name-format` | `%WORLD%-%TIMESTAMP%` | 文件名格式，支持 `%WORLD%`、`%TIMESTAMP%`；未写 `%TIMESTAMP%` 时自动追加时间戳 |
| `timestamp-format` | `yyyy-MM-dd_HH-mm-ss` | 时间戳格式 |
| `threads` | `0` | 7z 压缩线程数，`0` = 使用全部核心 |
| `safe-mode` | `true` | 备份前 `save-off` + `save-all flush`，结束后 `save-on`。**强烈建议保持开启** |
| `flush-wait-millis` | `1500` | 刷盘后额外等待时间，确保数据落地 |
| `exclude` | `session.lock` 等 | 排除的文件/文件夹，支持 `*`、`?`，同时匹配文件名与相对路径 |
| `timeout-minutes` | `120` | 单个任务超时，`0` = 不限制 |
| `announce` | `true` | 备份开始/结束全服公告 |

### `compression` 压缩

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `mode` | `AUTO` | `AUTO` / `SEVEN_ZIP_CLI` / `SEVEN_ZIP_EMBEDDED` / `ZIP` |
| `seven-zip-path` | 空 | 7z 可执行文件路径；留空则自动探测 PATH 与常见安装目录 |
| `level` | `9` | 压缩等级 0-9 |
| `solid` | `true` | 固实压缩（仅命令行 7z 支持），体积更小 |
| `compress-locked-files` | `true` | 传给 7-Zip `-ssw`，允许压缩被运行中的服务端占用的文件。**Windows 上必须保持开启**，否则区块文件会被跳过 |
| `fail-on-warning` | `true` | 7-Zip 返回警告（通常意味着有文件被跳过）时判定备份失败并删除压缩包，避免上传残缺备份 |
| `extra-args` | `[]` | 追加给 7z 的参数，例如 `-m0=lzma2:d=64m` 调整字典大小 |

### `upload` 上传

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 是否启用 WebDAV 上传 |
| `url` | 空 | WebDAV **目录**地址，建议以 `/` 结尾，支持中文（自动编码） |
| `username` / `password` | 空 | Basic 认证账号；坚果云请使用「应用密码」 |
| `delete-local-after-upload` | `true` | 上传成功后删除本地压缩包（上传失败不会删除） |
| `allow-insecure-tls` | `false` | 允许自签名证书（私有网盘） |
| `connect-timeout-seconds` | `30` | 连接超时 |
| `request-timeout-minutes` | `120` | 单次上传请求超时（大存档建议调大） |
| `retry-count` / `retry-delay-seconds` | `3` / `10` | 失败重试次数与间隔（认证失败不重试） |
| `keep-remote-count` | `0` | 上传后在网盘保留的备份数量，`0` = 不清理远端；需要网盘支持 `PROPFIND` / `DELETE` |

### `retention` 本地保留策略

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 启用本地自动清理 |
| `keep-count` | `5` | 保留最新的 N 个备份，超出的自动删除；`0` = 不按数量清理 |
| `max-age-days` | `0` | 同时删除超过 N 天的备份，`0` = 不按时间清理 |
| `file-pattern` | `*.7z` | 只清理匹配该通配符的文件（若改用 ZIP 引擎，请改成 `*.zip`） |
| `run-on-startup` | `true` | 服务器启动时先清理一次 |

### `auto-backup` 定时任务

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 是否启用定时备份 |
| `interval-minutes` | `360` | 间隔备份（分钟），`0` = 不使用间隔模式 |
| `first-delay-minutes` | `5` | 开服后首次备份延迟 |
| `daily-times` | `[]` | 每日固定时刻，例如 `['04:00', '16:00']` |
| `skip-when-empty` | `false` | 无玩家在线时跳过 |

### `progress` 进度显示

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `boss-bar` | `true` | 在 BOSS 血条栏显示进度 |
| `boss-bar-title` | 见默认配置 | 支持 `%STAGE%`、`%PERCENT%`、`%DETAIL%` 与 `&` 颜色代码 |
| `boss-bar-color` / `boss-bar-style` | `BLUE` / `SEGMENTED_20` | 血条颜色与样式 |
| `console` | `true` | 后台输出进度 |
| `console-interval-seconds` | `5` | 后台输出间隔 |

### 其它

- `after-backup-commands`：备份成功后以控制台身份执行命令，支持 `%FILE%`、`%SIZE%`、`%DURATION%`、`%WORLD%`、`%TRIGGER%`。
- `messages`：所有提示文本，支持 `&` 颜色代码或 MiniMessage 标签（如 `<aqua>`）。

---

## WebDAV 网盘配置示例

```yaml
# 坚果云（推荐使用「账户信息 → 安全选项 → 添加应用密码」生成的密码）
upload:
  enabled: true
  url: 'https://dav.jianguoyun.com/dav/我的坚果云/minecraft-backup/'
  username: '你的坚果云邮箱'
  password: '应用密码'

# Nextcloud / ownCloud
upload:
  enabled: true
  url: 'https://cloud.example.com/remote.php/dav/files/用户名/backups/'
  username: '用户名'
  password: '密码或应用专用密码'

# Alist（自建）
upload:
  enabled: true
  url: 'http://127.0.0.1:5244/dav/backups/'
  username: 'admin'
  password: '密码'

# 群晖 WebDAV Server
upload:
  enabled: true
  url: 'https://nas.example.com:5006/minecraft-backup/'
  username: '用户名'
  password: '密码'
```

> 上传时会自动逐级 `MKCOL` 创建目录；目录已存在返回 405/403 也会被正确忽略。

---

## 命令与权限

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/worldbackup backupnow [备注]` | `worldbackup.admin` | **立即手动备份**（也接受 `now`、`start` 作为简写） |
| `/worldbackup status` | `worldbackup.use` | 查看当前进度 / 上次结果 |
| `/worldbackup list` | `worldbackup.use` | 列出本地备份（序号、文件名、大小、时间） |
| `/worldbackup clean` | `worldbackup.admin` | 立即执行保留策略清理 |
| `/worldbackup cancel` | `worldbackup.admin` | 取消当前备份（会删除未完成的压缩包） |
| `/worldbackup reload` | `worldbackup.admin` | 重载 `config.yml` |

权限默认给 OP，可用权限插件调整。控制台拥有全部权限。

---

## 进度显示

- **BOSS 血条栏**：备份开始为所有在线玩家显示血条，标题形如
  `存档备份 | 压缩 45% 120 MB / 206 MB`，随阶段（准备 → 扫描 → 压缩 → 上传 → 清理 → 完成）推进；
  备份过程中加入的玩家也会自动看到。
- **服务器后台**：按 `console-interval-seconds` 输出，例如
  `[ZHM world backup helper] [压缩] 45% - 94.88 MB / 206.26 MB`，日志同时包含扫描结果、压缩率、上传地址、清理明细。

---

## 备份机制与数据安全

一次备份的完整流程：

1. **准备**：解析 `worlds`，广播开始提示；
2. **安全模式**：控制台执行 `save-off` → `save-all flush` → 等待 `flush-wait-millis`；
3. **扫描**：统计文件数量与总大小，应用 `exclude` 规则；
4. **压缩**：调用 7z（或内置引擎）生成 `.7z`；
5. **上传**：WebDAV `MKCOL` + `PUT`，带进度与重试；
6. **清理**：删除本地超量备份，可选清理网盘超量备份；
7. **收尾**：执行 `save-on`、`after-backup-commands`、移除血条、输出结果。

安全设计要点：

- **`-ssw`**：Windows 上运行中的服务端会占用 `region/*.mca`，不加该参数时 7-Zip 会**静默跳过**这些区块文件。
  本插件默认加上 `-ssw`，并在实测中确认 12 个被占用的区块文件全部被正确打包。
- **`fail-on-warning`**：若 7-Zip 仍报告文件被跳过，则判定备份失败、删除残缺压缩包、不执行上传，
  避免用「看起来成功」的残缺备份覆盖网盘上的完整备份。
- **上传后才删除本地**：`delete-local-after-upload` 只在收到 2xx 响应后执行；失败会自动重试并保留本地文件。

---

## 压缩引擎对比

| 引擎 | 是否需要安装 | 固实压缩 | 速度 | 体积 | 适用场景 |
| --- | --- | --- | --- | --- | --- |
| `SEVEN_ZIP_CLI`（AUTO 优先） | 需要 7-Zip | 支持 | 快（多线程） | 最小 | 推荐，尤其是大存档 |
| `SEVEN_ZIP_EMBEDDED` | 不需要 | 不支持 | 单线程，较慢 | 略大 2-5% | 无法安装 7-Zip 的环境 |
| `ZIP` | 不需要 | 不支持 | 快 | 最大 | 仅兜底 |

实测同一份 9.26 MB 存档：

- 7z 命令行：**3.17 MB，0.24 秒**
- 内置 7z(LZMA2)：**3.24 MB，5 秒**

> 插件 jar 已内置 `commons-compress` + `xz`，无需额外下载依赖。

---

## 保留策略与自动清理

- 每次备份成功后自动执行一次本地清理（也可 `/worldbackup clean` 手动触发）；
- 排序规则为「最后修改时间倒序」，保留最新的 `keep-count` 个；
- 超过 `max-age-days` 的备份同样会被删除；
- 设置了 `upload.keep-remote-count` 时，还会通过 `PROPFIND` 列出网盘文件并删除超量的旧备份；
- 实测：`keep-count: 3` 连续备份 4 次后，本地恰好保留 3 个，最旧的一个被删除并输出原因。

---

## 如何恢复存档

1. 停止服务器；
2. 用 7-Zip（或任意支持 7z 的工具）解压备份文件，压缩包内顶层即 `world/` 目录；
3. 备份现有 `world` 目录（重命名即可），把解压出的 `world` 放到服务器根目录；
4. 启动服务器验证。

命令行示例（Windows）：

```powershell
& 'C:\Program Files\7-Zip\7z.exe' x .\backups\world-2026-10-06_10-30-00.7z -o.\restore -y
```

---

## 从源码构建

需要 **JDK 25**，三种方式任选：

```powershell
# 方式一：仅用 JDK（无需 Maven / Gradle），会自动下载依赖到 libs/
pwsh -File build.ps1                      # 产物：dist/ZHM-world-backup-helper-1.0.1.jar

# 方式二：Maven
mvn -q clean package                      # 产物：target/ZHM-world-backup-helper-1.0.1.jar

# 方式三：Gradle
gradle clean build                        # 产物：build/libs/ZHM-world-backup-helper-1.0.1.jar
```

> `build.ps1` 在 Windows PowerShell 5.1 与 PowerShell 7 下均可运行（文件为 UTF-8 with BOM）。

---

## 常见问题

**Q：后台提示「未检测到系统 7-Zip，改用插件内置 7z(LZMA2) 引擎」？**
A：说明没找到 7-Zip。可安装 7-Zip（Windows 默认路径 `C:\Program Files\7-Zip\7z.exe` 会被自动识别），
或在 `compression.seven-zip-path` 填写完整路径；也可以直接使用内置引擎。

**Q：备份失败并提示「7-Zip 未能完整备份（有文件被跳过）」？**
A：说明有文件被其他程序独占（常见于未开启安全模式、或被其他备份软件锁定）。
请确认 `backup.safe-mode: true`、`compression.compress-locked-files: true`；
若确认可以忽略，可将 `compression.fail-on-warning` 设为 `false`。

**Q：上传返回 401 / 403？**
A：坚果云必须使用「应用密码」而非登录密码；也请确认 `username` 为完整邮箱。

**Q：BOSS 血条栏没有显示进度？**
A：确认 `progress.boss-bar: true`；血条只在备份进行期间显示，若备份在 1 秒内完成可能一闪而过
（可调大 `progress.console-interval-seconds` 观察后台日志）。玩家需要在备份开始后才加入时也会自动补显示。

**Q：`/worldbackup clean` 在 RCON 里看不到回执？**
A：清理是异步执行的，RCON 连接在命令返回后即关闭，因此回执可能只出现在服务器后台日志中；
游戏内或控制台执行可正常收到提示。

**Q：插件名为什么是 `ZHMWorldBackupHelper` 而不是带空格的？**
A：Paper 会拒绝含空格的插件名（`Restricted name, cannot use 0x20 (space character) in a plugin name`）。
因此 `name` 使用无空格的 `ZHMWorldBackupHelper`（同时决定数据目录名），
再用 `prefix: ZHM world backup helper` 让日志显示完整名称。
若想改成别的显示名，修改 `plugin.yml` 的 `prefix` 后重新打包即可；改 `name` 则需一并迁移数据目录。

**Q：想改成备份多个世界 / 自定义世界名？**
A：在 `backup.worlds` 中列出即可，例如 `[world, survival, skyblock]`；压缩包名称默认取第一个世界的名字。

---

## 项目结构

```
paper-backup-plugin/
├── build.ps1                     # 纯 JDK 构建脚本（自动拉取依赖）
├── build.gradle.kts / pom.xml    # Gradle / Maven 构建文件
├── dist/ZHM-world-backup-helper-1.0.1.jar    # 构建产物（可直接放入 plugins/）
├── src/main/java/com/dsh/worldbackup/
│   ├── WorldBackupPlugin.java    # 插件入口
│   ├── backup/                   # 任务编排、服务、保留策略、定时任务
│   ├── command/                  # /worldbackup 命令与 Tab 补全
│   ├── compress/                 # 三种压缩引擎、源扫描、引擎选择
│   ├── config/                   # 配置映射
│   ├── listener/                 # 玩家加入时补显示血条
│   ├── progress/                 # BOSS 血条 + 后台进度
│   ├── upload/                   # WebDAV 上传（含带进度的请求体）
│   └── util/                     # 文本、文件工具
├── src/main/resources/           # plugin.yml / config.yml
├── tools/                        # 测试工具（Mock WebDAV、RCON、归档校验）
└── testserver/                   # 实测用 Paper 26.2 服务端
```

---

## 验证结果

以下项目均在 **Paper 26.2 build 130（Java 25）** 实机上验证通过：

| 项目 | 结果 |
| --- | --- |
| 插件加载 | ✅ 无错误加载，`api-version: '26.2'` 有效 |
| 7z 命令行压缩 | ✅ 206.26 MB → 200.18 MB（随机数据），22 秒 |
| `-ssw` 修复占用文件 | ✅ 未加时 12 个区块文件被跳过（仅 4 KB）；加后全部打包 |
| 残缺备份保护 | ✅ 有文件被跳过时报错并删除压缩包，不上传 |
| 归档完整性 | ✅ 用参考实现 7zr 解压后与源目录逐文件 SHA-256 比对：缺失 0 / 不符 0（含中文名、空格名、空目录、0 字节文件） |
| 内置 7z 引擎 | ✅ 同上比对完全一致 |
| WebDAV 上传 | ✅ MKCOL/PUT/PROPFIND/DELETE 全通过；200 MB 上传后 SHA-256 与源文件一致 |
| 上传后删除本地 | ✅ 上传成功即删除，本地目录为空 |
| 网盘保留数量 | ✅ `keep-remote-count: 2` 时自动删除最旧的远端备份 |
| 本地保留数量 | ✅ `keep-count: 3` 连续备份 4 次后保留 3 个并输出删除原因 |
| 后台进度 | ✅ 每秒输出 `[压缩] NN% - xx MB / yy MB` |
| 实时状态 | ✅ `/worldbackup status` 返回 `压缩 25% / 上传 94%` 等实时百分比 |
| BOSS 血条 | ✅ 血条的创建/更新/移除代码路径每次备份均执行且无异常（未接入真实客户端做画面确认） |
| 取消任务 | ✅ 终止 7z 进程、删除未完成压缩包、恢复 `save-on` |
| 定时任务 | ✅ 间隔 1 分钟配置下自动触发备份 |
| 备份后命令 | ✅ `after-backup-commands` 正确替换 `%FILE%`/`%SIZE%` |
| 默认配置 | ✅ 删除配置后自动生成并可直接备份（AUTO 模式回退内置引擎） |
| 更名与版本 | ✅ v1.0.1 加载正常，日志显示名 `ZHM world backup helper`；旧 `/backup now` 已失效，`/worldbackup backupnow` 正常 |
| 上传链路（改名后复测） | ✅ 3 次备份全部上传成功、本地自动删除、远端保留 2 个；归档 56 文件 / 23 目录 |

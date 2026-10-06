package com.dsh.worldbackup.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * config.yml 的强类型映射。每次 /worldbackup reload 都会重新构建。
 */
public final class PluginSettings {

    /** 压缩引擎类型。 */
    public enum CompressionMode {
        /** 优先 7z 命令行，找不到则用内置 7z(LZMA2) 引擎。 */
        AUTO,
        /** 强制 7z 命令行。 */
        SEVEN_ZIP_CLI,
        /** 强制内置 7z(LZMA2) 引擎。 */
        SEVEN_ZIP_EMBEDDED,
        /** JDK 自带 zip，兜底方案。 */
        ZIP
    }

    private static final Map<String, String> DEFAULT_MESSAGES = new LinkedHashMap<>();

    static {
        DEFAULT_MESSAGES.put("prefix", "&8[&bZHM备份&8] &r");
        DEFAULT_MESSAGES.put("no-permission", "&c你没有权限执行该命令。");
        DEFAULT_MESSAGES.put("already-running", "&e已有备份任务正在运行（%STAGE% %PERCENT%%）。");
        DEFAULT_MESSAGES.put("started", "&a已开始备份，触发方式：&f%TRIGGER%&a。");
        DEFAULT_MESSAGES.put("not-running", "&7当前没有正在运行的备份任务。");
        DEFAULT_MESSAGES.put("cancelled", "&e已请求取消当前备份任务。");
        DEFAULT_MESSAGES.put("success", "&a备份完成：&f%FILE% &7(%SIZE%，用时 %DURATION%)");
        DEFAULT_MESSAGES.put("failed", "&c备份失败：&f%REASON%");
        DEFAULT_MESSAGES.put("broadcast-start", "&e[备份] 服务器正在备份存档，请稍候…");
        DEFAULT_MESSAGES.put("broadcast-success", "&a[备份] 存档备份完成，大小 %SIZE%，用时 %DURATION%。");
        DEFAULT_MESSAGES.put("broadcast-failed", "&c[备份] 存档备份失败。");
        DEFAULT_MESSAGES.put("reloaded", "&a配置文件已重载。");
        DEFAULT_MESSAGES.put("clean-result", "&a清理完成：删除 &f%DELETED% &a个旧备份，保留 &f%KEPT% &a个。");
        DEFAULT_MESSAGES.put("remote-clean", "&a网盘清理完成：删除 &f%DELETED% &a个远端备份。");
        DEFAULT_MESSAGES.put("list-header", "&8&m--------&r &b本地备份列表 &8&m--------");
        DEFAULT_MESSAGES.put("list-entry", "&7%INDEX%. &f%NAME% &8| &7%SIZE% &8| &7%DATE%");
        DEFAULT_MESSAGES.put("list-empty", "&7暂无本地备份文件。");
        DEFAULT_MESSAGES.put("list-footer", "&7共 &f%COUNT% &7个备份，占用 &f%TOTAL%&7。");
        DEFAULT_MESSAGES.put("status-idle", "&7当前空闲。上次备份：&f%LAST%");
        DEFAULT_MESSAGES.put("status-running", "&e正在备份 &8| &f%STAGE% &8| &f%PERCENT%% &8| &f%DETAIL%");
        DEFAULT_MESSAGES.put("upload-done", "&a上传完成：&f%REMOTE% &7(%SIZE%，用时 %DURATION%)");
        DEFAULT_MESSAGES.put("upload-skipped", "&7未启用 WebDAV 上传，备份保留在本地。");
        DEFAULT_MESSAGES.put("delete-local", "&7已按配置删除本地压缩包：&f%FILE%");
        DEFAULT_MESSAGES.put("unknown-command", "&c未知子命令。用法：&f/worldbackup <backupnow|status|list|clean|reload|cancel>");
    }

    private final Path serverRoot;

    private final List<String> worlds;
    private final boolean autoDetectExtraWorlds;
    private final boolean skipMissing;
    private final String outputDir;
    private final String fileNameFormat;
    private final String timestampFormat;
    private final boolean safeMode;
    private final long flushWaitMillis;
    private final List<String> exclude;
    private final int threads;
    private final int timeoutMinutes;
    private final boolean announce;

    private final CompressionMode compressionMode;
    private final String sevenZipPath;
    private final int level;
    private final boolean solid;
    private final boolean compressLockedFiles;
    private final boolean failOnWarning;
    private final List<String> extraArgs;

    private final boolean uploadEnabled;
    private final String uploadUrl;
    private final String uploadUsername;
    private final String uploadPassword;
    private final boolean deleteLocalAfterUpload;
    private final boolean allowInsecureTls;
    private final int connectTimeoutSeconds;
    private final int requestTimeoutMinutes;
    private final int retryCount;
    private final int retryDelaySeconds;
    private final int keepRemoteCount;

    private final boolean retentionEnabled;
    private final int keepCount;
    private final int maxAgeDays;
    private final String retentionPattern;
    private final boolean retentionOnStartup;

    private final boolean autoEnabled;
    private final int autoIntervalMinutes;
    private final int autoFirstDelayMinutes;
    private final List<String> dailyTimes;
    private final boolean skipWhenEmpty;

    private final boolean progressBossBar;
    private final String bossBarTitle;
    private final String bossBarColor;
    private final String bossBarStyle;
    private final boolean progressConsole;
    private final int consoleIntervalSeconds;

    private final List<String> afterBackupCommands;
    private final Map<String, String> messages;

    private PluginSettings(FileConfiguration cfg, Logger log, Path serverRoot) {
        this.serverRoot = serverRoot;

        this.worlds = new ArrayList<>(cfg.getStringList("backup.worlds"));
        if (this.worlds.isEmpty()) {
            this.worlds.add("world");
        }
        this.autoDetectExtraWorlds = cfg.getBoolean("backup.auto-detect-extra-worlds", true);
        this.skipMissing = cfg.getBoolean("backup.skip-missing", true);
        this.outputDir = cfg.getString("backup.output-dir", "backups");
        this.fileNameFormat = cfg.getString("backup.file-name-format", "%WORLD%-%TIMESTAMP%");
        this.timestampFormat = cfg.getString("backup.timestamp-format", "yyyy-MM-dd_HH-mm-ss");
        this.safeMode = cfg.getBoolean("backup.safe-mode", true);
        this.flushWaitMillis = Math.max(0L, cfg.getLong("backup.flush-wait-millis", 1500L));
        this.exclude = new ArrayList<>(cfg.getStringList("backup.exclude"));
        this.threads = Math.max(0, cfg.getInt("backup.threads", 0));
        this.timeoutMinutes = Math.max(0, cfg.getInt("backup.timeout-minutes", 120));
        this.announce = cfg.getBoolean("backup.announce", true);

        this.compressionMode = parseMode(cfg.getString("compression.mode", "AUTO"), log);
        this.sevenZipPath = cfg.getString("compression.seven-zip-path", "");
        this.level = clamp(cfg.getInt("compression.level", 9), 0, 9);
        this.solid = cfg.getBoolean("compression.solid", true);
        this.compressLockedFiles = cfg.getBoolean("compression.compress-locked-files", true);
        this.failOnWarning = cfg.getBoolean("compression.fail-on-warning", true);
        this.extraArgs = new ArrayList<>(cfg.getStringList("compression.extra-args"));

        this.uploadEnabled = cfg.getBoolean("upload.enabled", false);
        this.uploadUrl = cfg.getString("upload.url", "");
        this.uploadUsername = cfg.getString("upload.username", "");
        this.uploadPassword = cfg.getString("upload.password", "");
        this.deleteLocalAfterUpload = cfg.getBoolean("upload.delete-local-after-upload", true);
        this.allowInsecureTls = cfg.getBoolean("upload.allow-insecure-tls", false);
        this.connectTimeoutSeconds = Math.max(1, cfg.getInt("upload.connect-timeout-seconds", 30));
        this.requestTimeoutMinutes = Math.max(1, cfg.getInt("upload.request-timeout-minutes", 120));
        this.retryCount = Math.max(0, cfg.getInt("upload.retry-count", 3));
        this.retryDelaySeconds = Math.max(0, cfg.getInt("upload.retry-delay-seconds", 10));
        this.keepRemoteCount = Math.max(0, cfg.getInt("upload.keep-remote-count", 0));

        this.retentionEnabled = cfg.getBoolean("retention.enabled", true);
        this.keepCount = Math.max(0, cfg.getInt("retention.keep-count", 5));
        this.maxAgeDays = Math.max(0, cfg.getInt("retention.max-age-days", 0));
        this.retentionPattern = cfg.getString("retention.file-pattern", "*.7z");
        this.retentionOnStartup = cfg.getBoolean("retention.run-on-startup", true);

        this.autoEnabled = cfg.getBoolean("auto-backup.enabled", false);
        this.autoIntervalMinutes = Math.max(0, cfg.getInt("auto-backup.interval-minutes", 360));
        this.autoFirstDelayMinutes = Math.max(0, cfg.getInt("auto-backup.first-delay-minutes", 5));
        this.dailyTimes = new ArrayList<>(cfg.getStringList("auto-backup.daily-times"));
        this.skipWhenEmpty = cfg.getBoolean("auto-backup.skip-when-empty", false);

        this.progressBossBar = cfg.getBoolean("progress.boss-bar", true);
        this.bossBarTitle = cfg.getString("progress.boss-bar-title", "&b存档备份 &8| &f%STAGE% &7%PERCENT%%");
        this.bossBarColor = cfg.getString("progress.boss-bar-color", "BLUE");
        this.bossBarStyle = cfg.getString("progress.boss-bar-style", "SEGMENTED_20");
        this.progressConsole = cfg.getBoolean("progress.console", true);
        this.consoleIntervalSeconds = Math.max(1, cfg.getInt("progress.console-interval-seconds", 5));

        this.afterBackupCommands = new ArrayList<>(cfg.getStringList("after-backup-commands"));

        Map<String, String> loaded = new LinkedHashMap<>(DEFAULT_MESSAGES);
        ConfigurationSection section = cfg.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key);
                if (value != null) {
                    loaded.put(key, value);
                }
            }
        }
        this.messages = loaded;
    }

    private static CompressionMode parseMode(String raw, Logger log) {
        if (raw == null) {
            return CompressionMode.AUTO;
        }
        try {
            return CompressionMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("无法识别的 compression.mode: " + raw + "，已回退为 AUTO");
            return CompressionMode.AUTO;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static PluginSettings load(FileConfiguration cfg, Logger log, Path serverRoot) {
        return new PluginSettings(cfg, log, serverRoot);
    }

    public String message(String key) {
        String value = messages.get(key);
        return value != null ? value : DEFAULT_MESSAGES.getOrDefault(key, key);
    }

    public Path serverRoot() {
        return serverRoot;
    }

    public List<String> worlds() {
        return worlds;
    }

    public boolean autoDetectExtraWorlds() {
        return autoDetectExtraWorlds;
    }

    public boolean skipMissing() {
        return skipMissing;
    }

    public String outputDir() {
        return outputDir;
    }

    public String fileNameFormat() {
        return fileNameFormat;
    }

    public String timestampFormat() {
        return timestampFormat;
    }

    public boolean safeMode() {
        return safeMode;
    }

    public long flushWaitMillis() {
        return flushWaitMillis;
    }

    public List<String> exclude() {
        return exclude;
    }

    public int threads() {
        return threads;
    }

    public int timeoutMinutes() {
        return timeoutMinutes;
    }

    public boolean announce() {
        return announce;
    }

    public CompressionMode compressionMode() {
        return compressionMode;
    }

    public String sevenZipPath() {
        return sevenZipPath;
    }

    public int level() {
        return level;
    }

    public boolean solid() {
        return solid;
    }

    public boolean compressLockedFiles() {
        return compressLockedFiles;
    }

    public boolean failOnWarning() {
        return failOnWarning;
    }

    public List<String> extraArgs() {
        return extraArgs;
    }

    public boolean uploadEnabled() {
        return uploadEnabled;
    }

    public String uploadUrl() {
        return uploadUrl;
    }

    public String uploadUsername() {
        return uploadUsername;
    }

    public String uploadPassword() {
        return uploadPassword;
    }

    public boolean deleteLocalAfterUpload() {
        return deleteLocalAfterUpload;
    }

    public boolean allowInsecureTls() {
        return allowInsecureTls;
    }

    public int connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public int requestTimeoutMinutes() {
        return requestTimeoutMinutes;
    }

    public int retryCount() {
        return retryCount;
    }

    public int retryDelaySeconds() {
        return retryDelaySeconds;
    }

    public int keepRemoteCount() {
        return keepRemoteCount;
    }

    public boolean retentionEnabled() {
        return retentionEnabled;
    }

    public int keepCount() {
        return keepCount;
    }

    public int maxAgeDays() {
        return maxAgeDays;
    }

    public String retentionPattern() {
        return retentionPattern;
    }

    public boolean retentionOnStartup() {
        return retentionOnStartup;
    }

    public boolean autoEnabled() {
        return autoEnabled;
    }

    public int autoIntervalMinutes() {
        return autoIntervalMinutes;
    }

    public int autoFirstDelayMinutes() {
        return autoFirstDelayMinutes;
    }

    public List<String> dailyTimes() {
        return dailyTimes;
    }

    public boolean skipWhenEmpty() {
        return skipWhenEmpty;
    }

    public boolean progressBossBar() {
        return progressBossBar;
    }

    public String bossBarTitle() {
        return bossBarTitle;
    }

    public String bossBarColor() {
        return bossBarColor;
    }

    public String bossBarStyle() {
        return bossBarStyle;
    }

    public boolean progressConsole() {
        return progressConsole;
    }

    public int consoleIntervalSeconds() {
        return consoleIntervalSeconds;
    }

    public List<String> afterBackupCommands() {
        return afterBackupCommands;
    }
}

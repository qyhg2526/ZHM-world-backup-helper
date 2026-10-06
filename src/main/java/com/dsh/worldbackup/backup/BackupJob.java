package com.dsh.worldbackup.backup;

import com.dsh.worldbackup.WorldBackupPlugin;
import com.dsh.worldbackup.compress.CompressionResult;
import com.dsh.worldbackup.compress.Compressor;
import com.dsh.worldbackup.compress.CompressorFactory;
import com.dsh.worldbackup.compress.SourceEntry;
import com.dsh.worldbackup.compress.SourceSet;
import com.dsh.worldbackup.config.PluginSettings;
import com.dsh.worldbackup.progress.ProgressDisplay;
import com.dsh.worldbackup.upload.NoopUploader;
import com.dsh.worldbackup.upload.UploadResult;
import com.dsh.worldbackup.upload.Uploader;
import com.dsh.worldbackup.upload.WebDavUploader;
import com.dsh.worldbackup.util.BackupException;
import com.dsh.worldbackup.util.CancelledException;
import com.dsh.worldbackup.util.FileUtil;
import com.dsh.worldbackup.util.Text;
import org.bukkit.Bukkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 一次完整的备份任务：准备 → 扫描 → 压缩 → 上传 → 清理。
 * 全程以异步线程执行，进度同时输出到 BOSS 血条与服务器后台。
 */
public final class BackupJob implements Runnable {

    private final WorldBackupPlugin plugin;
    private final BackupService service;
    private final String trigger;
    private final String note;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final long createdAt = System.currentTimeMillis();

    private volatile String stage = "准备";
    private volatile double percent;
    private volatile String detail = "";
    private volatile Compressor compressor;
    private volatile Uploader uploader;
    private volatile Path archive;
    private volatile boolean archiveComplete;
    private volatile boolean success;
    private volatile String resultSummary = "进行中";

    public BackupJob(WorldBackupPlugin plugin, BackupService service, String trigger, String note) {
        this.plugin = plugin;
        this.service = service;
        this.trigger = trigger;
        this.note = note == null ? "" : note;
    }

    @Override
    public void run() {
        ProgressDisplay display = plugin.progress();
        display.begin();
        long start = System.currentTimeMillis();
        boolean saveDisabled = false;

        try {
            PluginSettings settings = plugin.settings();
            report(display, "准备", 0, "初始化备份任务");
            if (settings.announce()) {
                plugin.broadcast("broadcast-start", Text.ph("TRIGGER", trigger));
            }

            List<SourceEntry> sources = resolveSources(settings);
            if (sources.isEmpty()) {
                throw new BackupException("没有找到任何可备份的世界文件夹，请检查 backup.worlds");
            }
            String primaryName = sources.get(0).archiveName();
            String worldLabel = sources.stream().map(SourceEntry::archiveName).collect(Collectors.joining("+"));

            // ---------- 1. 安全模式：暂停自动保存并刷盘 ----------
            if (settings.safeMode()) {
                report(display, "准备", 2, "暂停自动保存并刷盘");
                Boolean ok = sync(() -> {
                    boolean off = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-off");
                    boolean saved = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
                    return off && saved;
                });
                saveDisabled = true;
                if (ok == null || !ok) {
                    plugin.getLogger().warning("save-off / save-all flush 执行异常，仍继续备份");
                }
                sleep(settings.flushWaitMillis());
            }

            // ---------- 2. 扫描 ----------
            report(display, "扫描", 8, "统计待备份文件");
            SourceSet set = SourceSet.scan(sources, settings.exclude(), plugin.getLogger());
            if (set.totalFiles() == 0) {
                throw new BackupException("待备份目录中没有可备份的文件");
            }
            plugin.getLogger().info("待备份数据：" + set.totalFiles() + " 个文件，"
                    + FileUtil.formatSize(set.totalBytes()));

            // ---------- 3. 压缩 ----------
            compressor = CompressorFactory.create(settings, plugin.serverRoot(), plugin.getLogger());
            Path outputDir = plugin.resolveServerPath(settings.outputDir());
            Files.createDirectories(outputDir);
            String baseName = buildBaseName(settings, primaryName, worldLabel);
            archive = outputDir.resolve(baseName + compressor.extension());
            Files.deleteIfExists(archive);

            plugin.getLogger().info("开始压缩：" + archive.getFileName() + " | 引擎：" + compressor.displayName()
                    + " | 等级：" + settings.level());
            report(display, "压缩", 15, FileUtil.formatSize(set.totalBytes()) + " → " + archive.getFileName());

            CompressionResult compression = compressor.compress(set, archive, (processed, total, ratio) ->
                    report(display, "压缩", 15 + ratio * 55,
                            FileUtil.formatSize(processed) + " / " + FileUtil.formatSize(total)));
            archiveComplete = true;
            String fileName = compression.archive().getFileName().toString();
            plugin.getLogger().info("压缩完成：" + fileName + " " + FileUtil.formatSize(compression.archiveBytes())
                    + "（原始 " + FileUtil.formatSize(compression.sourceBytes()) + "，节省 "
                    + String.format(Locale.ROOT, "%.1f%%", compression.ratio() * 100) + "，用时 "
                    + FileUtil.formatDuration(compression.durationMillis()) + "）");

            // ---------- 4. 上传 ----------
            PluginSettings live = plugin.settings();
            uploader = live.uploadEnabled() ? new WebDavUploader(live, plugin.getLogger()) : new NoopUploader();
            if (live.uploadEnabled()) {
                report(display, "上传", 72, "连接 " + live.uploadUrl());
                plugin.getLogger().info("开始上传到网盘：" + uploader.describe());
                UploadResult uploadResult = uploader.upload(compression.archive(), fileName, (processed, total, ratio) ->
                        report(display, "上传", 72 + ratio * 22,
                                FileUtil.formatSize(processed) + " / " + FileUtil.formatSize(total)));
                if (uploadResult.uploaded()) {
                    plugin.getLogger().info("上传完成：" + uploadResult.remoteLocation() + "（"
                            + FileUtil.formatSize(uploadResult.bytes()) + "，用时 "
                            + FileUtil.formatDuration(uploadResult.durationMillis()) + "）");
                    if (live.deleteLocalAfterUpload()) {
                        Files.deleteIfExists(compression.archive());
                        archive = null;
                        plugin.getLogger().info("已按配置删除本地压缩包：" + fileName);
                    }
                }
            } else {
                plugin.getLogger().info("未启用 WebDAV 上传，备份文件保留在本地：" + fileName);
            }

            // ---------- 5. 保留策略清理 ----------
            int keepCount = live.keepCount();
            report(display, "清理", 95, "保留 " + (keepCount > 0 ? keepCount + " 个最近备份" : "全部备份"));
            RetentionManager.Result retention = plugin.retentionManager().cleanupLocal();
            if (retention.deleted() > 0) {
                plugin.getLogger().info("本地清理：删除 " + retention.deleted() + " 个旧备份，释放 "
                        + FileUtil.formatSize(retention.freedBytes()) + "，保留 " + retention.remaining() + " 个");
            }
            if (live.uploadEnabled() && live.keepRemoteCount() > 0) {
                int remoteDeleted = uploader.cleanupRemote(live.retentionPattern(), live.keepRemoteCount());
                if (remoteDeleted > 0) {
                    plugin.getLogger().info("网盘清理：删除 " + remoteDeleted + " 个远端旧备份，保留 "
                            + live.keepRemoteCount() + " 个");
                }
            }
            uploader.close();

            // ---------- 完成 ----------
            long elapsed = System.currentTimeMillis() - start;
            String sizeText = FileUtil.formatSize(compression.archiveBytes());
            String durationText = FileUtil.formatDuration(elapsed);
            resultSummary = fileName + "（" + sizeText + "，原始 " + FileUtil.formatSize(compression.sourceBytes())
                    + "，节省 " + String.format(Locale.ROOT, "%.1f%%", compression.ratio() * 100)
                    + "，用时 " + durationText + "）";
            success = true;
            report(display, "完成", 100, fileName + " " + sizeText);

            Map<String, String> placeholders = Text.ph(
                    "FILE", fileName,
                    "SIZE", sizeText,
                    "DURATION", durationText,
                    "WORLD", worldLabel,
                    "TRIGGER", trigger);
            plugin.send(Bukkit.getConsoleSender(), "success", placeholders);
            if (plugin.settings().announce()) {
                plugin.broadcast("broadcast-success", placeholders);
            }
            runAfterCommands(placeholders);
        } catch (CancelledException e) {
            success = false;
            resultSummary = "已取消";
            plugin.getLogger().warning("备份任务已取消");
            deleteIncompleteArchive();
        } catch (Throwable t) {
            success = false;
            String reason = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            resultSummary = "失败：" + reason;
            plugin.getLogger().severe("备份失败：" + reason);
            t.printStackTrace();
            deleteIncompleteArchive();
            plugin.send(Bukkit.getConsoleSender(), "failed", Text.ph("REASON", reason));
            if (plugin.settings().announce()) {
                plugin.broadcast("broadcast-failed", Text.ph("REASON", reason));
            }
        } finally {
            if (saveDisabled) {
                try {
                    sync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-on"));
                } catch (Exception e) {
                    plugin.getLogger().warning("恢复自动保存失败，请手动执行 save-on：" + e.getMessage());
                }
            }
            uploader = null;
            compressor = null;
            display.end();
            service.finish(this, success, resultSummary);
        }
    }

    /** 取消任务（压缩阶段会终止外部 7z 进程）。 */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            Compressor currentCompressor = compressor;
            if (currentCompressor != null) {
                currentCompressor.cancel();
            }
            Uploader currentUploader = uploader;
            if (currentUploader != null) {
                currentUploader.cancel();
            }
        }
    }

    private void deleteIncompleteArchive() {
        Path path = archive;
        if (!archiveComplete && path != null) {
            try {
                Files.deleteIfExists(path);
                plugin.getLogger().info("已删除未完成的压缩包：" + path.getFileName());
            } catch (Exception e) {
                plugin.getLogger().warning("无法删除未完成的压缩包：" + e.getMessage());
            }
        }
    }

    private void runAfterCommands(Map<String, String> placeholders) {
        List<String> commands = plugin.settings().afterBackupCommands();
        if (commands.isEmpty()) {
            return;
        }
        try {
            sync(() -> {
                for (String raw : commands) {
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }
                    String command = Text.apply(raw, placeholders);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
                return true;
            });
        } catch (Exception e) {
            plugin.getLogger().warning("执行 after-backup-commands 失败：" + e.getMessage());
        }
    }

    /** 解析需要备份的目录（含旧版独立维度目录的自动探测）。 */
    private List<SourceEntry> resolveSources(PluginSettings settings) {
        List<String> names = new ArrayList<>(settings.worlds());
        if (settings.autoDetectExtraWorlds()) {
            for (String extra : List.of("world_nether", "world_the_end")) {
                if (names.stream().noneMatch(name -> name.equalsIgnoreCase(extra))) {
                    names.add(extra);
                }
            }
        }
        List<SourceEntry> sources = new ArrayList<>();
        List<String> configured = settings.worlds();
        for (String name : names) {
            Path path = plugin.resolveServerPath(name);
            boolean explicitlyConfigured = configured.stream().anyMatch(value -> value.equalsIgnoreCase(name));
            if (!Files.isDirectory(path)) {
                if (!explicitlyConfigured) {
                    continue;
                }
                if (settings.skipMissing()) {
                    plugin.getLogger().warning("世界文件夹不存在，已跳过：" + path);
                    continue;
                }
                throw new BackupException("世界文件夹不存在：" + path);
            }
            String archiveName = path.getFileName() == null ? name : path.getFileName().toString();
            boolean duplicate = sources.stream().anyMatch(entry -> entry.path().equals(path));
            if (!duplicate) {
                sources.add(new SourceEntry(path, archiveName));
            }
        }
        return sources;
    }

    private String buildBaseName(PluginSettings settings, String primaryName, String worldLabel) {
        String timestamp = formatTimestamp(settings);
        String format = settings.fileNameFormat() == null ? "%WORLD%-%TIMESTAMP%" : settings.fileNameFormat();
        boolean hasTimestamp = format.contains("%TIMESTAMP%");
        String name = format.replace("%WORLD%", primaryName).replace("%WORLD%", worldLabel)
                .replace("%TIMESTAMP%", timestamp);
        if (!hasTimestamp) {
            name = name + "-" + timestamp;
        }
        return FileUtil.sanitizeFileName(name);
    }

    private String formatTimestamp(PluginSettings settings) {
        String pattern = settings.timestampFormat();
        try {
            return DateTimeFormatter.ofPattern(pattern).format(LocalDateTime.now());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("backup.timestamp-format 不合法（" + pattern + "），已使用默认格式");
            return DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now());
        }
    }

    private void report(ProgressDisplay display, String stage, double percent, String detail) {
        this.stage = stage;
        this.percent = percent;
        this.detail = detail;
        display.update(stage, percent, detail);
    }

    /** 在主线程执行并等待结果。 */
    private <T> T sync(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return task.call();
        }
        Future<T> future = Bukkit.getScheduler().callSyncMethod(plugin, task);
        return future.get(60L, TimeUnit.SECONDS);
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException("备份已中断");
        }
    }

    public String stage() {
        return stage;
    }

    public double percent() {
        return percent;
    }

    public String detail() {
        return detail;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String resultSummary() {
        return resultSummary;
    }

    public boolean isSuccess() {
        return success;
    }

    public Map<String, String> describe() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("TRIGGER", trigger);
        info.put("NOTE", note);
        info.put("STAGE", stage);
        info.put("PERCENT", String.format(Locale.ROOT, "%.0f", percent));
        info.put("DETAIL", detail);
        info.put("ELAPSED", FileUtil.formatDuration(System.currentTimeMillis() - createdAt));
        return info;
    }
}

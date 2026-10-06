package com.dsh.worldbackup.backup;

import com.dsh.worldbackup.WorldBackupPlugin;
import com.dsh.worldbackup.config.PluginSettings;
import com.dsh.worldbackup.util.FileUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 本地备份保留策略：自动检测备份数量（并可按时间）删除多余备份。
 */
public final class RetentionManager {

    /**
     * 清理结果。
     *
     * @param deleted      删除数量
     * @param kept         保留数量
     * @param freedBytes   释放的字节数
     * @param remaining    清理后剩余数量
     */
    public record Result(int deleted, int kept, long freedBytes, int remaining) {
    }

    private final WorldBackupPlugin plugin;

    public RetentionManager(WorldBackupPlugin plugin) {
        this.plugin = plugin;
    }

    /** 备份输出目录。 */
    public Path outputDirectory() {
        PluginSettings settings = plugin.settings();
        Path dir = Path.of(settings.outputDir());
        if (!dir.isAbsolute()) {
            dir = plugin.serverRoot().resolve(dir);
        }
        return dir.normalize().toAbsolutePath();
    }

    /** 当前本地备份（按时间倒序）。 */
    public List<Path> listBackups() throws IOException {
        PluginSettings settings = plugin.settings();
        return FileUtil.listFiles(outputDirectory(), settings.retentionPattern());
    }

    /** 执行本地清理。 */
    public Result cleanupLocal() throws IOException {
        PluginSettings settings = plugin.settings();
        if (!settings.retentionEnabled()) {
            List<Path> all = listBackups();
            return new Result(0, all.size(), 0L, all.size());
        }
        Path dir = outputDirectory();
        List<Path> files = FileUtil.listFiles(dir, settings.retentionPattern());
        int keepCount = settings.keepCount();
        long maxAgeMillis = settings.maxAgeDays() > 0L ? settings.maxAgeDays() * 86_400_000L : -1L;
        long now = System.currentTimeMillis();

        int deleted = 0;
        int kept = 0;
        long freed = 0L;
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            boolean overCount = keepCount > 0 && i >= keepCount;
            boolean tooOld = false;
            if (maxAgeMillis > 0) {
                tooOld = now - Files.getLastModifiedTime(file).toMillis() > maxAgeMillis;
            }
            if (overCount || tooOld) {
                long size = Files.size(file);
                if (Files.deleteIfExists(file)) {
                    deleted++;
                    freed += size;
                    plugin.getLogger().info("已删除旧备份：" + file.getFileName()
                            + "（" + FileUtil.formatSize(size) + "，原因：" + (overCount ? "超出保留数量 " + keepCount : "超过保留天数 " + settings.maxAgeDays()) + "）");
                }
            } else {
                kept++;
            }
        }
        return new Result(deleted, kept, freed, kept);
    }
}

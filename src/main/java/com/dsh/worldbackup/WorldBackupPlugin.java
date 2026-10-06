package com.dsh.worldbackup;

import com.dsh.worldbackup.backup.AutoBackupTask;
import com.dsh.worldbackup.backup.BackupService;
import com.dsh.worldbackup.backup.RetentionManager;
import com.dsh.worldbackup.command.BackupCommand;
import com.dsh.worldbackup.config.PluginSettings;
import com.dsh.worldbackup.listener.BossBarJoinListener;
import com.dsh.worldbackup.progress.ProgressDisplay;
import com.dsh.worldbackup.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ZHM world backup helper 主类。
 * Paper 26.2 / Java 25。
 */
public final class WorldBackupPlugin extends JavaPlugin {

    private volatile PluginSettings settings;
    private ProgressDisplay progress;
    private BackupService backupService;
    private AutoBackupTask autoBackupTask;
    private RetentionManager retentionManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.settings = PluginSettings.load(getConfig(), getLogger(), serverRoot());
        this.progress = new ProgressDisplay(this);
        this.backupService = new BackupService(this);
        this.retentionManager = new RetentionManager(this);

        PluginCommand command = getCommand("worldbackup");
        if (command != null) {
            BackupCommand handler = new BackupCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().warning("未能注册 /worldbackup 命令，请检查 plugin.yml");
        }
        getServer().getPluginManager().registerEvents(new BossBarJoinListener(this), this);

        this.autoBackupTask = new AutoBackupTask(this);
        this.autoBackupTask.start();

        if (settings.retentionEnabled() && settings.retentionOnStartup()) {
            getServer().getScheduler().runTaskLaterAsynchronously(this, () -> {
                try {
                    RetentionManager.Result result = retentionManager.cleanupLocal();
                    if (result.deleted() > 0) {
                        getLogger().info("启动清理完成：删除 " + result.deleted() + " 个旧备份，释放 "
                                + com.dsh.worldbackup.util.FileUtil.formatSize(result.freedBytes()));
                    }
                } catch (Exception e) {
                    getLogger().warning("启动清理失败：" + e.getMessage());
                }
            }, 100L);
        }

        getLogger().info("ZHM world backup helper 已启用 | 备份目录: " + String.join(", ", settings.worlds())
                + " | 压缩模式: " + settings.compressionMode()
                + " | 上传: " + (settings.uploadEnabled() ? settings.uploadUrl() : "未启用")
                + " | 本地保留: " + (settings.keepCount() > 0 ? settings.keepCount() + " 个" : "不限制"));
    }

    @Override
    public void onDisable() {
        if (backupService != null) {
            backupService.cancel();
        }
        if (autoBackupTask != null) {
            autoBackupTask.stop();
        }
        if (progress != null) {
            progress.end();
        }
        getLogger().info("ZHM world backup helper 已卸载");
    }

    /** 重新读取 config.yml。 */
    public void reloadAll() {
        reloadConfig();
        this.settings = PluginSettings.load(getConfig(), getLogger(), serverRoot());
        if (autoBackupTask != null) {
            autoBackupTask.reload();
        }
    }

    /** 服务器根目录（世界文件夹的上级目录）。 */
    public Path serverRoot() {
        return Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    /** 把配置中的相对路径解析为绝对路径。 */
    public Path resolveServerPath(String configured) {
        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            path = serverRoot().resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }

    public PluginSettings settings() {
        return settings;
    }

    public ProgressDisplay progress() {
        return progress;
    }

    public BackupService backupService() {
        return backupService;
    }

    public RetentionManager retentionManager() {
        return retentionManager;
    }

    /** 向指定接收者发送带前缀的配置化消息。 */
    public void send(CommandSender receiver, String key, Map<String, String> placeholders) {
        Map<String, String> values = placeholders == null ? Map.of() : placeholders;
        receiver.sendMessage(Text.render(settings.message("prefix") + settings.message(key), values));
    }

    public void send(CommandSender receiver, String key, String... placeholders) {
        send(receiver, key, Text.ph(placeholders));
    }

    /** 全服广播。 */
    public void broadcast(String key, Map<String, String> placeholders) {
        Map<String, String> values = placeholders == null ? new LinkedHashMap<>() : new LinkedHashMap<>(placeholders);
        Bukkit.broadcast(Text.render(settings.message("prefix") + settings.message(key), values));
    }
}

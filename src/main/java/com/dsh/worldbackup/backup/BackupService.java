package com.dsh.worldbackup.backup;

import com.dsh.worldbackup.WorldBackupPlugin;
import com.dsh.worldbackup.util.FileUtil;
import com.dsh.worldbackup.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 备份任务的调度与状态管理：保证同一时间只有一个任务在运行。
 */
public final class BackupService {

    private final WorldBackupPlugin plugin;
    private final AtomicBoolean busy = new AtomicBoolean();

    private volatile BackupJob current;
    private volatile String lastSummary = "尚未执行过备份";
    private volatile boolean lastSuccess;
    private volatile long lastRunMillis;

    public BackupService(WorldBackupPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 启动一次备份。
     *
     * @return false 表示已有任务在运行
     */
    public boolean start(String trigger, String note) {
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        BackupJob job = new BackupJob(plugin, this, trigger, note);
        this.current = job;
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, job);
        } catch (Throwable t) {
            this.current = null;
            busy.set(false);
            throw t;
        }
        return true;
    }

    void finish(BackupJob job, boolean success, String summary) {
        this.lastSuccess = success;
        this.lastSummary = summary;
        this.lastRunMillis = System.currentTimeMillis();
        this.current = null;
        this.busy.set(false);
    }

    /** 仅执行一次本地清理。 */
    public boolean runRetentionAsync(CommandSender feedback) {
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                RetentionManager.Result result = plugin.retentionManager().cleanupLocal();
                if (feedback != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.send(feedback, "clean-result", Text.ph(
                            "DELETED", String.valueOf(result.deleted()),
                            "KEPT", String.valueOf(result.remaining()),
                            "SIZE", FileUtil.formatSize(result.freedBytes()))));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("清理备份失败：" + e.getMessage());
                if (feedback != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.send(feedback, "failed",
                            Text.ph("REASON", String.valueOf(e.getMessage()))));
                }
            } finally {
                busy.set(false);
            }
        });
        return true;
    }

    public void cancel() {
        BackupJob job = current;
        if (job != null) {
            job.cancel();
        }
    }

    public boolean isRunning() {
        return busy.get();
    }

    public BackupJob current() {
        return current;
    }

    public String lastSummary() {
        return lastSummary;
    }

    public boolean lastSuccess() {
        return lastSuccess;
    }

    public long lastRunMillis() {
        return lastRunMillis;
    }

    public String lastRunText() {
        if (lastRunMillis <= 0L) {
            return "从未";
        }
        return FileUtil.formatDate(lastRunMillis) + "（" + (lastSuccess ? "成功" : "失败") + "）";
    }
}

package com.dsh.worldbackup.backup;

import com.dsh.worldbackup.WorldBackupPlugin;
import com.dsh.worldbackup.config.PluginSettings;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 定时自动备份：支持固定间隔与每日固定时刻两种触发方式。
 */
public final class AutoBackupTask {

    private static final long CHECK_INTERVAL_TICKS = 400L; // 每 20 秒检查一次

    private final WorldBackupPlugin plugin;
    private final Set<String> firedToday = new HashSet<>();

    private volatile BukkitTask task;
    private volatile long lastRunMillis;
    private volatile long enabledAtMillis;
    private volatile String currentDay = "";

    public AutoBackupTask(WorldBackupPlugin plugin) {
        this.plugin = plugin;
    }

    /** 启动（或重启）定时任务；配置未启用时不做任何事。 */
    public void start() {
        stop();
        PluginSettings settings = plugin.settings();
        if (!settings.autoEnabled()) {
            plugin.getLogger().info("定时自动备份：未启用");
            return;
        }
        long now = System.currentTimeMillis();
        this.enabledAtMillis = now;
        this.lastRunMillis = now;
        this.currentDay = LocalDate.now().toString();
        this.firedToday.clear();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
        plugin.getLogger().info("定时自动备份已启用：间隔 " + settings.autoIntervalMinutes() + " 分钟，每日时刻 "
                + settings.dailyTimes() + "，首次延迟 " + settings.autoFirstDelayMinutes() + " 分钟");
    }

    public void stop() {
        BukkitTask current = task;
        if (current != null) {
            current.cancel();
            task = null;
        }
    }

    public void reload() {
        start();
    }

    private void tick() {
        PluginSettings settings = plugin.settings();
        if (!settings.autoEnabled() || plugin.backupService().isRunning()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - enabledAtMillis < settings.autoFirstDelayMinutes() * 60_000L) {
            return;
        }

        String today = LocalDate.now().toString();
        if (!today.equals(currentDay)) {
            currentDay = today;
            firedToday.clear();
        }

        boolean due = settings.autoIntervalMinutes() > 0
                && now - lastRunMillis >= settings.autoIntervalMinutes() * 60_000L;

        if (!due && !settings.dailyTimes().isEmpty()) {
            LocalTime nowTime = LocalTime.now().withSecond(0).withNano(0);
            for (String raw : settings.dailyTimes()) {
                LocalTime target = parseTime(raw);
                if (target == null || firedToday.contains(raw)) {
                    continue;
                }
                if (nowTime.equals(target)) {
                    firedToday.add(raw);
                    due = true;
                    break;
                }
            }
        }

        if (!due) {
            return;
        }
        if (settings.skipWhenEmpty() && Bukkit.getOnlinePlayers().isEmpty()) {
            plugin.getLogger().info("定时备份已跳过：当前没有玩家在线");
            lastRunMillis = now;
            return;
        }
        if (plugin.backupService().start("定时任务", "")) {
            lastRunMillis = now;
        }
    }

    private static LocalTime parseTime(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            String[] parts = raw.trim().split(":");
            if (parts.length < 2) {
                return null;
            }
            return LocalTime.of(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (Exception e) {
            return null;
        }
    }
}

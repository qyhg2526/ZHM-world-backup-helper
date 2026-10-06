package com.dsh.worldbackup.progress;

import com.dsh.worldbackup.WorldBackupPlugin;
import com.dsh.worldbackup.config.PluginSettings;
import com.dsh.worldbackup.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 备份进度的统一展示：BOSS 血条栏 + 服务器后台日志。
 * 进度更新可能来自异步线程，血条操作会被调度回主线程执行。
 */
public final class ProgressDisplay {

    private final WorldBackupPlugin plugin;
    private final AtomicLong lastBossBarUpdate = new AtomicLong(0L);
    private final AtomicLong lastConsoleUpdate = new AtomicLong(0L);
    private final AtomicLong lastConsoleSecond = new AtomicLong(-1L);
    private volatile BossBar bossBar;
    private volatile String stage = "";
    private volatile double percent = 0.0;
    private volatile String detail = "";

    public ProgressDisplay(WorldBackupPlugin plugin) {
        this.plugin = plugin;
    }

    /** 任务开始：创建血条并显示给所有在线玩家。 */
    public void begin() {
        PluginSettings settings = plugin.settings();
        stage = "准备";
        percent = 0.0;
        detail = "";
        lastBossBarUpdate.set(0L);
        lastConsoleUpdate.set(0L);
        lastConsoleSecond.set(-1L);
        if (!settings.progressBossBar()) {
            return;
        }
        runOnMain(() -> {
            BossBar bar = Bukkit.createBossBar(bossBarTitle(settings), barColor(settings), barStyle(settings));
            bar.setProgress(0.0D);
            bar.setVisible(true);
            for (Player player : Bukkit.getOnlinePlayers()) {
                bar.addPlayer(player);
            }
            bossBar = bar;
        });
    }

    /** 更新进度（可从任意线程调用）。 */
    public void update(String newStage, double newPercent, String newDetail) {
        this.stage = newStage == null ? "" : newStage;
        this.percent = Math.max(0.0, Math.min(100.0, newPercent));
        this.detail = newDetail == null ? "" : newDetail;
        PluginSettings settings = plugin.settings();

        if (settings.progressBossBar()) {
            long now = System.currentTimeMillis();
            long last = lastBossBarUpdate.get();
            if (now - last >= 200L || percent >= 100.0) {
                lastBossBarUpdate.set(now);
                refreshBossBar(settings);
            }
        }

        if (settings.progressConsole()) {
            long now = System.currentTimeMillis();
            long second = now / 1000L;
            if (now - lastConsoleUpdate.get() >= settings.consoleIntervalSeconds() * 1000L
                    || percent >= 100.0 || lastConsoleSecond.get() < 0) {
                lastConsoleUpdate.set(now);
                if (lastConsoleSecond.getAndSet(second) != second || percent >= 100.0) {
                    plugin.getLogger().info(consoleLine());
                }
            }
        }
    }

    private String consoleLine() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(stage).append("] ").append(String.format(Locale.ROOT, "%.0f%%", percent));
        if (!detail.isBlank()) {
            sb.append(" - ").append(detail);
        }
        return sb.toString();
    }

    private void refreshBossBar(PluginSettings settings) {
        runOnMain(() -> {
            BossBar bar = bossBar;
            if (bar == null) {
                return;
            }
            bar.setTitle(bossBarTitle(settings));
            bar.setProgress(Math.max(0.0, Math.min(1.0, percent / 100.0)));
            bar.setColor(barColor(settings));
            bar.setStyle(barStyle(settings));
            if (!bar.isVisible()) {
                bar.setVisible(true);
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!bar.getPlayers().contains(player)) {
                    bar.addPlayer(player);
                }
            }
        });
    }

    private String bossBarTitle(PluginSettings settings) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("STAGE", stage);
        placeholders.put("PERCENT", String.format(Locale.ROOT, "%.0f", percent));
        placeholders.put("DETAIL", detail);
        return Text.section(settings.bossBarTitle(), placeholders);
    }

    private static BarColor barColor(PluginSettings settings) {
        try {
            return BarColor.valueOf(settings.bossBarColor().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BarColor.BLUE;
        }
    }

    private static BarStyle barStyle(PluginSettings settings) {
        try {
            return BarStyle.valueOf(settings.bossBarStyle().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BarStyle.SEGMENTED_20;
        }
    }

    /** 任务结束：移除血条。 */
    public void end() {
        runOnMain(() -> {
            BossBar bar = bossBar;
            if (bar != null) {
                bar.removeAll();
                bar.setVisible(false);
            }
            bossBar = null;
        });
    }

    /** 备份过程中加入的玩家也能看到进度条。 */
    public void addPlayer(Player player) {
        runOnMain(() -> {
            BossBar bar = bossBar;
            if (bar != null && !bar.getPlayers().contains(player)) {
                bar.addPlayer(player);
            }
        });
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

    private void runOnMain(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, runnable);
            } catch (IllegalStateException e) {
                // 服务器正在关闭
                runnable.run();
            }
        }
    }
}

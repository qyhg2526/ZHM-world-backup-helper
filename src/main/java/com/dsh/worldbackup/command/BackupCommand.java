package com.dsh.worldbackup.command;

import com.dsh.worldbackup.WorldBackupPlugin;
import com.dsh.worldbackup.backup.BackupJob;
import com.dsh.worldbackup.util.FileUtil;
import com.dsh.worldbackup.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /worldbackup 命令。
 * backupnow [备注] | status | list | clean | reload | cancel
 */
public final class BackupCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of("backupnow", "status", "list", "clean", "reload", "cancel");

    private final WorldBackupPlugin plugin;

    public BackupCommand(WorldBackupPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "backupnow", "now", "start", "run" -> startBackup(sender, args);
            case "status", "info" -> showStatus(sender);
            case "list", "ls" -> showList(sender);
            case "clean", "cleanup", "prune" -> clean(sender);
            case "reload" -> reload(sender);
            case "cancel", "stop" -> cancel(sender);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private void startBackup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("worldbackup.admin")) {
            plugin.send(sender, "no-permission");
            return;
        }
        StringBuilder note = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (note.length() > 0) {
                note.append(' ');
            }
            note.append(args[i]);
        }
        String trigger = sender.getName() + " 手动触发";
        if (!plugin.backupService().start(trigger, note.toString())) {
            BackupJob job = plugin.backupService().current();
            if (job != null) {
                plugin.send(sender, "already-running", job.describe());
            } else {
                plugin.send(sender, "already-running", Text.ph("STAGE", "-", "PERCENT", "0"));
            }
            return;
        }
        plugin.send(sender, "started", Text.ph("TRIGGER", trigger));
    }

    private void showStatus(CommandSender sender) {
        if (!sender.hasPermission("worldbackup.use")) {
            plugin.send(sender, "no-permission");
            return;
        }
        BackupJob job = plugin.backupService().current();
        if (job != null && plugin.backupService().isRunning()) {
            plugin.send(sender, "status-running", job.describe());
            return;
        }
        plugin.send(sender, "status-idle", Text.ph("LAST",
                plugin.backupService().lastRunText() + " " + plugin.backupService().lastSummary()));
    }

    private void showList(CommandSender sender) {
        if (!sender.hasPermission("worldbackup.use")) {
            plugin.send(sender, "no-permission");
            return;
        }
        try {
            List<Path> backups = plugin.retentionManager().listBackups();
            if (backups.isEmpty()) {
                plugin.send(sender, "list-empty");
                return;
            }
            plugin.send(sender, "list-header");
            long total = 0L;
            int index = 1;
            for (Path file : backups) {
                long size = Files.size(file);
                total += size;
                plugin.send(sender, "list-entry", Text.ph(
                        "INDEX", String.valueOf(index++),
                        "NAME", file.getFileName().toString(),
                        "SIZE", FileUtil.formatSize(size),
                        "DATE", FileUtil.formatDate(Files.getLastModifiedTime(file).toMillis())));
            }
            plugin.send(sender, "list-footer", Text.ph(
                    "COUNT", String.valueOf(backups.size()),
                    "TOTAL", FileUtil.formatSize(total)));
        } catch (Exception e) {
            plugin.send(sender, "failed", Text.ph("REASON", String.valueOf(e.getMessage())));
        }
    }

    private void clean(CommandSender sender) {
        if (!sender.hasPermission("worldbackup.admin")) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (!plugin.backupService().runRetentionAsync(sender)) {
            BackupJob job = plugin.backupService().current();
            if (job != null) {
                plugin.send(sender, "already-running", job.describe());
            }
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("worldbackup.admin")) {
            plugin.send(sender, "no-permission");
            return;
        }
        try {
            plugin.reloadAll();
            plugin.send(sender, "reloaded");
        } catch (Exception e) {
            plugin.send(sender, "failed", Text.ph("REASON", String.valueOf(e.getMessage())));
        }
    }

    private void cancel(CommandSender sender) {
        if (!sender.hasPermission("worldbackup.admin")) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (plugin.backupService().isRunning()) {
            plugin.backupService().cancel();
            plugin.send(sender, "cancelled");
        } else {
            plugin.send(sender, "not-running", Text.ph("LAST", plugin.backupService().lastRunText()));
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        plugin.send(sender, "unknown-command");
        sender.sendMessage(Text.render("&7/" + label + " backupnow [备注] &8- &f立即手动备份一次", null));
        sender.sendMessage(Text.render("&7/" + label + " status &8- &f查看当前进度", null));
        sender.sendMessage(Text.render("&7/" + label + " list &8- &f列出本地备份", null));
        sender.sendMessage(Text.render("&7/" + label + " clean &8- &f立即执行保留策略清理", null));
        sender.sendMessage(Text.render("&7/" + label + " cancel &8- &f取消当前备份", null));
        sender.sendMessage(Text.render("&7/" + label + " reload &8- &f重载配置文件", null));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            for (String sub : SUB_COMMANDS) {
                if (sub.startsWith(prefix) && hasPermissionFor(sender, sub)) {
                    result.add(sub);
                }
            }
            return result;
        }
        return List.of();
    }

    private boolean hasPermissionFor(CommandSender sender, String sub) {
        return switch (sub) {
            case "status", "list" -> sender.hasPermission("worldbackup.use");
            default -> sender.hasPermission("worldbackup.admin");
        };
    }
}

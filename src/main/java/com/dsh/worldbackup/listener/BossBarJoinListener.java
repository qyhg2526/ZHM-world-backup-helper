package com.dsh.worldbackup.listener;

import com.dsh.worldbackup.WorldBackupPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** 备份进行中时，新加入的玩家也能看到进度血条。 */
public final class BossBarJoinListener implements Listener {

    private final WorldBackupPlugin plugin;

    public BossBarJoinListener(WorldBackupPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.backupService().isRunning()) {
            plugin.progress().addPlayer(event.getPlayer());
        }
    }
}

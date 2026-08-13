package com.authmedemo.task;

// ===================== 实现思路 =====================
// 出生点强制锁定任务（每 tick 执行一次）
// 如果 settings.force-spawn-location = true：
//   对所有在线但未登录的玩家，强制传送到他所在世界的出生点
//   这比单纯 cancel PlayerMoveEvent 更严格——活塞/水流/爆炸/骑乘都带不走他
// 为什么用 BukkitRunnable 而不是在 PlayerMoveEvent 里 teleport？
//   - PlayerMoveEvent 触发频率高达每 tick 多次，在事件里 teleport 有概率触发新的事件造成递归
//   - 独立任务每 tick 统一拉一次，简单直接不会递归
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.cache.PlayerCache;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class SpawnLockTask implements Runnable {

    private final PlayerCache cache;

    public SpawnLockTask(AuthMeDemo plugin) {
        this.cache = plugin.getPlayerCache();
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // 已经登录 → 放行，不操作
            if (cache.isLoggedIn(player.getUniqueId())) {
                continue;
            }

            World world = player.getWorld();
            Location spawn = world.getSpawnLocation();
            Location current = player.getLocation();

            // 只有当玩家离开出生点 0.5 格以上才传送，避免每 tick 都 teleport 产生粒子效果
            double dx = Math.abs(current.getX() - spawn.getX());
            double dy = Math.abs(current.getY() - spawn.getY());
            double dz = Math.abs(current.getZ() - spawn.getZ());
            if (dx > 0.5 || dy > 0.5 || dz > 0.5) {
                // teleport 是同步 Bukkit API，这个任务本身就是同步调度的，所以可以直接调用
                player.teleport(spawn);
            }
        }
    }
}

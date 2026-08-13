package com.authmedemo.task;

// ===================== 实现思路 =====================
// 出生点强制锁定任务（每 tick 执行一次，始终启动，不看配置开关）
// 两层兜底：
//   ① 进服后前 30 秒（600 tick）每 5 tick 无条件 teleport 一次出生点
//      → 专治 NMS 覆盖 / 外力推 / 客户端作弊等"卡在半路"的情况
//   ② 全程每 tick 检查位置偏移，超过 0.3 格就传送
// settings.force-spawn-location 现在只控制日志提示，不控制任务启停
// 为什么要"无条件强传"？
//   玩家客户端手里看到的坐标不一定和服务端一致，无条件传送会强制同步坐标，
//   避免客户端"飘出去"又被服务端橡皮筋拉回的卡顿感。
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.cache.PlayerCache;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnLockTask implements Runnable {

    private static final long FORCE_PERIOD   = 5L;   // 无条件强传间隔（tick）
    private static final long FORCE_DURATION = 600L; // 无条件强传持续多久（tick，600=30秒）
    private static final double OFFSET_THRESHOLD = 0.3; // 超偏移阈值（格）

    private final PlayerCache cache;
    // 记录每个未登录玩家"已度过的tick数"，tick计数器，和进服时间无关（玩家可能中途登出再进）
    private final Map<UUID, Long> playerTickCounter = new ConcurrentHashMap<>();

    public SpawnLockTask(AuthMeDemo plugin) {
        this.cache = plugin.getPlayerCache();
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();

            // 已经登录 → 清计数器 + 放行
            if (cache.isLoggedIn(uuid)) {
                playerTickCounter.remove(uuid);
                continue;
            }

            long tick = playerTickCounter.merge(uuid, 0L, (old, zero) -> old + 1);

            World world = player.getWorld();
            Location spawn = world.getSpawnLocation();
            Location current = player.getLocation();

            boolean needTeleport = false;

            // 兜底①：进服后前30秒，每5 tick无条件传送一次（强制同步坐标）
            if (tick < FORCE_DURATION && tick % FORCE_PERIOD == 0L) {
                needTeleport = true;
            } else {
                // 兜底②：偏移超过阈值就传送
                double dx = Math.abs(current.getX() - spawn.getX());
                double dy = Math.abs(current.getY() - spawn.getY());
                double dz = Math.abs(current.getZ() - spawn.getZ());
                if (dx > OFFSET_THRESHOLD || dy > OFFSET_THRESHOLD || dz > OFFSET_THRESHOLD) {
                    needTeleport = true;
                }
            }

            if (needTeleport) {
                player.teleport(spawn);
            }
        }

        // 清理已经下线的玩家（防止内存泄露：玩家都下线了还保留计数器）
        playerTickCounter.keySet().removeIf(u -> {
            Player p = Bukkit.getPlayer(u);
            return p == null || !p.isOnline();
        });
    }
}

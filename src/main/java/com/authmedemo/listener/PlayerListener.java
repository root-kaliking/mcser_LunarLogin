package com.authmedemo.listener;

// ===================== 实现思路 =====================
// 玩家事件监听器：核心业务逻辑
// 【进服新增】
//   - PlayerJoinEvent 立刻同步传送到世界出生点（不管SpawnLockTask是否开启，先固定住）
//   - 查库结束后不再只发单行，而是立刻发一次注册/登录大框（配合PromptTask每N秒重复）
//   - 如果 settings.allow-registration=false 且 玩家未注册，立刻提示"注册已关闭"
// 【命令白名单新增】
//   - 注册关闭时，未注册玩家即使输 /register 也被拒（提示注册已关闭）
// 【新增事件拦截】
//   - 骑乘实体（上矿车/上猪等）、实体伤害（防止自残/PVP）、食用物品（防止吃东西）
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.cache.PlayerCache;
import com.authmedemo.database.DatabaseManager;
import com.authmedemo.model.PlayerAuth;
import com.authmedemo.security.SecurityManager;
import com.authmedemo.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import java.util.List;
import java.util.UUID;

public class PlayerListener implements Listener {

    private final AuthMeDemo plugin;
    private final DatabaseManager db;
    private final PlayerCache cache;
    private final SecurityManager security;

    public PlayerListener(AuthMeDemo plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        this.cache = plugin.getPlayerCache();
        this.security = plugin.getSecurityManager();
    }

    // ===================== 玩家进服：延迟1tick固定出生点 + 异步查库 =====================
    // 【为什么延迟1tick？两个致命坑：】
    //  1. 1.16.5 NMS 在 PlayerJoinEvent 回调完之后，还会再把玩家"放置"到客户端连接时的坐标
    //     → 我们在事件里的 teleport 会被 NMS 覆盖，等于没传
    //  2. 同一个 PlayerJoinEvent 上如果前面的插件（比如你日志里的 Arcade v1.51d）抛了未捕获异常，
    //     Bukkit 会直接中断事件链，后面我们的同步代码根本跑不到 → 没传送 + 没查库 + 没提示
    //  【解决方案】事件里只做最轻的缓存清理，然后 runTaskLater 延迟1 tick。
    //  这是 Bukkit 调度器，和事件链完全独立，前面插件再怎么炸都不影响我们执行。
    // ====================================================================================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        // 只做最轻的内存清理，不做任何IO和传送
        cache.clearPlayer(uuid);
        security.clearFailState(uuid);

        final String offlineUuidStr = uuid.toString();
        final String ip = getPlayerIp(player);

        // 延迟 1 tick 后执行：此时 NMS 已经放完玩家位置，且其他插件的异常不会干扰我们
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // 双重校验：玩家是否还在线（极端情况1tick内已经被踢出去了）
            if (!player.isOnline()) return;

            // 1. 强制传送到当前世界出生点（这次不会被 NMS 覆盖）
            Location spawn = player.getWorld().getSpawnLocation();
            player.teleport(spawn);

            // 2. 异步查库，查完立刻发一次"请登录"或"请注册"大框
            db.getAuthByUuid(offlineUuidStr, (PlayerAuth auth) -> {
                // 查库回调也是主线程，再校验一次玩家是否还在线
                if (!player.isOnline()) return;
                if (auth != null) {
                    // 已注册但未登录：缓存账号 + 立刻发"请登录"大框
                    cache.cacheAuth(uuid, auth);
                    db.updateLoginInfo(offlineUuidStr, ip, System.currentTimeMillis());
                    sendLines(player, MessageUtil.getList("prompt-login"));
                } else {
                    // 未注册：根据注册开关发不同的大框
                    if (plugin.isAllowRegistration()) {
                        sendLines(player, MessageUtil.getList("prompt-register"));
                    } else {
                        sendLines(player, MessageUtil.getList("registration-closed"));
                    }
                }
            });
        }, 1L);
    }

    // ===================== 玩家退出：清理 =====================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cache.clearPlayer(uuid);
        security.clearPlayer(uuid);
    }

    // ===================== 命令白名单（支持注册开关）=====================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (cache.isLoggedIn(uuid)) return;

        String msg = event.getMessage().toLowerCase();
        if (msg.startsWith("/")) msg = msg.substring(1);
        String cmdName = msg.split(" ")[0];

        // 1. 先判断是不是白名单命令
        boolean allowCmd = false;
        boolean isRegisterCmd = false;
        switch (cmdName) {
            case "login":
            case "l":
                allowCmd = true;
                break;
            case "register":
            case "reg":
                allowCmd = true;
                isRegisterCmd = true;
                break;
        }
        if (!allowCmd) {
            event.setCancelled(true);
            player.sendMessage(MessageUtil.get("not-logged-in-command"));
            return;
        }

        // 2. 注册命令额外检查：若管理员关了注册开关，不允许执行
        if (isRegisterCmd && !plugin.isAllowRegistration()) {
            event.setCancelled(true);
            sendLines(player, MessageUtil.getList("registration-closed"));
        }
    }

    // ===================== 移动拦截（即使被外力推，事件也cancel）=====================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!cache.isLoggedIn(player.getUniqueId())) {
            if (event.getFrom().getX() != event.getTo().getX()
                    || event.getFrom().getY() != event.getTo().getY()
                    || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setCancelled(true);
            }
        }
    }

    // ===================== 聊天拦截 =====================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!cache.isLoggedIn(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(MessageUtil.get("not-logged-in-chat"));
        }
    }

    // ===================== 其他动作拦截 =====================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event)     { cancelIfNotLoggedIn(event.getPlayer(), event, "not-logged-in-blocked"); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event)       { cancelIfNotLoggedIn(event.getPlayer(), event, "not-logged-in-blocked"); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event)       { cancelIfNotLoggedIn(event.getPlayer(), event, "not-logged-in-blocked"); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpenInventory(InventoryOpenEvent event) { if (event.getPlayer() instanceof Player) cancelIfNotLoggedIn((Player) event.getPlayer(), event, "not-logged-in-blocked"); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event)     { cancelIfNotLoggedIn(event.getPlayer(), event, "not-logged-in-blocked"); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickupItem(PlayerPickupItemEvent event) { cancelIfNotLoggedIn(event.getPlayer(), event, null); }

    // 【新增】拦截上矿车/上船/骑猪（防止玩家靠骑乘离开出生点）
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player) {
            cancelIfNotLoggedIn((Player) event.getEntered(), event, "not-logged-in-blocked");
        }
    }

    // 【新增】拦截受伤（包括PVP、自残、跌落、爆炸），防止未登录玩家靠自杀/卡BUG
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player p = (Player) event.getEntity();
            if (!cache.isLoggedIn(p.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    // 【新增】拦截饥饿度变化，未登录玩家不会饿也不会回血
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) {
            Player p = (Player) event.getEntity();
            if (!cache.isLoggedIn(p.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    // 【新增】拦截玩家使用物品（吃东西、喝药水、丢末影珍珠等）
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        cancelIfNotLoggedIn(event.getPlayer(), event, "not-logged-in-blocked");
    }

    // ===================== 辅助 =====================
    private void cancelIfNotLoggedIn(Player player, Cancellable event, String messageKey) {
        if (player == null || event == null) return;
        if (!cache.isLoggedIn(player.getUniqueId())) {
            event.setCancelled(true);
            if (messageKey != null) {
                player.sendMessage(MessageUtil.get(messageKey));
            }
        }
    }

    private void sendLines(Player player, List<String> lines) {
        for (String l : lines) player.sendMessage(l);
    }

    private String getPlayerIp(Player player) {
        try {
            if (player.getAddress() != null && player.getAddress().getAddress() != null) {
                return player.getAddress().getAddress().getHostAddress();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }
}

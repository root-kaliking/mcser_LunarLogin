package com.authmedemo.listener;

// ===================== 实现思路 =====================
// 玩家事件监听器：核心业务逻辑
// 【本次大量Bug修复——对齐AuthMe实现】
// 1. Join处理：先 prepareForJoin(UNKNOWN) → 延迟1tick teleport → 查库 → setRegistered/setNotRegistered
// 2. 命令白名单：
//    - 新增"命令太快"保护（进服800ms内禁输命令，对齐AuthMe的"You used a command too fast"）
//    - 命令命名空间归一化：/minecraft:login / authmedemo:reg / login → 只看最后一个冒号后的名字
//    - 白名单同时包含 plugin.yml 中所有别名（l、reg、cpw、changepw）
// 3. 新增 PlayerTeleportEvent 拦截：未登录玩家只能去出生点，其他插件/玩家传送全部取消
// 4. 新增 PlayerToggleFlightEvent 拦截：未登录玩家不许切换飞行
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.cache.PlayerCache;
import com.authmedemo.cache.PlayerCache.RegistrationStatus;
import com.authmedemo.database.DatabaseManager;
import com.authmedemo.model.PlayerAuth;
import com.authmedemo.security.SecurityManager;
import com.authmedemo.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public class PlayerListener implements Listener {

    private final AuthMeDemo plugin;
    private final DatabaseManager db;
    private final PlayerCache cache;
    private final SecurityManager security;

    // ===================== 允许未登录玩家使用的命令（含别名，命名空间归一化后匹配）=====================
    // 对应 plugin.yml 里的 commands 段 + aliases 全部展开
    private static final HashSet<String> ALLOWED_COMMANDS = new HashSet<>(Arrays.asList(
            "login", "l",                              // /login 别名
            "register", "reg",                         // /register 别名
            "changepassword", "cpw", "changepw",       // /changepassword 别名（理论上要登录才用，白名单放行不影响，命令内部已做已登录校验）
            "logout",                                   // /logout（已登录才用，但别拦命令，让命令自己拒）
            "adminauthme"                               // 管理员命令（内部有权限校验）
    ));

    // 哪些命令属于"登录/注册类"——只有这些在 UNKNOWN 状态下可以先放行，让用户输了不被"命令太快"或"加载中"阻止
    private static final HashSet<String> AUTH_COMMANDS = new HashSet<>(Arrays.asList(
            "login", "l", "register", "reg"
    ));

    public PlayerListener(AuthMeDemo plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        this.cache = plugin.getPlayerCache();
        this.security = plugin.getSecurityManager();
    }

    // ===================== 玩家进服：先UNKNOWN → 延迟1tick传送+查库 =====================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        // 1. 清理旧缓存（包含登录/注册/失败计数）
        cache.clearPlayer(uuid);
        security.clearFailState(uuid);

        // 2. 【关键】注册状态置为 UNKNOWN（查库空窗期中间态），并记录进服时间戳
        cache.prepareForJoin(uuid);

        final String offlineUuidStr = uuid.toString();
        final String ip = getPlayerIp(player);

        // 3. 延迟 1 tick 后执行（绕开 NMS 覆盖坐标 + 其他插件抛错打断事件链）
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            // 3a) 强制传送到当前世界出生点
            player.teleport(player.getWorld().getSpawnLocation());

            // 3b) 异步查库，查完后必须更新注册状态三态（setRegistered / setNotRegistered）
            db.getAuthByUuid(offlineUuidStr, (PlayerAuth auth) -> {
                if (!player.isOnline()) return;
                if (auth != null) {
                    // 已注册：缓存 + 更新登录信息 + 发登录大框
                    cache.setRegistered(uuid, auth);
                    db.updateLoginInfo(offlineUuidStr, ip, System.currentTimeMillis());
                    sendLines(player, MessageUtil.getList("prompt-login"));
                } else {
                    // 未注册：标记 NOT_REGISTERED + 根据注册开关发提示
                    cache.setNotRegistered(uuid);
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

    // ===================== 命令白名单（含命名空间归一化+命令太快保护）=====================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (cache.isLoggedIn(uuid)) return; // 已登录 → 完全放行

        // 【修复1】命令太快保护：进服 800ms 内禁输命令，玩家爆破/机器人
        if (cache.isCommandTooFast(uuid)) {
            event.setCancelled(true);
            player.sendMessage("§c§l你使用命令的速度太快！请重新连接服务器并稍等片刻再输入命令。");
            // AuthMe原版：严重的命令刷会踢玩家。demo版先只拦不踢。
            return;
        }

        // 【修复2】命名空间归一化
        // 用户可能输入：/login · /minecraft:login · /authmedemo:reg · /plugins:login
        // 统一去掉开头 "/"，然后取最后一个冒号 ":" 后面的字符串作为命令名
        String rawMsg = event.getMessage();
        String msg = (rawMsg != null && rawMsg.startsWith("/")) ? rawMsg.substring(1) : rawMsg;
        if (msg == null || msg.isEmpty()) {
            event.setCancelled(true);
            return;
        }
        // 取"空格前"的部分，兼容 /login 123 这种有参数的情况
        String firstToken = msg.split(" ")[0];
        // 取最后一个冒号后面的字符串 → 真正的命令名
        int lastColon = firstToken.lastIndexOf(':');
        String cmdName = (lastColon >= 0) ? firstToken.substring(lastColon + 1) : firstToken;
        cmdName = cmdName.toLowerCase();

        // 【查库空窗期保护】注册状态还是 UNKNOWN 时：
        //   - 只允许 /login /register 这两个 auth 类命令通过
        //   - 其它命令一律拦，免得我们判不准他是注册还是未注册（虽然我们会拦截所有非白名单，但这里多一层保险）
        RegistrationStatus status = cache.getRegistrationStatus(uuid);
        if (status == RegistrationStatus.UNKNOWN && !AUTH_COMMANDS.contains(cmdName)) {
            event.setCancelled(true);
            player.sendMessage("§e§l[系统] 账号数据加载中，请稍等一秒后再操作...");
            return;
        }

        // 白名单判定
        if (!ALLOWED_COMMANDS.contains(cmdName)) {
            event.setCancelled(true);
            player.sendMessage(MessageUtil.get("not-logged-in-command"));
            return;
        }

        // 注册命令：注册关闭时再兜底拦截
        boolean isRegisterCmd = "register".equals(cmdName) || "reg".equals(cmdName);
        if (isRegisterCmd && !plugin.isAllowRegistration()) {
            event.setCancelled(true);
            sendLines(player, MessageUtil.getList("registration-closed"));
        }
    }

    // ===================== 玩家传送事件（防其他插件把未登录玩家传走）=====================
    // 其他插件（Essentials/Multiverse/权限组）可能触发传送，直接cancel掉，目的地只允许等于世界出生点
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (cache.isLoggedIn(uuid)) return;

        Location to = event.getTo();
        if (to == null) return;

        World world = to.getWorld();
        if (world == null) {
            event.setCancelled(true);
            return;
        }
        Location spawn = world.getSpawnLocation();
        double dx = Math.abs(to.getX() - spawn.getX());
        double dy = Math.abs(to.getY() - spawn.getY());
        double dz = Math.abs(to.getZ() - spawn.getZ());
        // 目的地不等于出生点 → cancel（偏移 > 1格就算是别的插件传的，我们传的偏移是0）
        if (dx > 1.0 || dy > 1.0 || dz > 1.0) {
            event.setCancelled(true);
        }
    }

    // ===================== 切换飞行（防飞行权限把玩家带出出生点）=====================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player p = event.getPlayer();
        if (!cache.isLoggedIn(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ===================== 移动拦截 =====================
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
            // 异步事件里 sendMessage 要丢回主线程（Bukkit API要求）
            Bukkit.getScheduler().runTask(plugin, () ->
                    player.sendMessage(MessageUtil.get("not-logged-in-chat")));
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

    // 【新增】拦截上矿车/上船/骑猪
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player) {
            cancelIfNotLoggedIn((Player) event.getEntered(), event, "not-logged-in-blocked");
        }
    }

    // 【新增】拦截受伤
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player p = (Player) event.getEntity();
            if (!cache.isLoggedIn(p.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    // 【新增】拦截饥饿度变化
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

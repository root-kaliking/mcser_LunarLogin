package com.authmedemo.listener;

// ===================== 实现思路 =====================
// 玩家事件监听器：核心业务逻辑所在
// 对齐AuthMeReloaded行为：
// 【进服流程】
//   PlayerJoinEvent触发 → 异步查库：
//     → 未注册(没记录)：冻结玩家，提示/register
//     → 已注册但未登录：冻结玩家，提示/login
//     → 注意：玩家UUID不会变，用UUID做查库和缓存key
//
// 【未登录拦截】（以下事件如果玩家未登录全部取消）：
//   - PlayerMoveEvent：禁止移动（AuthMe甚至会把玩家传送回出生点）
//   - AsyncPlayerChatEvent：禁止聊天
//   - PlayerCommandPreprocessEvent：只允许/login和/register（含别名）
//   - PlayerInteractEvent：禁止点击方块/物品（开箱等）
//   - BlockBreakEvent/BlockPlaceEvent：禁止破坏/放置
//   - PlayerDropItemEvent/InventoryOpenEvent：禁止丢东西/开背包
//
// 【退出清理】PlayerQuitEvent → 清登录状态+清缓存+清失败计数
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.cache.PlayerCache;
import com.authmedemo.database.DatabaseManager;
import com.authmedemo.model.PlayerAuth;
import com.authmedemo.security.SecurityManager;
import com.authmedemo.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;

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

    // ===================== 玩家进服：核心入口逻辑 =====================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 1. 先清理该UUID可能残留的旧缓存（比如服务器重启前没清干净）
        cache.clearPlayer(uuid);
        security.clearFailState(uuid);

        // 2. 获取离线UUID字符串（Bukkit在online-mode=false时会自动算好）
        String offlineUuidStr = uuid.toString();

        // 3. 【异步】查数据库判断玩家是否注册过
        db.getAuthByUuid(offlineUuidStr, (PlayerAuth auth) -> {
            // 此回调在主线程执行（DatabaseManager会切回主线程）
            if (auth != null) {
                // ========== 情况B：账号已注册，但本次进服还未登录 ==========
                // 更新缓存中的账号数据
                cache.cacheAuth(uuid, auth);
                // 更新玩家IP（下次登录信息用，但这里不急，异步更新即可）
                db.updateLoginInfo(offlineUuidStr, getPlayerIp(player), System.currentTimeMillis());
                // 强制提示登录
                player.sendMessage(MessageUtil.get("join-registered"));
            } else {
                // ========== 情况A：账号未注册 ==========
                // 缓存里也标记为空，但不加入authCache（isRegistered会返回false）
                player.sendMessage(MessageUtil.get("join-unregistered"));
            }
            // 情况C：已登录？进服时内存缓存肯定是空的，所以没有"已登录直接放行"的情况
            // AuthMe设计：每次进服都必须重新登录（防止盗号者复用会话）
        });
    }

    // ===================== 玩家退出：清理内存状态 =====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // 清除登录状态、账号缓存、失败计数（防止内存泄漏）
        cache.clearPlayer(uuid);
        security.clearPlayer(uuid);
    }

    // ===================== 未登录状态下的各种拦截 =====================
    // 以下事件统一判断：isLoggedIn(uuid) == false → cancel + 提示

    /**
     * 拦截玩家移动（AuthMe行为：甚至会把玩家拉回原地）
     * 注意：PlayerMoveEvent触发非常频繁（每tick可能多次），所以判断要快（内存Set，O(1)）
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!cache.isLoggedIn(player.getUniqueId())) {
            // 只取消位置变化，不取消朝向变化（让玩家可以转头看提示）
            if (event.getFrom().getX() != event.getTo().getX()
                    || event.getFrom().getY() != event.getTo().getY()
                    || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setCancelled(true);
                // 不每tick都发消息，刷屏体验差。这里只在需要时提示
                // 实际AuthMe是通过定时重复提示登录，这里简化
            }
        }
    }

    /**
     * 拦截未登录玩家聊天
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!cache.isLoggedIn(player.getUniqueId())) {
            event.setCancelled(true);
            // 注意：这是Async事件，但Bukkit API允许发消息（sendMessage是线程安全的）
            player.sendMessage(MessageUtil.get("not-logged-in-chat"));
        }
    }

    /**
     * 拦截命令：未登录时仅允许 /login /register /changepassword /logout 及别名
     * 【重要】：PlayerCommandPreprocessEvent的getMessage()是包含斜杠的原始命令
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (cache.isLoggedIn(uuid)) {
            return; // 已登录放行
        }

        // 解析命令（去掉开头斜杠，转小写）
        String message = event.getMessage().toLowerCase();
        if (message.startsWith("/")) {
            message = message.substring(1);
        }
        String cmdName = message.split(" ")[0]; // 取第一个空格前的部分

        // 白名单：login / l / register / reg 及它们的别名
        boolean isAllowed = false;
        switch (cmdName) {
            case "login":
            case "l":
            case "register":
            case "reg":
                isAllowed = true;
                break;
        }

        if (!isAllowed) {
            event.setCancelled(true);
            player.sendMessage(MessageUtil.get("not-logged-in-command"));
        }
    }

    /**
     * 拦截交互（右键点击方块/物品等，包括开箱子、开按钮、开矿车）
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        cancelIfNotLoggedIn(event.getPlayer(), event, "not-logged-in-blocked");
    }

    /**
     * 拦截破坏方块
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        cancelIfNotLoggedIn(event.getPlayer(), event, "not-logged-in-blocked");
    }

    /**
     * 拦截放置方块
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        cancelIfNotLoggedIn(event.getPlayer(), event, "not-logged-in-blocked");
    }

    /**
     * 拦截打开背包/箱子
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player) {
            cancelIfNotLoggedIn((Player) event.getPlayer(), event, "not-logged-in-blocked");
        }
    }

    /**
     * 拦截丢物品
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        cancelIfNotLoggedIn(event.getPlayer(), event, "not-logged-in-blocked");
    }

    /**
     * 拦截拾取物品（未登录不能捡东西）
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        cancelIfNotLoggedIn(event.getPlayer(), event, null);
    }

    // ===================== 辅助方法 =====================

    /**
     * 通用拦截辅助：玩家未登录就取消事件+发消息
     *
     * @param messageKey 消息配置key；传null表示静默取消（不发消息）
     */
    private void cancelIfNotLoggedIn(Player player, Cancellable event, String messageKey) {
        if (player == null || event == null) return;
        if (!cache.isLoggedIn(player.getUniqueId())) {
            event.setCancelled(true);
            if (messageKey != null) {
                player.sendMessage(MessageUtil.get(messageKey));
            }
        }
    }

    /**
     * 获取玩家IP字符串（容错：避免getAddress()返回null时报NPE）
     */
    private String getPlayerIp(Player player) {
        try {
            if (player.getAddress() != null && player.getAddress().getAddress() != null) {
                return player.getAddress().getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }
}

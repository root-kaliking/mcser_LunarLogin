package com.authmedemo;

// ===================== 实现思路 =====================
// 插件主类：所有管理器的装配入口
// 新增3个settings配置：allow-registration、force-spawn-location、prompt-repeat-interval
// onEnable最后启动两个Bukkit调度任务：
//   ① PromptTask  - 每隔 N 秒，给未登录玩家循环发送注册/登录大框提示
//   ② SpawnLockTask- 每 tick 把未登录玩家强制拉回世界出生点（防活塞/水流/爆炸推动）
// onDisable 时 Bukkit 会自动清理它创建的任务，无需手动cancel
// ====================================================

import com.authmedemo.cache.PlayerCache;
import com.authmedemo.command.*;
import com.authmedemo.database.DatabaseManager;
import com.authmedemo.listener.PlayerListener;
import com.authmedemo.security.SecurityManager;
import com.authmedemo.task.PromptTask;
import com.authmedemo.task.SpawnLockTask;
import com.authmedemo.util.MessageUtil;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class AuthMeDemo extends JavaPlugin {

    private static AuthMeDemo instance;

    // ===== 各管理器实例 =====
    private DatabaseManager databaseManager;
    private PlayerCache playerCache;
    private SecurityManager securityManager;

    // ===== 配置参数缓存 =====
    private int minPasswordLength;
    private int maxLoginFails;
    private int cooldownSeconds;

    // ===== 【新增】settings 配置 =====
    private boolean allowRegistration;      // 是否允许玩家自行注册
    private boolean forceSpawnLocation;     // 是否强制锁定到出生点
    private int promptRepeatInterval;       // 重复提示间隔（秒）

    // ===== 调度任务ID引用（onDisable时无需手动cancel，Bukkit会清理）=====
    private BukkitTask promptTask;
    private BukkitTask spawnLockTask;

    @Override
    public void onEnable() {
        instance = this;
        long startTime = System.currentTimeMillis();

        saveDefaultConfig();
        reloadConfig();

        // ===== 读取所有配置参数 =====
        minPasswordLength     = getConfig().getInt("security.min-password-length", 4);
        maxLoginFails         = getConfig().getInt("security.max-login-fails", 5);
        cooldownSeconds       = getConfig().getInt("security.login-cooldown-seconds", 60);
        allowRegistration     = getConfig().getBoolean("settings.allow-registration", true);
        forceSpawnLocation    = getConfig().getBoolean("settings.force-spawn-location", true);
        promptRepeatInterval  = getConfig().getInt("settings.prompt-repeat-interval", 3);
        // 提示间隔保护：防止配成 0 导致每 tick 刷屏
        if (promptRepeatInterval < 1) promptRepeatInterval = 1;

        MessageUtil.init(getConfig());

        String dbFilename = getConfig().getString("database.filename", "authme.db");
        databaseManager = new DatabaseManager(this, getDataFolder(), dbFilename);
        if (!databaseManager.init()) {
            getLogger().severe("数据库初始化失败！插件将无法正常工作。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        playerCache = new PlayerCache();
        securityManager = new SecurityManager(maxLoginFails, cooldownSeconds);

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        try {
            getCommand("register").setExecutor(new RegisterCommand(this));
            getCommand("login").setExecutor(new LoginCommand(this));
            getCommand("changepassword").setExecutor(new ChangePasswordCommand(this));
            getCommand("logout").setExecutor(new LogoutCommand(this));
            getCommand("adminauthme").setExecutor(new AdminAuthMeCommand(this));
        } catch (NullPointerException e) {
            getLogger().severe("注册命令失败！请检查plugin.yml中的命令名是否正确。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ===== 【新增】启动定时调度任务 =====
        // ① 重复提示任务：间隔 N 秒 * 20 tick，延迟 1 秒后第一次执行
        long intervalTicks = (long) promptRepeatInterval * 20L;
        promptTask = getServer().getScheduler().runTaskTimer(this, new PromptTask(this), 20L, intervalTicks);
        getLogger().info("已启动登录/注册重复提示任务，间隔 " + promptRepeatInterval + " 秒");

        // ② 出生点强制锁定任务：每 tick 拉回一次（如果配置开启）
        if (forceSpawnLocation) {
            spawnLockTask = getServer().getScheduler().runTaskTimer(this, new SpawnLockTask(this), 0L, 1L);
            getLogger().info("已启用出生点强制锁定（未登录玩家每 tick 被拉回出生点）");
        } else {
            getLogger().info("出生点强制锁定未启用，仅通过事件取消移动");
        }

        long cost = System.currentTimeMillis() - startTime;
        getLogger().info("AuthMeDemo 插件已启用！耗时 " + cost + "ms");
        getLogger().info("注册功能开关: " + (allowRegistration ? "开启" : "关闭")
                + " | 出生点锁定: " + (forceSpawnLocation ? "开启" : "关闭")
                + " | 提示间隔: " + promptRepeatInterval + "s");
    }

    @Override
    public void onDisable() {
        getLogger().info("AuthMeDemo 插件正在关闭...");
        if (playerCache != null) {
            playerCache.clearAll();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        instance = null;
    }

    // ===================== Getters =====================
    public static AuthMeDemo getInstance() {
        return instance;
    }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public PlayerCache getPlayerCache()         { return playerCache; }
    public SecurityManager getSecurityManager() { return securityManager; }
    public int getMinPasswordLength()           { return minPasswordLength; }

    // 新增 settings 配置对外暴露
    public boolean isAllowRegistration()  { return allowRegistration; }
    public boolean isForceSpawnLocation() { return forceSpawnLocation; }
    public int getPromptRepeatInterval()  { return promptRepeatInterval; }
}

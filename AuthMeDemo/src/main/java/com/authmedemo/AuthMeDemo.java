package com.authmedemo;

// ===================== 实现思路 =====================
// 插件主类：所有管理器的装配入口
// 生命周期：
//   onEnable()  → 加载配置 → 初始化数据库 → 初始化管理器 → 注册事件/命令
//   onDisable() → 关闭数据库 → 清理缓存
// 所有管理器单例通过此主类暴露，其他模块通过AuthMeDemo.getInstance()获取
// ====================================================

import com.authmedemo.cache.PlayerCache;
import com.authmedemo.command.*;
import com.authmedemo.database.DatabaseManager;
import com.authmedemo.listener.PlayerListener;
import com.authmedemo.security.SecurityManager;
import com.authmedemo.util.MessageUtil;
import org.bukkit.plugin.java.JavaPlugin;

public class AuthMeDemo extends JavaPlugin {

    // 单例引用（插件实例）
    private static AuthMeDemo instance;

    // ===== 各管理器实例 =====
    private DatabaseManager databaseManager;   // SQLite数据库
    private PlayerCache playerCache;           // 内存缓存
    private SecurityManager securityManager;   // 安全限制（失败计数/冷却）

    // ===== 配置参数缓存 =====
    private int minPasswordLength;
    private int maxLoginFails;
    private int cooldownSeconds;

    @Override
    public void onEnable() {
        instance = this;
        long startTime = System.currentTimeMillis();

        // 1. 保存默认配置（如果config.yml不存在则从jar内resources拷贝）
        saveDefaultConfig();
        reloadConfig(); // 确保加载最新

        // 2. 从config读取参数缓存
        minPasswordLength = getConfig().getInt("security.min-password-length", 4);
        maxLoginFails = getConfig().getInt("security.max-login-fails", 5);
        cooldownSeconds = getConfig().getInt("security.login-cooldown-seconds", 60);

        // 3. 初始化消息工具类（注入config引用）
        MessageUtil.init(getConfig());

        // 4. 初始化数据库管理器
        String dbFilename = getConfig().getString("database.filename", "authme.db");
        databaseManager = new DatabaseManager(this, getDataFolder(), dbFilename);
        if (!databaseManager.init()) {
            getLogger().severe("数据库初始化失败！插件将无法正常工作。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 5. 初始化缓存和安全管理器
        playerCache = new PlayerCache();
        securityManager = new SecurityManager(maxLoginFails, cooldownSeconds);

        // 6. 注册事件监听器
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // 7. 注册命令处理器（plugin.yml里注册的命令名必须和这里setExecutor的name一致）
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

        long cost = System.currentTimeMillis() - startTime;
        getLogger().info("AuthMeDemo 插件已启用！耗时 " + cost + "ms");
        getLogger().info("密码最小长度: " + minPasswordLength +
                " 最大失败次数: " + maxLoginFails +
                " 冷却时间: " + cooldownSeconds + "s");
    }

    @Override
    public void onDisable() {
        getLogger().info("AuthMeDemo 插件正在关闭...");
        // 清理缓存
        if (playerCache != null) {
            playerCache.clearAll();
        }
        // 关闭数据库连接
        if (databaseManager != null) {
            databaseManager.close();
        }
        instance = null;
    }

    // ===================== 单例Getters（供其他模块使用）=====================

    public static AuthMeDemo getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public PlayerCache getPlayerCache() {
        return playerCache;
    }

    public SecurityManager getSecurityManager() {
        return securityManager;
    }

    public int getMinPasswordLength() {
        return minPasswordLength;
    }
}

package com.authmedemo.command;

// ===================== 实现思路 =====================
// /login <password> 登录命令
// 流程：
// 1. 前置检查：必须是玩家 + 参数(1个) + 未登录 + 已注册
// 2. 冷却检查：处于冷却期直接拒，不做密码校验（防止爆破）
// 3. 从缓存取账号数据（PlayerAuth里有密码哈希）
// 4. BCrypt.checkpw(明文输入, 存储哈希) 比较密码
// 5. 密码错 → 失败计数+1 → 达到上限开启冷却
// 6. 密码对 → 清失败计数 + 标记登录 + 异步更新登录IP/时间
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.cache.PlayerCache;
import com.authmedemo.database.DatabaseManager;
import com.authmedemo.model.PlayerAuth;
import com.authmedemo.security.PasswordSecurity;
import com.authmedemo.security.SecurityManager;
import com.authmedemo.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public class LoginCommand implements CommandExecutor {

    private final AuthMeDemo plugin;
    private final DatabaseManager db;
    private final PlayerCache cache;
    private final SecurityManager security;

    public LoginCommand(AuthMeDemo plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        this.cache = plugin.getPlayerCache();
        this.security = plugin.getSecurityManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家可以使用登录命令！");
            return true;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        // 1. 已登录就不允许重复登录
        if (cache.isLoggedIn(uuid)) {
            player.sendMessage(MessageUtil.get("login-already"));
            return true;
        }

        // 2. 参数校验：必须一个参数（密码）
        if (args.length != 1) {
            player.sendMessage(MessageUtil.get("login-usage"));
            return true;
        }

        String inputPassword = args[0];

        // 3. 先检查是否已注册（缓存未命中 → 异步查库后重试）
        PlayerAuth auth = cache.getCachedAuth(uuid);
        if (auth == null) {
            // 缓存没命中（可能是PlayerJoinEvent的异步查库还没回来），主动查一次
            db.getAuthByUuid(uuid.toString(), (PlayerAuth dbAuth) -> {
                if (dbAuth == null) {
                    // 数据库里也没有 → 没注册。根据注册开关提示不同内容
                    if (plugin.isAllowRegistration()) {
                        sendLines(player, MessageUtil.getList("prompt-register"));
                    } else {
                        sendLines(player, MessageUtil.getList("registration-closed"));
                    }
                } else {
                    // 查到了，缓存起来，然后执行真正的登录校验
                    cache.cacheAuth(uuid, dbAuth);
                    doLogin(player, uuid, dbAuth, inputPassword);
                }
            });
            return true;
        }

        // 4. 缓存命中，直接登录校验
        doLogin(player, uuid, auth, inputPassword);
        return true;
    }

    /**
     * 真正的登录校验逻辑（抽出来避免重复写）
     */
    private void doLogin(Player player, UUID uuid, PlayerAuth auth, String inputPassword) {
        // ===== 冷却检查（重要！冷却期内不做密码校验，防止暴力破解）=====
        long remainingCooldown = security.getRemainingCooldownSeconds(uuid);
        if (remainingCooldown > 0) {
            player.sendMessage(MessageUtil.get("login-cooldown",
                    "{seconds}", String.valueOf(remainingCooldown)));
            return;
        }

        // ===== BCrypt密码比对 =====
        boolean correct = PasswordSecurity.checkPassword(inputPassword, auth.getPasswordHash());
        if (!correct) {
            // 密码错误：记录失败次数
            boolean reachedMax = security.recordLoginFail(uuid);
            if (reachedMax) {
                // 达到上限，发送冷却提示
                long cooldown = security.getRemainingCooldownSeconds(uuid);
                player.sendMessage(MessageUtil.get("login-too-many-fails",
                        "{seconds}", String.valueOf(cooldown)));
            } else {
                player.sendMessage(MessageUtil.get("login-failed"));
            }
            return;
        }

        // ===== 登录成功！=====
        // 1. 清除失败计数和冷却
        security.clearFailState(uuid);
        // 2. 标记已登录（事件拦截器将放行所有动作）
        cache.setLoggedIn(uuid);
        // 3. 异步更新登录IP和时间（无需回调）
        db.updateLoginInfo(auth.getOfflineUuid(), getPlayerIp(player), System.currentTimeMillis());
        // 4. 提示成功：发送多行登录成功大框
        sendLines(player, MessageUtil.getList("login-success", "{player}", player.getName()));
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

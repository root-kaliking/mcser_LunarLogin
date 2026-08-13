package com.authmedemo.command;

// ===================== 实现思路 =====================
// /register <password> <confirmPassword> 注册命令
// 流程：
// 1. 前置检查：必须是玩家执行 + 参数个数(2个) + 未注册过 + 未登录
// 2. 校验两次密码一致 + 密码长度满足最小
// 3. BCrypt加密密码（密码绝不会明文进入数据库！）
// 4. 【异步】插入数据库 → 成功后：更新缓存+标记登录+清失败计数+发成功消息
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

public class RegisterCommand implements CommandExecutor {

    private final AuthMeDemo plugin;
    private final DatabaseManager db;
    private final PlayerCache cache;
    private final SecurityManager security;
    private final int minPwdLen;

    public RegisterCommand(AuthMeDemo plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        this.cache = plugin.getPlayerCache();
        this.security = plugin.getSecurityManager();
        this.minPwdLen = plugin.getMinPasswordLength();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // ===== 1. 只能玩家执行 =====
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家可以使用注册命令！");
            return true;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        // ===== 【新增】先检查注册总开关：管理员已关闭注册 =====
        if (!plugin.isAllowRegistration()) {
            sendLines(player, MessageUtil.getList("registration-closed"));
            return true;
        }

        // ===== 2. 已经登录了，不让重复注册 =====
        if (cache.isLoggedIn(uuid)) {
            player.sendMessage(MessageUtil.get("already-logged-in"));
            return true;
        }

        // ===== 3. 参数校验：必须两个参数（密码+确认密码）=====
        if (args.length != 2) {
            player.sendMessage(MessageUtil.get("register-usage"));
            return true;
        }

        String password = args[0];
        String confirmPwd = args[1];

        // ===== 4. 密码长度校验 =====
        if (!security.isPasswordLongEnough(password, minPwdLen)) {
            player.sendMessage(MessageUtil.get("register-password-too-short",
                    "{min}", String.valueOf(minPwdLen)));
            return true;
        }

        // ===== 5. 两次密码比对 =====
        if (!password.equals(confirmPwd)) {
            player.sendMessage(MessageUtil.get("register-password-mismatch"));
            return true;
        }

        // ===== 6. 防重复注册：查缓存看是否已注册 =====
        if (cache.isRegistered(uuid)) {
            player.sendMessage(MessageUtil.get("register-already"));
            return true;
        }

        // ===== 7. 准备PlayerAuth对象 =====
        String realName = player.getName();
        String lowerName = realName.toLowerCase();
        String offlineUuidStr = uuid.toString();
        // 【核心安全】BCrypt哈希密码后才存数据库
        String hashedPwd = PasswordSecurity.hashPassword(password);
        String ip = getPlayerIp(player);
        long now = System.currentTimeMillis();
        PlayerAuth newAuth = new PlayerAuth(realName, lowerName, offlineUuidStr, hashedPwd, ip, now);

        // ===== 8. 【异步】写入数据库 =====
        db.insertAuth(newAuth, (Boolean success) -> {
            // 回调在主线程
            if (success) {
                // 注册成功：更新缓存 + 标记登录 + 清失败计数
                cache.cacheAuth(uuid, newAuth);
                cache.setLoggedIn(uuid);
                security.clearFailState(uuid);
                // 发送多行"注册成功"大框
                sendLines(player, MessageUtil.getList("register-success"));
            } else {
                // 插入失败：可能是并发注册导致冲突，兜底提示
                player.sendMessage(MessageUtil.get("database-error"));
                // 失败后再查一次看是不是已经注册了
                db.getAuthByUuid(offlineUuidStr, (PlayerAuth existing) -> {
                    if (existing != null) {
                        cache.cacheAuth(uuid, existing);
                        player.sendMessage(MessageUtil.get("register-already"));
                    }
                });
            }
        });

        return true;
    }

    // 辅助：发送多行消息
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

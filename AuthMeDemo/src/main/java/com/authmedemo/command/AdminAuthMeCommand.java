package com.authmedemo.command;

// ===================== 实现思路 =====================
// /adminauthme setpassword <玩家名> <新密码> 管理员强制改密码
// 流程：
// 1. 权限检查：需要 authme.admin 权限（plugin.yml里配置了permission节点）
// 2. 子命令解析：目前只支持 setpassword 子命令
// 3. 新密码长度校验
// 4. 【异步】按玩家名查库 → 查到了就把密码哈希更新 → 踢玩家下线（要求重新登录）
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.database.DatabaseManager;
import com.authmedemo.model.PlayerAuth;
import com.authmedemo.security.PasswordSecurity;
import com.authmedemo.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AdminAuthMeCommand implements CommandExecutor {

    private final AuthMeDemo plugin;
    private final DatabaseManager db;
    private final int minPwdLen;

    public AdminAuthMeCommand(AuthMeDemo plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        this.minPwdLen = plugin.getMinPasswordLength();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // 权限检查（plugin.yml已经配置permission-message，这里再兜底一次）
        if (!sender.hasPermission("authme.admin")) {
            sender.sendMessage("§c你没有权限使用此命令！");
            return true;
        }

        // 必须2个以上参数：子命令 + 玩家名 + 新密码
        if (args.length < 3) {
            sender.sendMessage(MessageUtil.get("admin-usage"));
            return true;
        }

        String subCmd = args[0].toLowerCase();
        String targetName = args[1];
        String newPassword = args[2];

        // 目前仅支持 setpassword 子命令
        if (!"setpassword".equals(subCmd)) {
            sender.sendMessage(MessageUtil.get("admin-usage"));
            return true;
        }

        // 新密码长度校验
        if (!plugin.getSecurityManager().isPasswordLongEnough(newPassword, minPwdLen)) {
            sender.sendMessage(MessageUtil.get("admin-password-too-short",
                    "{min}", String.valueOf(minPwdLen)));
            return true;
        }

        // 【异步】按玩家名查账号（管理员不一定有目标玩家的UUID）
        db.getAuthByName(targetName, (PlayerAuth auth) -> {
            // 回到主线程
            if (auth == null) {
                sender.sendMessage(MessageUtil.get("admin-player-not-found",
                        "{player}", targetName));
                return;
            }

            // 【核心安全】新密码用BCrypt加密
            String newHash = PasswordSecurity.hashPassword(newPassword);

            // 【异步】更新数据库
            db.updatePassword(auth.getOfflineUuid(), newHash, (Boolean success) -> {
                if (success) {
                    sender.sendMessage(MessageUtil.get("admin-setpassword-success",
                            "{player}", auth.getRealName()));

                    // 如果目标玩家当前在线 → 踢下线（强制重新登录新密码）
                    Player onlineTarget = Bukkit.getPlayer(auth.getLowerName());
                    if (onlineTarget != null && onlineTarget.isOnline()) {
                        // 同时清掉该玩家的登录缓存
                        plugin.getPlayerCache().clearPlayer(onlineTarget.getUniqueId());
                        plugin.getSecurityManager().clearPlayer(onlineTarget.getUniqueId());
                        onlineTarget.kickPlayer(MessageUtil.get("kick-for-vulnerability"));
                    }
                } else {
                    sender.sendMessage(MessageUtil.get("database-error"));
                }
            });
        });

        return true;
    }
}

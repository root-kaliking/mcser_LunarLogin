package com.authmedemo.command;

// ===================== 实现思路 =====================
// /changepassword <旧密码> <新密码> 修改密码
// 流程：
// 1. 必须玩家执行 + 必须已登录（防止未登录的人乱试）
// 2. 旧密码通过BCrypt校验（防篡改）
// 3. 新密码满足最小长度
// 4. 【异步】更新数据库密码哈希 → 成功后更新缓存
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

import java.util.UUID;

public class ChangePasswordCommand implements CommandExecutor {

    private final AuthMeDemo plugin;
    private final DatabaseManager db;
    private final PlayerCache cache;
    private final int minPwdLen;

    public ChangePasswordCommand(AuthMeDemo plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
        this.cache = plugin.getPlayerCache();
        this.minPwdLen = plugin.getMinPasswordLength();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家可以使用此命令！");
            return true;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        // 1. 必须已登录
        if (!cache.isLoggedIn(uuid)) {
            player.sendMessage(MessageUtil.get("changepassword-not-logged-in"));
            return true;
        }

        // 2. 参数：旧密码 + 新密码
        if (args.length != 2) {
            player.sendMessage(MessageUtil.get("changepassword-usage"));
            return true;
        }
        String oldPwd = args[0];
        String newPwd = args[1];

        // 3. 新密码长度校验
        if (!plugin.getSecurityManager().isPasswordLongEnough(newPwd, minPwdLen)) {
            player.sendMessage(MessageUtil.get("changepassword-password-too-short",
                    "{min}", String.valueOf(minPwdLen)));
            return true;
        }

        // 4. 从缓存拿账号（已登录肯定有，不用查库）
        PlayerAuth auth = cache.getCachedAuth(uuid);
        if (auth == null) {
            player.sendMessage(MessageUtil.get("database-error"));
            return true;
        }

        // 5. 校验旧密码是否正确
        if (!PasswordSecurity.checkPassword(oldPwd, auth.getPasswordHash())) {
            player.sendMessage(MessageUtil.get("changepassword-old-wrong"));
            return true;
        }

        // 6. 【核心安全】新密码用BCrypt加密
        String newHash = PasswordSecurity.hashPassword(newPwd);

        // 7. 【异步】更新数据库
        db.updatePassword(auth.getOfflineUuid(), newHash, (Boolean success) -> {
            if (success) {
                // 更新缓存中的哈希
                auth.setPasswordHash(newHash);
                cache.cacheAuth(uuid, auth);
                player.sendMessage(MessageUtil.get("changepassword-success"));
            } else {
                player.sendMessage(MessageUtil.get("database-error"));
            }
        });

        return true;
    }
}

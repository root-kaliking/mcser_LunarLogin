package com.authmedemo.command;

// ===================== 实现思路 =====================
// /logout 手动登出
// 逻辑简单：清除玩家已登录状态（事件监听器会重新冻结玩家）
// 注意：不删除账号缓存，也不清数据库（登出不等于删号）
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.cache.PlayerCache;
import com.authmedemo.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class LogoutCommand implements CommandExecutor {

    private final PlayerCache cache;

    public LogoutCommand(AuthMeDemo plugin) {
        this.cache = plugin.getPlayerCache();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家可以使用此命令！");
            return true;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        if (!cache.isLoggedIn(uuid)) {
            // 还没登录
            player.sendMessage(MessageUtil.get("logout-not-logged-in"));
            return true;
        }

        // 清除登录状态（事件监听器会立即冻结玩家动作）
        cache.setLoggedOut(uuid);
        player.sendMessage(MessageUtil.get("logout-success"));
        // 提示重新登录
        player.sendMessage(MessageUtil.get("join-registered"));
        return true;
    }
}

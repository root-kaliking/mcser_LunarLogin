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

import java.util.List;
import java.util.UUID;

public class LogoutCommand implements CommandExecutor {

    private final PlayerCache cache;
    // 新增主类引用用于取配置开关
    private final AuthMeDemo plugin;

    public LogoutCommand(AuthMeDemo plugin) {
        this.plugin = plugin;
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
        // 登出后立刻再发一次登录提示大框（PromptTask也会重复发，保证视觉强制）
        sendLines(player, MessageUtil.getList("prompt-login"));
        return true;
    }

    private void sendLines(Player player, List<String> lines) {
        for (String l : lines) player.sendMessage(l);
    }
}

package com.authmedemo.task;

// ===================== 实现思路 =====================
// 周期性重复提示任务（Bukkit Runnable）
// 给所有在线但未登录的玩家，发送"请注册"或"请登录"的多行大框提示
// 注册开关关闭时，未注册玩家改发"注册已关闭"提示
// 玩家状态缓存判断逻辑：
//   PlayerCache.isRegistered(uuid) == true  → 已注册但未登录 → prompt-login
//   PlayerCache.isRegistered(uuid) == false → 未注册           → prompt-register 或 registration-closed
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.cache.PlayerCache;
import com.authmedemo.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

public class PromptTask implements Runnable {

    private final AuthMeDemo plugin;
    private final PlayerCache cache;
    // 是否允许注册（来自config的settings.allow-registration）
    private final boolean allowRegistration;

    public PromptTask(AuthMeDemo plugin) {
        this.plugin = plugin;
        this.cache = plugin.getPlayerCache();
        this.allowRegistration = plugin.isAllowRegistration();
    }

    @Override
    public void run() {
        // 遍历所有在线玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            // 已经登录 → 跳过
            if (cache.isLoggedIn(player.getUniqueId())) {
                continue;
            }

            // 根据是否已注册发送不同的重复提示
            List<String> lines;
            if (cache.isRegistered(player.getUniqueId())) {
                // 情况1：已注册但没登录 → 提示登录命令
                lines = MessageUtil.getList("prompt-login");
            } else if (allowRegistration) {
                // 情况2：没注册且允许注册 → 提示注册命令
                lines = MessageUtil.getList("prompt-register");
            } else {
                // 情况3：没注册且管理员关闭了注册 → 提示联系管理员
                lines = MessageUtil.getList("registration-closed");
            }

            // 逐行发送给玩家（多行消息形成一个大框框，视觉上更强制）
            for (String line : lines) {
                player.sendMessage(line);
            }
        }
    }
}

package com.authmedemo.task;

// ===================== 实现思路 =====================
// 周期性重复提示任务（Bukkit Runnable）
// 【修复——对齐AuthMe】
//  1. allowRegistration 不再从构造函数快照，每次 run 都实时读 plugin.isAllowRegistration()
//     → 管理员 /adminauthme reload （或以后加 reload）改了开关立刻生效，不用重启
//  2. 根据 PlayerCache 三态 RegistrationStatus 发对应提示：
//     - UNKNOWN        → 数据加载中中间提示（1次即可，避免刷屏，但我们是重复任务，这里发短提示）
//     - REGISTERED     → 已注册未登录 → prompt-login
//     - NOT_REGISTERED → 未注册 + allowRegistration=true → prompt-register
//     - NOT_REGISTERED → 未注册 + allowRegistration=false → registration-closed
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.cache.PlayerCache;
import com.authmedemo.cache.PlayerCache.RegistrationStatus;
import com.authmedemo.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;

public class PromptTask implements Runnable {

    private final AuthMeDemo plugin;
    private final PlayerCache cache;
    // allowRegistration → 不再缓存，每次 run 实时读 plugin

    public PromptTask(AuthMeDemo plugin) {
        this.plugin = plugin;
        this.cache = plugin.getPlayerCache();
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // 已经登录 → 跳过
            if (cache.isLoggedIn(player.getUniqueId())) {
                continue;
            }

            RegistrationStatus status = cache.getRegistrationStatus(player.getUniqueId());
            List<String> lines;

            if (status == RegistrationStatus.UNKNOWN) {
                // 查库回调还没回来 → 短提示（短到不会刷屏），用户知道系统在工作
                player.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "[系统] 账号数据加载中，请稍候...");
                continue;
            }

            if (status == RegistrationStatus.REGISTERED) {
                // 已注册未登录 → prompt-login
                lines = MessageUtil.getList("prompt-login");
            } else {
                // NOT_REGISTERED → 根据注册开关提示
                if (plugin.isAllowRegistration()) {
                    lines = MessageUtil.getList("prompt-register");
                } else {
                    lines = MessageUtil.getList("registration-closed");
                }
            }
            for (String line : lines) {
                player.sendMessage(line);
            }
        }
    }
}

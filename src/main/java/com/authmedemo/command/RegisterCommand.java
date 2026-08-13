package com.authmedemo.command;

// ===================== 实现思路 =====================
// /register <password> <confirmPassword> 注册命令
// 【本次Bug修复——对齐AuthMe成熟实现】
//  1. 【并发双注册漏洞修复】：当缓存不是 NOT_REGISTERED（即 UNKNOWN 或 REGISTERED 但缓存没值），
//     必须先主动异步查库后再判断，不能只看缓存 → 否则已注册玩家在查库回调前快速发起 /register
//     会走到 insertAuth，虽数据库唯一约束会阻止真写入，但用户会收到"数据库错误"假错误。
//  2. 所有异步回调（DB回调）里第一句必须 `if (!player.isOnline()) return;` —— 防止玩家在IO期间退出
//     导致 sendMessage 或缓存写入异常（虽然缓存退出时会清，但时间差内写入再清是浪费，更关键是 sendMessage 可能崩）。
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
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c只有玩家可以使用注册命令！");
            return true;
        }
        final Player player = (Player) sender;
        final UUID uuid = player.getUniqueId();

        // 1. 注册总开关：管理员已关闭注册
        if (!plugin.isAllowRegistration()) {
            sendLines(player, MessageUtil.getList("registration-closed"));
            return true;
        }

        // 2. 已登录了，不让重复注册
        if (cache.isLoggedIn(uuid)) {
            player.sendMessage(MessageUtil.get("already-logged-in"));
            return true;
        }

        // 3. 参数校验：必须两个参数（密码+确认密码）
        if (args.length != 2) {
            player.sendMessage(MessageUtil.get("register-usage"));
            return true;
        }

        final String password = args[0];
        final String confirmPwd = args[1];

        // 4. 密码长度校验
        if (!security.isPasswordLongEnough(password, minPwdLen)) {
            player.sendMessage(MessageUtil.get("register-password-too-short",
                    "{min}", String.valueOf(minPwdLen)));
            return true;
        }

        // 5. 两次密码比对
        if (!password.equals(confirmPwd)) {
            player.sendMessage(MessageUtil.get("register-password-mismatch"));
            return true;
        }

        // 6. 【修复：并发双注册】根据注册状态三态分支处理
        //    - REGISTERED → 直接提示已注册
        //    - NOT_REGISTERED → 可以立即开始注册流程
        //    - UNKNOWN（查库空窗期）→ 必须先查库，不能盲插入
        switch (cache.getRegistrationStatus(uuid)) {
            case REGISTERED:
                player.sendMessage(MessageUtil.get("register-already"));
                return true;
            case NOT_REGISTERED:
                doRegister(player, uuid, password);
                return true;
            case UNKNOWN:
            default:
                player.sendMessage("§e§l[系统] 账号数据加载中，请稍等一秒后再尝试注册...");
                // 主动补一次查库，等查完状态更新了用户再输就可以走 NOT_REGISTERED 分支了
                db.getAuthByUuid(uuid.toString(), (PlayerAuth auth) -> {
                    if (!player.isOnline()) return;
                    if (auth != null) {
                        cache.setRegistered(uuid, auth);
                        player.sendMessage(MessageUtil.get("register-already"));
                    } else {
                        cache.setNotRegistered(uuid);
                        player.sendMessage("§a§l[系统] 数据加载完成！现在你可以输入 /register 密码 确认密码 进行注册了！");
                    }
                });
                return true;
        }
    }

    /**
     * 真正执行注册：BCrypt哈希 → 异步插库 → 成功后标记登录+发大框
     */
    private void doRegister(final Player player, final UUID uuid, final String password) {
        final String realName = player.getName();
        final String lowerName = realName.toLowerCase();
        final String offlineUuidStr = uuid.toString();
        // BCrypt哈希密码（密码绝不会明文进入数据库！）
        final String hashedPwd = PasswordSecurity.hashPassword(password);
        final String ip = getPlayerIp(player);
        final long now = System.currentTimeMillis();
        final PlayerAuth newAuth = new PlayerAuth(realName, lowerName, offlineUuidStr, hashedPwd, ip, now);

        // 异步写入数据库
        db.insertAuth(newAuth, (Boolean success) -> {
            if (!player.isOnline()) return;
            if (success) {
                // 注册成功：更新缓存 + 标记登录 + 清失败计数
                cache.setRegistered(uuid, newAuth);
                cache.setLoggedIn(uuid);
                security.clearFailState(uuid);
                sendLines(player, MessageUtil.getList("register-success"));
            } else {
                // 插入失败：兜底再查一次看是不是已经注册了（别人同玩家名并发注册）
                player.sendMessage(MessageUtil.get("database-error"));
                db.getAuthByUuid(offlineUuidStr, (PlayerAuth existing) -> {
                    if (!player.isOnline()) return;
                    if (existing != null) {
                        cache.setRegistered(uuid, existing);
                        player.sendMessage(MessageUtil.get("register-already"));
                    }
                });
            }
        });
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

package com.authmedemo.command;

// ===================== 实现思路 =====================
// /login <password> 登录命令
// 【本次Bug修复】：所有异步回调第一句 `if (!player.isOnline()) return;`
//   防玩家发起 /login 后立刻退服，回调时 sendMessage/setLoggedIn 打到无效对象
// ====================================================

import com.authmedemo.AuthMeDemo;
import com.authmedemo.cache.PlayerCache;
import com.authmedemo.cache.PlayerCache.RegistrationStatus;
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
        final Player player = (Player) sender;
        final UUID uuid = player.getUniqueId();

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

        final String inputPassword = args[0];

        // 3. 根据缓存三态判断是直接登还是先查库
        PlayerAuth auth = cache.getCachedAuth(uuid);
        RegistrationStatus status = cache.getRegistrationStatus(uuid);

        if (status == RegistrationStatus.NOT_REGISTERED || (auth == null && status == RegistrationStatus.UNKNOWN)) {
            // NOT_REGISTERED → 直接提示没注册
            // UNKNOWN + 缓存空 → 主动查库
            db.getAuthByUuid(uuid.toString(), (PlayerAuth dbAuth) -> {
                if (!player.isOnline()) return;
                if (dbAuth == null) {
                    // 数据库里也没有 → 没注册
                    cache.setNotRegistered(uuid);
                    if (plugin.isAllowRegistration()) {
                        sendLines(player, MessageUtil.getList("prompt-register"));
                    } else {
                        sendLines(player, MessageUtil.getList("registration-closed"));
                    }
                } else {
                    // 查到了 → 缓存起来 → 登录校验
                    cache.setRegistered(uuid, dbAuth);
                    doLogin(player, uuid, dbAuth, inputPassword);
                }
            });
            return true;
        }

        // 4. 缓存命中（status=REGISTERED 且 auth!=null）→ 直接校验登录
        doLogin(player, uuid, auth, inputPassword);
        return true;
    }

    private void doLogin(Player player, UUID uuid, PlayerAuth auth, String inputPassword) {
        if (!player.isOnline()) return;

        // 冷却检查（重要！冷却期内不做密码校验，防止暴力破解）
        long remainingCooldown = security.getRemainingCooldownSeconds(uuid);
        if (remainingCooldown > 0) {
            player.sendMessage(MessageUtil.get("login-cooldown",
                    "{seconds}", String.valueOf(remainingCooldown)));
            return;
        }

        // BCrypt密码比对
        boolean correct = PasswordSecurity.checkPassword(inputPassword, auth.getPasswordHash());
        if (!correct) {
            boolean reachedMax = security.recordLoginFail(uuid);
            if (reachedMax) {
                long cooldown = security.getRemainingCooldownSeconds(uuid);
                player.sendMessage(MessageUtil.get("login-too-many-fails",
                        "{seconds}", String.valueOf(cooldown)));
            } else {
                player.sendMessage(MessageUtil.get("login-failed"));
            }
            return;
        }

        // 登录成功！
        security.clearFailState(uuid);
        cache.setLoggedIn(uuid);
        db.updateLoginInfo(auth.getOfflineUuid(), getPlayerIp(player), System.currentTimeMillis());
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

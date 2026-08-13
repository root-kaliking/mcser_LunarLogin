package com.authmedemo.cache;

// ===================== 实现思路 =====================
// 内存缓存层：减少数据库查询压力
// 【重要修正——对齐AuthMe状态机】：
//   之前的 isRegistered() 直接判断 authCache.containsKey()，
//   但 DB 查库回调前 authCache 一定没写进缓存——这时 PromptTask/RegisterCommand
//   会把"已注册玩家"错判成未注册 → 发错提示框 / 允许重复发起注册。
//   【修复方案】引入 RegistrationStatus 三态：
//     UNKNOWN        = 查库回调还没回来（进服后前几十ms的空窗期）
//     REGISTERED     = 已注册，且账号已缓存进 authCache
//     NOT_REGISTERED = 已确认数据库里没有这个玩家，不用再查
//   PromptTask 对 UNKNOWN 玩家发"数据加载中..."中间态，不瞎猜。
// ====================================================

import com.authmedemo.model.PlayerAuth;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家内存缓存管理器
 * 线程安全：使用ConcurrentHashMap，支持Bukkit异步线程访问
 */
public class PlayerCache {

    /**
     * 玩家注册状态（三态，解决查库空窗期误判问题）
     */
    public enum RegistrationStatus {
        UNKNOWN,        // 查库回调还没回来，不知道有没有注册
        REGISTERED,     // 已注册，authCache里一定有值
        NOT_REGISTERED  // 查库确认过没注册，数据库里没有
    }

    // 已登录玩家集合（用UUID做key，判断是否已登录只需O(1)查此Set）
    private final Set<UUID> loggedInPlayers = ConcurrentHashMap.newKeySet();

    // 玩家账号数据缓存（离线UUID -> PlayerAuth）
    // 只有在状态=REGISTERED时才有值（注册成功后写入、或Join查库后写入）
    private final Map<UUID, PlayerAuth> authCache = new ConcurrentHashMap<>();

    // 玩家注册状态（三态）：必须和 authCache 保持一致
    private final Map<UUID, RegistrationStatus> regStatus = new ConcurrentHashMap<>();

    // 玩家进服时间戳（毫秒）——用于"命令太快"保护
    private final Map<UUID, Long> joinTimestamps = new ConcurrentHashMap<>();

    // ===================== 登录状态缓存 =====================

    public void setLoggedIn(UUID uuid) {
        loggedInPlayers.add(uuid);
    }

    public void setLoggedOut(UUID uuid) {
        loggedInPlayers.remove(uuid);
    }

    public boolean isLoggedIn(UUID uuid) {
        return loggedInPlayers.contains(uuid);
    }

    // ===================== 注册状态（三态）=====================

    /**
     * 玩家进服时调用：状态重置为 UNKNOWN（查库还没回来），并记录进服时间戳
     */
    public void prepareForJoin(UUID uuid) {
        regStatus.put(uuid, RegistrationStatus.UNKNOWN);
        joinTimestamps.put(uuid, System.currentTimeMillis());
    }

    /**
     * 查库回调后，如果玩家【已注册】——标记 REGISTERED 并缓存账号数据
     */
    public void setRegistered(UUID uuid, PlayerAuth auth) {
        if (auth != null) {
            authCache.put(uuid, auth);
            regStatus.put(uuid, RegistrationStatus.REGISTERED);
        }
    }

    /**
     * 查库回调后，如果玩家【未注册】——标记 NOT_REGISTERED
     * 以后 PromptTask/RegisterCommand 不用再查DB就能判断
     */
    public void setNotRegistered(UUID uuid) {
        regStatus.put(uuid, RegistrationStatus.NOT_REGISTERED);
    }

    /**
     * 判断玩家是否已注册。返回值三态。
     * 这是所有外部判断注册状态的【唯一入口】，不许直接用 authCache.containsKey！
     */
    public RegistrationStatus getRegistrationStatus(UUID uuid) {
        return regStatus.getOrDefault(uuid, RegistrationStatus.UNKNOWN);
    }

    /**
     * 简化判断：是否【确定已经】注册过
     */
    public boolean isRegistered(UUID uuid) {
        return getRegistrationStatus(uuid) == RegistrationStatus.REGISTERED;
    }

    /**
     * 简化判断：是否【确定还没】注册
     */
    public boolean isDefinitelyNotRegistered(UUID uuid) {
        return getRegistrationStatus(uuid) == RegistrationStatus.NOT_REGISTERED;
    }

    /**
     * 从缓存取账号数据（可能返回null）
     */
    public PlayerAuth getCachedAuth(UUID uuid) {
        return authCache.get(uuid);
    }

    // ===================== 命令太快保护 =====================

    /**
     * 玩家进服后多少毫秒内禁止输命令（默认 800ms，对齐AuthMe的防快刷）
     */
    private static final long COMMAND_JOIN_COOLDOWN_MS = 800L;

    /**
     * 玩家进服后尝试输命令时调用，判断是否"用命令太快"
     *
     * @return true=太快了，应该拒绝；false=正常，可以放行
     */
    public boolean isCommandTooFast(UUID uuid) {
        Long joinTs = joinTimestamps.get(uuid);
        if (joinTs == null) return false; // 没记录就不拦
        return System.currentTimeMillis() - joinTs < COMMAND_JOIN_COOLDOWN_MS;
    }

    // ===================== 清理（退出服务器时调用）=====================

    public void clearPlayer(UUID uuid) {
        loggedInPlayers.remove(uuid);
        authCache.remove(uuid);
        regStatus.remove(uuid);
        joinTimestamps.remove(uuid);
    }

    public void clearAll() {
        loggedInPlayers.clear();
        authCache.clear();
        regStatus.clear();
        joinTimestamps.clear();
    }

    public int getLoggedInCount() {
        return loggedInPlayers.size();
    }
}

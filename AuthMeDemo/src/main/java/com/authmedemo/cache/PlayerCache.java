package com.authmedemo.cache;

// ===================== 实现思路 =====================
// 内存缓存层：减少数据库查询压力
// 1. loggedInPlayers：已登录玩家的UUID集合（判断玩家是否放行只需查内存，不用查DB）
// 2. playerAuthCache：玩家账号数据缓存（按离线UUID映射，避免重复查库）
// 3. 玩家退出时从缓存移除，不占用内存
// 注意：所有写缓存操作必须与数据库异步操作配合，做到"DB写成功后再更新缓存"
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

    // 已登录玩家集合（用UUID做key，判断是否已登录只需O(1)查此Set）
    // 只有在数据库存在账号 + 登录/注册成功 之后才会加入此集合
    private final Set<UUID> loggedInPlayers = ConcurrentHashMap.newKeySet();

    // 玩家账号数据缓存（离线UUID -> PlayerAuth）
    // 玩家进服查库后写入，后续判断是否注册直接从缓存取
    private final Map<UUID, PlayerAuth> authCache = new ConcurrentHashMap<>();

    // ===================== 登录状态缓存 =====================

    /**
     * 标记玩家为已登录（加入内存集合）
     */
    public void setLoggedIn(UUID uuid) {
        loggedInPlayers.add(uuid);
    }

    /**
     * 取消玩家登录状态（登出/退出服务器时调用）
     */
    public void setLoggedOut(UUID uuid) {
        loggedInPlayers.remove(uuid);
    }

    /**
     * 判断玩家是否已登录
     * 【核心高频查询】事件拦截器判断是否要冻结玩家时大量调用
     *
     * @return true=已登录（放行） false=未登录（冻结）
     */
    public boolean isLoggedIn(UUID uuid) {
        return loggedInPlayers.contains(uuid);
    }

    // ===================== 账号数据缓存 =====================

    /**
     * 把查询到的账号数据写入缓存
     * （玩家进服时查库后调用，或注册/修改密码后更新）
     */
    public void cacheAuth(UUID uuid, PlayerAuth auth) {
        if (auth != null) {
            authCache.put(uuid, auth);
        }
    }

    /**
     * 从缓存取账号数据（可能返回null，表示没缓存过）
     */
    public PlayerAuth getCachedAuth(UUID uuid) {
        return authCache.get(uuid);
    }

    /**
     * 判断玩家是否已经注册过（通过缓存判断，比查库快）
     *
     * @return true=注册过 false=没注册过或缓存未命中
     */
    public boolean isRegistered(UUID uuid) {
        return authCache.containsKey(uuid);
    }

    /**
     * 清除玩家所有缓存（退出服务器时调用）
     */
    public void clearPlayer(UUID uuid) {
        loggedInPlayers.remove(uuid);
        authCache.remove(uuid);
    }

    /**
     * 清除所有缓存（插件禁用时调用）
     */
    public void clearAll() {
        loggedInPlayers.clear();
        authCache.clear();
    }

    /**
     * 已登录玩家数（调试用）
     */
    public int getLoggedInCount() {
        return loggedInPlayers.size();
    }
}

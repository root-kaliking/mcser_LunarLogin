package com.authmedemo.security;

// ===================== 实现思路 =====================
// 安全限制管理器：负责登录失败计数 + 冷却拦截
// 对齐AuthMe行为：
// 1. 玩家连续输错密码 maxLoginFails 次后，进入冷却期
// 2. 冷却期内玩家尝试登录会直接被拒，不做密码校验
// 3. 登录成功/玩家退出服务器 时，清零该玩家的失败计数和冷却
// 注意：这些状态只在内存维护，不需要存数据库（重启重置即可）
// ====================================================

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 安全限制管理器
 * 处理登录失败计数、冷却拦截
 */
public class SecurityManager {

    // 最大允许连续失败次数（从config读取）
    private final int maxLoginFails;

    // 登录失败冷却时间（秒，从config读取）
    private final int cooldownSeconds;

    // 玩家登录失败次数计数（UUID -> 失败次数）
    private final Map<UUID, Integer> failCounts = new ConcurrentHashMap<>();

    // 玩家冷却结束时间戳（UUID -> System.currentTimeMillis()）
    // 超过此时刻后，玩家可以再次尝试登录
    private final Map<UUID, Long> cooldownEndTimes = new ConcurrentHashMap<>();

    public SecurityManager(int maxLoginFails, int cooldownSeconds) {
        this.maxLoginFails = maxLoginFails;
        this.cooldownSeconds = cooldownSeconds;
    }

    // ===================== 失败计数 =====================

    /**
     * 玩家密码输错时调用：失败次数+1，达到上限则开启冷却
     *
     * @return true=已达到失败上限，刚刚开启冷却；false=还没到上限
     */
    public boolean recordLoginFail(UUID uuid) {
        int count = failCounts.getOrDefault(uuid, 0) + 1;
        failCounts.put(uuid, count);

        if (count >= maxLoginFails) {
            // 达到失败次数上限，设置冷却结束时间 = 当前 + cooldownSeconds * 1000
            cooldownEndTimes.put(uuid, System.currentTimeMillis() + (long) cooldownSeconds * 1000);
            return true;
        }
        return false;
    }

    /**
     * 玩家登录/注册成功时调用：清零失败次数和冷却状态
     */
    public void clearFailState(UUID uuid) {
        failCounts.remove(uuid);
        cooldownEndTimes.remove(uuid);
    }

    /**
     * 玩家退出服务器时调用：清理状态，释放内存
     */
    public void clearPlayer(UUID uuid) {
        failCounts.remove(uuid);
        cooldownEndTimes.remove(uuid);
    }

    // ===================== 冷却检查 =====================

    /**
     * 玩家尝试登录前调用：检查是否处于冷却期
     *
     * @return 剩余冷却秒数；0 表示不处于冷却，可以尝试登录
     */
    public long getRemainingCooldownSeconds(UUID uuid) {
        Long endTime = cooldownEndTimes.get(uuid);
        if (endTime == null) {
            return 0;
        }
        long remaining = (endTime - System.currentTimeMillis()) / 1000;
        if (remaining <= 0) {
            // 冷却自然过期，清理状态
            cooldownEndTimes.remove(uuid);
            failCounts.remove(uuid);
            return 0;
        }
        return remaining;
    }

    /**
     * 是否处于冷却期（给事件判断用）
     */
    public boolean isInCooldown(UUID uuid) {
        return getRemainingCooldownSeconds(uuid) > 0;
    }

    // ===================== 密码长度校验 =====================

    /**
     * 校验密码长度是否满足最小位数
     */
    public boolean isPasswordLongEnough(String password, int minLength) {
        return password != null && password.length() >= minLength;
    }
}

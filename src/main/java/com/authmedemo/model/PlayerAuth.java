package com.authmedemo.model;

// ===================== 实现思路 =====================
// 玩家账号数据模型，对应SQLite表中一行记录
// 对齐AuthMe的核心字段：用户名、小写用户名、离线UUID、密码哈希、登录IP、注册时间
// 内存中还额外维护"是否已登录"的状态（不存库，因为每次进服都要重新登录）
// ====================================================

/**
 * 玩家账号数据模型
 * 对应数据库 authme_players 表的一条记录
 */
public class PlayerAuth {

    // 玩家真实用户名（保留大小写，如"Steve"）
    private String realName;

    // 玩家小写用户名（用于不区分大小写的唯一性判断，如"steve"）
    private String lowerName;

    // 离线UUID（online-mode=false时由Bukkit根据玩家名计算的UUID）
    // offlineUUID = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes())
    private String offlineUuid;

    // BCrypt密码哈希（绝对不能是明文！）
    private String passwordHash;

    // 注册时/最后一次登录的IP地址（格式如"192.168.1.1"）
    private String lastIp;

    // 注册时间戳（毫秒，System.currentTimeMillis()）
    private long registerDate;

    // 最后一次登录时间戳（毫秒）
    private long lastLoginDate;

    /**
     * 空构造（用于数据库读取后反射/Setter填充）
     */
    public PlayerAuth() {
    }

    /**
     * 新注册时用的构造
     */
    public PlayerAuth(String realName, String lowerName, String offlineUuid,
                      String passwordHash, String lastIp, long registerDate) {
        this.realName = realName;
        this.lowerName = lowerName;
        this.offlineUuid = offlineUuid;
        this.passwordHash = passwordHash;
        this.lastIp = lastIp;
        this.registerDate = registerDate;
        this.lastLoginDate = registerDate; // 注册即登录
    }

    // ===================== Getter / Setter =====================

    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }

    public String getLowerName() { return lowerName; }
    public void setLowerName(String lowerName) { this.lowerName = lowerName; }

    public String getOfflineUuid() { return offlineUuid; }
    public void setOfflineUuid(String offlineUuid) { this.offlineUuid = offlineUuid; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getLastIp() { return lastIp; }
    public void setLastIp(String lastIp) { this.lastIp = lastIp; }

    public long getRegisterDate() { return registerDate; }
    public void setRegisterDate(long registerDate) { this.registerDate = registerDate; }

    public long getLastLoginDate() { return lastLoginDate; }
    public void setLastLoginDate(long lastLoginDate) { this.lastLoginDate = lastLoginDate; }

    @Override
    public String toString() {
        return "PlayerAuth{name='" + realName + "', uuid='" + offlineUuid + "'}";
    }
}

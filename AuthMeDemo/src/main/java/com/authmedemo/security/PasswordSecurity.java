package com.authmedemo.security;

// ===================== 实现思路 =====================
// 严格对齐AuthMeReloaded的密码处理逻辑：
// 1. 使用jBCrypt算法（成本因子10 = 2^10=1024次哈希迭代）
// 2. 密码存储格式：$2a$10$... (BCrypt标准格式，内含随机盐+哈希值)
// 3. 绝不保存明文密码，数据库只存bcrypt哈希字符串
// 4. 密码比对时通过BCrypt.checkpw()计算输入密码的哈希后与存储值比对
// ====================================================

import org.mindrot.jbcrypt.BCrypt;

/**
 * 密码安全工具类
 * 基于jBCrypt实现，与AuthMeReloaded密码处理逻辑完全一致
 */
public class PasswordSecurity {

    // BCrypt成本因子，AuthMe默认使用10
    // 数值越高越安全，但计算越慢。10是安全性与性能的平衡点
    private static final int BCRYPT_LOG_ROUNDS = 10;

    /**
     * 加密明文密码
     * 【核心安全规则】：此方法返回的字符串才能存数据库，绝对不能存明文
     *
     * @param plainPassword 用户输入的明文密码
     * @return BCrypt哈希字符串（包含随机盐和成本因子）
     */
    public static String hashPassword(String plainPassword) {
        // BCrypt.gensalt()自动生成16字节随机盐 + 成本因子
        // 返回的哈希串格式：$2a$10$<22字符盐><31字符哈希>
        // 盐值内嵌在哈希串中，校验时无需单独存盐
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(BCRYPT_LOG_ROUNDS));
    }

    /**
     * 校验密码是否匹配
     * 登录时调用：用户输入明文密码 + 数据库存的哈希串
     *
     * @param plainPassword 用户输入的明文密码
     * @param hashedPassword 数据库中存储的BCrypt哈希串
     * @return true=密码正确 false=密码错误
     */
    public static boolean checkPassword(String plainPassword, String hashedPassword) {
        if (plainPassword == null || hashedPassword == null) {
            return false;
        }
        try {
            // BCrypt.checkpw内部会从hashedPassword解析盐值和成本因子
            // 对plainPassword用同样参数哈希后与hashedPassword的哈希部分做时序安全比较
            return BCrypt.checkpw(plainPassword, hashedPassword);
        } catch (IllegalArgumentException e) {
            // 哈希串格式非法时返回false，不要抛异常导致服务器出错
            return false;
        }
    }

    /**
     * 判断字符串是否为合法的BCrypt哈希格式
     * 用于避免把明文误存进数据库的兜底检查
     *
     * @param str 待检查字符串
     * @return true=看起来是合法的BCrypt哈希
     */
    public static boolean isBCryptHash(String str) {
        if (str == null) return false;
        // BCrypt哈希格式：$2a$xx$... 或 $2y$xx$... 或 $2b$xx$...
        // 总长度60字符
        return str.length() == 60 && str.matches("^\\$2[aby]?\\$\\d{2}\\$[./0-9A-Za-z]{53}$");
    }
}

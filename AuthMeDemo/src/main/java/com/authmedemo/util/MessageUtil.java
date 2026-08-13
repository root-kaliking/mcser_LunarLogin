package com.authmedemo.util;

// ===================== 实现思路 =====================
// 消息工具类：从config.yml读取配置好的消息，支持占位符替换
// 禁止在代码任何地方硬编码中文提示，所有消息必须走此类
// 占位符目前支持：
//   {player}  -> 玩家名
//   {min}     -> 密码最小长度
//   {seconds} -> 剩余冷却秒数
// ====================================================

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 消息工具类
 * 所有对玩家发送的提示消息都从此类读取，确保消息可配置
 */
public class MessageUtil {

    // 插件配置引用
    private static FileConfiguration config;

    /**
     * 插件启动时初始化，注入config引用
     */
    public static void init(FileConfiguration pluginConfig) {
        config = pluginConfig;
    }

    /**
     * 核心方法：按key读取消息，替换占位符，并解析颜色代码
     *
     * @param key config.yml中messages下的key（如"login-success"）
     * @param replacements 占位符替换（依次配对：key1, value1, key2, value2...）
     * @return 处理好的最终消息字符串
     */
    public static String get(String key, String... replacements) {
        if (config == null) {
            return "[MessageUtil not initialized] " + key;
        }

        // 从messages.xxx路径读取配置
        String message = config.getString("messages." + key);
        if (message == null) {
            // 配置缺失时返回提示，避免NPE
            return ChatColor.RED + "Missing message: messages." + key;
        }

        // 替换占位符（格式：key1, value1, key2, value2...）
        if (replacements != null) {
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                String placeholder = replacements[i];
                String value = replacements[i + 1];
                if (value != null) {
                    message = message.replace(placeholder, value);
                }
            }
        }

        // 解析 § 颜色代码（Bukkit标准做法）
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * 便捷重载：不带占位符的消息读取
     */
    public static String get(String key) {
        return get(key, (String[]) null);
    }
}

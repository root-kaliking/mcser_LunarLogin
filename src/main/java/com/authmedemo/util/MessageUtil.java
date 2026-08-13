package com.authmedemo.util;

// ===================== 实现思路 =====================
// 消息工具类：从config.yml读取配置好的消息，支持占位符替换
// 禁止在代码任何地方硬编码中文提示，所有消息必须走此类
// 支持两种消息格式：
//   - 单条String（老样子，get/getList都行）
//   - String List（多行，比如登录/注册提示大框框，读出来List<String>然后逐条发）
// 占位符支持：{player} 玩家名 | {min} 最小密码位数 | {seconds} 剩余冷却秒数
// ====================================================

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MessageUtil {

    private static FileConfiguration config;

    /** 插件启动时注入config引用 */
    public static void init(FileConfiguration pluginConfig) {
        config = pluginConfig;
    }

    // ===================== 核心：占位符替换引擎（复用）=====================
    private static String applyReplacements(String msg, String... replacements) {
        if (msg == null) return "";
        if (replacements != null) {
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                String placeholder = replacements[i];
                String value = replacements[i + 1];
                if (value != null) {
                    msg = msg.replace(placeholder, value);
                }
            }
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    // ===================== 单条消息：String get() =====================
    public static String get(String key, String... replacements) {
        if (config == null) {
            return ChatColor.RED + "[MessageUtil未初始化] " + key;
        }
        // 优先按 String 取
        String msg = config.getString("messages." + key);
        if (msg != null) {
            return applyReplacements(msg, replacements);
        }
        // 退而求其次：如果用户配成 List，取第 0 行（兼容误配）
        List<String> list = config.getStringList("messages." + key);
        if (list != null && !list.isEmpty()) {
            return applyReplacements(list.get(0), replacements);
        }
        return ChatColor.RED + "缺少消息配置: messages." + key;
    }

    public static String get(String key) {
        return get(key, (String[]) null);
    }

    // ===================== 多行消息：List<String> getList() =====================
    public static List<String> getList(String key, String... replacements) {
        if (config == null) {
            return Collections.singletonList(ChatColor.RED + "[MessageUtil未初始化] " + key);
        }
        List<String> rawList = config.getStringList("messages." + key);
        // 如果配置不是 List，是单 String，则包装成单元素 List
        if (rawList == null || rawList.isEmpty()) {
            String single = config.getString("messages." + key);
            if (single != null) {
                rawList = Collections.singletonList(single);
            } else {
                return Collections.singletonList(ChatColor.RED + "缺少消息配置: messages." + key);
            }
        }
        // 逐行应用占位符替换和颜色代码
        List<String> result = new ArrayList<>(rawList.size());
        for (String line : rawList) {
            result.add(applyReplacements(line, replacements));
        }
        return result;
    }

    public static List<String> getList(String key) {
        return getList(key, (String[]) null);
    }

    /** 判断某个消息 key 是否存在（用于判断是否配了多行） */
    public static boolean hasKey(String key) {
        if (config == null) return false;
        ConfigurationSection msgs = config.getConfigurationSection("messages");
        return msgs != null && msgs.contains(key);
    }
}

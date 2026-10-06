package com.dsh.worldbackup.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文本工具：同时支持 MiniMessage 标签（&lt;aqua&gt;）与传统的 &amp; 颜色代码。
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String[] LEGACY_TAGS = new String[128];

    static {
        LEGACY_TAGS['0'] = "black";
        LEGACY_TAGS['1'] = "dark_blue";
        LEGACY_TAGS['2'] = "dark_green";
        LEGACY_TAGS['3'] = "dark_aqua";
        LEGACY_TAGS['4'] = "dark_red";
        LEGACY_TAGS['5'] = "dark_purple";
        LEGACY_TAGS['6'] = "gold";
        LEGACY_TAGS['7'] = "gray";
        LEGACY_TAGS['8'] = "dark_gray";
        LEGACY_TAGS['9'] = "blue";
        LEGACY_TAGS['a'] = "green";
        LEGACY_TAGS['b'] = "aqua";
        LEGACY_TAGS['c'] = "red";
        LEGACY_TAGS['d'] = "light_purple";
        LEGACY_TAGS['e'] = "yellow";
        LEGACY_TAGS['f'] = "white";
        LEGACY_TAGS['k'] = "obfuscated";
        LEGACY_TAGS['l'] = "bold";
        LEGACY_TAGS['m'] = "strikethrough";
        LEGACY_TAGS['n'] = "underlined";
        LEGACY_TAGS['o'] = "italic";
        LEGACY_TAGS['r'] = "reset";
    }

    private Text() {
    }

    /** 便捷构造占位符表。 */
    public static Map<String, String> ph(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** 把 &amp; 颜色代码转换成 MiniMessage 标签。已经是 MiniMessage 标签的内容不受影响。 */
    public static String legacyToMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c == '&' || c == '\u00a7') && i + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(i + 1));
                if (code < 128 && LEGACY_TAGS[code] != null) {
                    out.append('<').append(LEGACY_TAGS[code]).append('>');
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 替换 %KEY% 占位符。 */
    public static String apply(String template, Map<String, String> placeholders) {
        if (template == null) {
            return "";
        }
        String result = template;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                result = replaceIgnoreCase(result, "%" + entry.getKey() + "%", entry.getValue());
            }
        }
        return result;
    }

    private static String replaceIgnoreCase(String source, String target, String replacement) {
        if (replacement == null) {
            replacement = "";
        }
        int index = indexOfIgnoreCase(source, target);
        if (index < 0) {
            return source;
        }
        StringBuilder sb = new StringBuilder(source.length() + 16);
        int start = 0;
        while (index >= 0) {
            sb.append(source, start, index).append(replacement);
            start = index + target.length();
            index = indexOfIgnoreCase(source, target, start);
        }
        sb.append(source, start, source.length());
        return sb.toString();
    }

    private static int indexOfIgnoreCase(String source, String target) {
        return indexOfIgnoreCase(source, target, 0);
    }

    private static int indexOfIgnoreCase(String source, String target, int from) {
        int limit = source.length() - target.length();
        for (int i = Math.max(from, 0); i <= limit; i++) {
            if (source.regionMatches(true, i, target, 0, target.length())) {
                return i;
            }
        }
        return -1;
    }

    /** 渲染成 Adventure 组件，解析失败时退化为纯文本。 */
    public static Component render(String template, Map<String, String> placeholders) {
        String applied = apply(template, placeholders);
        String mini = legacyToMiniMessage(applied);
        try {
            return MINI.deserialize(mini);
        } catch (Throwable ignored) {
            return Component.text(mini.replaceAll("<[^>]+>", ""));
        }
    }

    /** 去掉所有 MiniMessage 标签与颜色代码，得到纯文本（用于 BOSS 血条标题等只接受字符串的场景）。 */
    public static String plain(String input) {
        if (input == null) {
            return "";
        }
        return legacyToMiniMessage(input).replaceAll("<[^>]+>", "");
    }

    /** 转换成原版 § 颜色代码字符串。 */
    public static String section(String input, Map<String, String> placeholders) {
        String applied = apply(input, placeholders);
        StringBuilder sb = new StringBuilder(applied.length());
        for (int i = 0; i < applied.length(); i++) {
            char c = applied.charAt(i);
            if ((c == '&' || c == '\u00a7') && i + 1 < applied.length()) {
                char code = Character.toLowerCase(applied.charAt(i + 1));
                if (code < 128 && LEGACY_TAGS[code] != null) {
                    sb.append('\u00a7').append(code);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString().replaceAll("<[^>]+>", "");
    }
}

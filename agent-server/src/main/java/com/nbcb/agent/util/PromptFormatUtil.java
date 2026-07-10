package com.nbcb.agent.util;

/**
 * ★ 安全提示词格式化工具 — 替代容易引发参数注入的 replaceFirst 链式调用
 * <p>
 * 问题：链式 {@code template.replaceFirst("%s", a).replaceFirst("%s", b)}
 * 如果 a 的内容包含 "%s"，第二次 replaceFirst 会错误匹配 a 中的 "%s" 而非模板占位符。
 * <p>
 * 解法：用 {@link StringBuilder#indexOf} + {@link StringBuilder#replace} 每次从上次替换位置之后搜索，
 * 确保只替换模板中的原始占位符，内容中的 "%s" 不会被误匹配。
 *
 * @author com.nbcb
 */
public final class PromptFormatUtil {

    private PromptFormatUtil() {
        // utility class
    }

    /**
     * 安全替换模板中的 %s 占位符（按顺序逐一替换）
     *
     * @param template     包含 %s 占位符的模板字符串
     * @param replacements 按顺序的替换值
     * @return 格式化后的字符串
     */
    public static String safeFormat(String template, String... replacements) {
        StringBuilder sb = new StringBuilder(template);
        int pos = 0;
        for (String replacement : replacements) {
            pos = sb.indexOf("%s", pos);
            if (pos == -1) {
                break;
            }
            sb.replace(pos, pos + 2, replacement);
            pos += replacement.length();
        }
        return sb.toString();
    }
}

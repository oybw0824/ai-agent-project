package com.nbcb.agent.util;

/**
 * 文本处理工具类 — 统一的字符串清洗与去重逻辑
 *
 * @author com.nbcb
 */
public final class TextProcessingUtil {

    private TextProcessingUtil() {
        // utility class
    }

    /**
     * ★ 检测并去除循环重复文本（如 LLM 输出 "你好你好你好"）
     * <p>
     * 算法：从大到小检查整除长度的重复模式，O(k) 其中 k = 因子数
     */
    public static String deduplicate(String text) {
        if (text == null || text.length() < 4) {
            return text;
        }
        int len = text.length();
        for (int half = len / 2; half >= Math.max(2, len / 10); half--) {
            if (len % half != 0) {
                continue;
            }
            String prefix = text.substring(0, half);
            boolean duplicated = true;
            for (int pos = half; pos < len; pos += half) {
                if (!text.startsWith(prefix, pos)) {
                    duplicated = false;
                    break;
                }
            }
            if (duplicated) {
                return prefix;
            }
        }
        return text;
    }
}

package com.agentx.ai.core.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * Token 估算工具。
 * <p>
 * 区分中英文进行差异化估算：
 * <ul>
 *   <li>英文/ASCII 字符：约 4 字符 = 1 token</li>
 *   <li>中文/CJK 字符：约 1.5 字符 = 1 token</li>
 * </ul>
 * 不依赖外部 token 计数库，保持框架轻量。
 *
 * @author bigchui
 * 
 */
public final class TokenEstimator {

    private static final Logger log = LoggerFactory.getLogger(TokenEstimator.class);

    /** 英文估算比率：约 4 字符 = 1 token */
    private static final double CHARS_PER_TOKEN_EN = 4.0;
    /** 中文估算比率：约 1.5 字符 = 1 token */
    private static final double CHARS_PER_TOKEN_CJK = 1.5;

    private TokenEstimator() {
    }

    /**
     * 估算消息列表的总 token 数。
     * <p>
     * 逐字符判断中英文，使用不同的换算比率。
     * 同时打印 DEBUG 日志，方便排查压缩触发时机。
     *
     * @param messages 消息列表
     * @return 估算的 token 数
     */
    public static int estimateTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int cjkCount = 0;
        int nonCjkCount = 0;

        for (Message msg : messages) {
            int[] counts = countChars(msg);
            cjkCount += counts[0];
            nonCjkCount += counts[1];
        }

        int tokens = (int) (cjkCount / CHARS_PER_TOKEN_CJK + nonCjkCount / CHARS_PER_TOKEN_EN);

        log.debug("Token estimation: cjkChars={}, nonCjkChars={}, estimatedTokens={}, messages={}",
                cjkCount, nonCjkCount, tokens, messages.size());

        return tokens;
    }

    /**
     * 统计单条消息的中文字符数和非中文字符数。
     *
     * @param message 消息
     * @return [cjkCount, nonCjkCount]
     */
    private static int[] countChars(Message message) {
        int cjk = 0;
        int nonCjk = 0;

        if (message instanceof SystemMessage sm) {
            int[] c = count(sm.getText());
            cjk += c[0]; nonCjk += c[1];
        } else if (message instanceof UserMessage um) {
            int[] c = count(um.getText());
            cjk += c[0]; nonCjk += c[1];
        } else if (message instanceof AssistantMessage am) {
            int[] c = count(am.getText());
            cjk += c[0]; nonCjk += c[1];
            if (am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    int[] cn = count(tc.name());
                    cjk += cn[0]; nonCjk += cn[1];
                    int[] ca = count(tc.arguments());
                    cjk += ca[0]; nonCjk += ca[1];
                }
            }
        } else if (message instanceof ToolResponseMessage trm) {
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                int[] c = count(resp.responseData());
                cjk += c[0]; nonCjk += c[1];
            }
        } else {
            int[] c = count(message.toString());
            cjk += c[0]; nonCjk += c[1];
        }

        return new int[]{cjk, nonCjk};
    }

    /**
     * 统计字符串中的中文字符数和非中文字符数。
     * <p>
     * CJK 统一汉字范围：\u4E00-\u9FFF（基本汉字）
     * 扩展覆盖：\u3400-\u4DBF（扩展A）、\uF900-\uFAFF（兼容汉字）
     *
     * @param s 字符串
     * @return [cjkCount, nonCjkCount]
     */
    private static int[] count(String s) {
        if (s == null || s.isEmpty()) {
            return new int[]{0, 0};
        }
        int cjk = 0;
        int nonCjk = 0;
        for (int i = 0; i < s.length(); i++) {
            if (isCJK(s.charAt(i))) {
                cjk++;
            } else {
                nonCjk++;
            }
        }
        return new int[]{cjk, nonCjk};
    }

    /**
     * 判断字符是否为 CJK（中日韩）字符。
     */
    private static boolean isCJK(char ch) {
        return (ch >= '\u4E00' && ch <= '\u9FFF')   // 基本汉字
                || (ch >= '\u3400' && ch <= '\u4DBF') // 扩展A
                || (ch >= '\uF900' && ch <= '\uFAFF') // 兼容汉字
                || (ch >= '\u2E80' && ch <= '\u2EFF') // CJK 部首补充
                || (ch >= '\u3000' && ch <= '\u303F') // CJK 符号和标点
                || (ch >= '\uFF00' && ch <= '\uFFEF') // 半角/全角字符
                || (ch >= '\u3040' && ch <= '\u309F') // 平假名
                || (ch >= '\u30A0' && ch <= '\u30FF');// 片假名
    }
}

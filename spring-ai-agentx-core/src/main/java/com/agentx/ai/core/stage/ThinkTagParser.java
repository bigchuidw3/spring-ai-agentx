package com.agentx.ai.core.stage;

import java.util.ArrayList;
import java.util.List;

/**
 * &lt;think/&gt; 标签解析器。
 *
 * 无状态工具类，将 LLM 流式输出的文本 chunk 拆分为思考内容和正常文本。
 * 支持跨 chunk 的标签状态追踪（通过 inThink 参数）。
 *
 * <p>解析规则：
 * <ul>
 *   <li>{@code <think&gt;} 标签后的内容 → {@link Segment#thinking()} = true</li>
 *   <li>{@code </think&gt;} 标签后的内容 → {@link Segment#thinking()} = false</li>
 *   <li>标签外的内容 → 正常文本</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * boolean inThink = false;
 * for (String chunk : chunks) {
 *     ThinkTagParser.ParseResult result = ThinkTagParser.parse(chunk, inThink);
 *     inThink = result.inThink();
 *     for (ThinkTagParser.Segment seg : result.segments()) {
 *         if (seg.thinking()) emit(new Thinking(seg.content()));
 *         else emit(new Text(seg.content()));
 *     }
 * }
 * }</pre>
 *
 * @author bigchui
 * 
 */
public final class ThinkTagParser {

    // 只匹配标签开头（不含 >），兼容 <think/> 和 <think attr="..."/> 等变体，
    // 实际标签结束位置通过 indexOf('>') 动态查找
    private static final String THINK_START = "<think";
    private static final String THINK_END = "</think";

    private ThinkTagParser() {
    }

    /**
     * 内容段，标识是思考内容还是正常文本。
     *
     * @param thinking 是否为思考内容
     * @param content  文本内容
     */
    public record Segment(boolean thinking, String content) {
    }

    /**
     * 解析结果。
     *
     * @param segments 拆分后的内容段列表
     * @param inThink  更新后的 think 标签内状态（跨 chunk 使用）
     */
    public record ParseResult(List<Segment> segments, boolean inThink) {
    }

    /**
     * 解析一个文本 chunk。
     *
     * @param chunk  当前文本 chunk
     * @param inThink 上一个 chunk 结束时的 think 标签内状态
     * @return 解析结果，包含拆分后的内容段和更新后的 inThink 状态
     */
    public static ParseResult parse(String chunk, boolean inThink) {
        if (chunk == null || chunk.isEmpty()) {
            return new ParseResult(List.of(), inThink);
        }

        List<Segment> segments = new ArrayList<>();
        boolean currentInThink = inThink;
        int index = 0;

        while (index < chunk.length()) {
            int thinkStartIdx = chunk.indexOf(THINK_START, index);
            int thinkEndIdx = chunk.indexOf(THINK_END, index);

            // 找下一个标签位置
            int nextTagPos;
            boolean isStartTag;

            if (thinkStartIdx == -1 && thinkEndIdx == -1) {
                // 没有更多标签
                String remaining = chunk.substring(index);
                if (!remaining.isEmpty()) {
                    segments.add(new Segment(currentInThink, remaining));
                }
                break;
            }

            if (thinkStartIdx != -1 && (thinkEndIdx == -1 || thinkStartIdx < thinkEndIdx)) {
                nextTagPos = thinkStartIdx;
                isStartTag = true;
            } else {
                nextTagPos = thinkEndIdx;
                isStartTag = false;
            }

            // 标签前的内容
            if (nextTagPos > index) {
                String beforeTag = chunk.substring(index, nextTagPos);
                if (!beforeTag.isEmpty()) {
                    segments.add(new Segment(currentInThink, beforeTag));
                }
            }

            // 跳过标签本身（包括 > 字符）
            int tagEnd = chunk.indexOf('>', nextTagPos);
            if (tagEnd == -1) {
                // 标签不完整（跨 chunk），跳过已读部分
                currentInThink = isStartTag;
                break;
            }

            currentInThink = isStartTag;
            index = tagEnd + 1;
        }

        return new ParseResult(segments, currentInThink);
    }

    /**
     * 去除文本中的 {@code <think>...</think>} 或 {@code <think>...</think/>} 标签及其内容。
     *
     * @param input 可能包含 think 标签的文本
     * @return 去除 think 标签后的文本
     */
    public static String stripThinkTags(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        // 通用正则：匹配 <think...>...</think...>（兼容空格、属性、自闭合等变体）
        String result = input.replaceAll("(?s)<think[^>]*>.*?</think[^>]*>", "").trim();
        return result;
    }
}

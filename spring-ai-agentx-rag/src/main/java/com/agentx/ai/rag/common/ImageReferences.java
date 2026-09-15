package com.agentx.ai.rag.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本图片引用解析工具。
 *
 * <p>当前支持 Markdown 图片和 HTML img 标签。图片引用是渲染和展示的原子单元，
 * 解析、增强和分块层共用同一套识别规则，避免各模块对“什么是图片”产生不同理解。
 *
 * @author bigchui
 */
public final class ImageReferences {

    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
            "!\\[(?<alt>(?:\\\\.|[^\\]\\r\\n])*)\\]\\(\\s*(?:<(?<angle>[^>\\r\\n]+)>|(?<plain>[^\\s)]+))"
                    + "(?:\\s+(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|\\([^)\\r\\n]*\\)))?\\s*\\)"
                    + "|<img\\b[^>\\r\\n]*>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HTML_SOURCE_PATTERN = Pattern.compile(
            "\\bsrc\\s*=\\s*(?:\"(?<double>[^\"]+)\"|'(?<single>[^']+)'|(?<bare>[^\\s>]+))",
            Pattern.CASE_INSENSITIVE);

    private ImageReferences() {
    }

    public static List<ImageReference> find(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<ImageReference> references = new ArrayList<>();
        Matcher matcher = REFERENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            references.add(new ImageReference(
                    matcher.group(),
                    referenceUri(matcher),
                    referenceAlt(matcher)
            ));
        }
        return List.copyOf(references);
    }

    public static String replace(String text, Function<ImageReference, String> replacement) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(replacement, "replacement");

        Matcher matcher = REFERENCE_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            ImageReference reference = new ImageReference(
                    matcher.group(),
                    referenceUri(matcher),
                    referenceAlt(matcher)
            );
            String value = replacement.apply(reference);
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    value == null ? reference.token() : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String normalizeUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return "";
        }
        String normalized = uri.replace('\\', '/');
        return normalized.startsWith("./") ? normalized.substring(2) : normalized;
    }

    private static String referenceUri(Matcher matcher) {
        String token = matcher.group();
        if (!token.toLowerCase().startsWith("<img")) {
            String uri = firstNonBlank(matcher.group("angle"), matcher.group("plain"));
            return uri == null ? "" : uri.trim();
        }

        Matcher sourceMatcher = HTML_SOURCE_PATTERN.matcher(token);
        if (!sourceMatcher.find()) {
            return "";
        }
        String uri = firstNonBlank(
                sourceMatcher.group("double"),
                sourceMatcher.group("single"),
                sourceMatcher.group("bare"));
        return uri == null ? "" : uri.trim();
    }

    private static String referenceAlt(Matcher matcher) {
        String token = matcher.group();
        if (!token.toLowerCase().startsWith("<img")) {
            return matcher.group("alt");
        }

        Matcher altMatcher = Pattern.compile(
                "\\balt\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
                Pattern.CASE_INSENSITIVE).matcher(token);
        if (!altMatcher.find()) {
            return "";
        }
        String alt = firstNonBlank(altMatcher.group(1), altMatcher.group(2), altMatcher.group(3));
        return alt == null ? "" : alt;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record ImageReference(String token, String uri, String alt) {
    }
}

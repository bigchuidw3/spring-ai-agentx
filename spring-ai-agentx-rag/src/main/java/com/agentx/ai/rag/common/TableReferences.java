package com.agentx.ai.rag.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本 HTML 表格解析工具。
 *
 * <p>检测 Markdown 中的 {@code <table>...</table>} 块，确保分块时表格作为
 * 原子单元不被截断。与 {@link ImageReferences} 搭配使用，覆盖文档中所有
 * 需要保持完整性的内联结构。
 *
 * @author bigchui
 */
public final class TableReferences {

    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "<table\\b[^>]*>[\\s\\S]*?</table>",
            Pattern.CASE_INSENSITIVE);

    private TableReferences() {
    }

    public static List<TableReference> find(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<TableReference> references = new ArrayList<>();
        Matcher matcher = TABLE_PATTERN.matcher(text);
        while (matcher.find()) {
            references.add(new TableReference(matcher.group()));
        }
        return List.copyOf(references);
    }

    public static String replace(String text, Function<TableReference, String> replacement) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(replacement, "replacement");

        Matcher matcher = TABLE_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            TableReference reference = new TableReference(matcher.group());
            String value = replacement.apply(reference);
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    value == null ? reference.token() : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public record TableReference(String token) {
        public TableReference {
            Objects.requireNonNull(token, "token");
        }
    }
}

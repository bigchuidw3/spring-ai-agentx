package com.agentx.ai.rag.splitter;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.agentx.ai.rag.common.ChunkIdGenerator;
import com.agentx.ai.rag.common.UuidChunkIdGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 按有序分隔符递归切分的分块器。
 *
 * <p>分隔符从粗到细依次尝试：当前分隔符切不出多个片段时自动降级到下一级；
 * 所有分隔符都无法继续细分时，才使用固定窗口兜底。分隔符会保留在文本中，
 * 避免句子被无痕拼接。未显式配置时使用 {@link #DEFAULT_SEPARATORS}，
 * 覆盖中英文常见段落、句末、分句和逗号边界。
 *
 * @author bigchui
 */
public class RecursiveSplitter extends AbstractDocumentSplitter {

    private static final String REGEX_PREFIX = "regex:";

    /**
     * 默认分隔符，按优先级从粗到细排列。
     */
    public static final List<String> DEFAULT_SEPARATORS = List.of(
            "\r\n\r\n",
            "\n\n",
            "\r\n",
            "\n",
            "regex:[。！？]+\\s*|[.!?]+(?=\\s|$)\\s*",
            "regex:[；;]+\\s*",
            "regex:[，、]+\\s*|[,]+(?=\\s|$)"
    );

    private final List<String> separators;

    private RecursiveSplitter(Builder builder) {
        super(builder.chunkSize, builder.overlap, builder.chunkIdGenerator,
                builder.preserveImageRef, builder.preserveTableRef);
        this.separators = List.copyOf(builder.separators);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected List<String> splitText(String text) {
        ContentProtector protector = createProtector(text);
        if (protector.hasProtectedContent()) {
            return splitRecursive(protector.protectedText(), 0).stream()
                    .map(protector::restore)
                    .toList();
        }
        return splitRecursive(text, 0);
    }

    private List<String> splitRecursive(String text, int depth) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (codePointLength(text) <= chunkSize()) {
            return List.of(text);
        }
        if (depth >= separators.size()) {
            return splitFixedWindow(text, chunkSize(), overlap(),
                    preserveImageRef(), preserveTableRef());
        }

        String separator = separators.get(depth);
        if (separator == null || separator.isEmpty()) {
            return splitRecursive(text, depth + 1);
        }

        List<String> pieces = splitBySeparator(text, separator);
        if (pieces.size() <= 1) {
            return splitRecursive(text, depth + 1);
        }

        List<String> result = new ArrayList<>();
        List<String> mergeable = new ArrayList<>();
        for (String piece : pieces) {
            if (piece == null || piece.isBlank()) {
                continue;
            }
            if (codePointLength(piece) > chunkSize()) {
                flush(mergeable, result);
                result.addAll(splitRecursive(piece, depth + 1));
            } else {
                mergeable.add(piece);
            }
        }
        flush(mergeable, result);
        return result;
    }

    private void flush(List<String> pieces, List<String> result) {
        if (pieces.isEmpty()) {
            return;
        }
        result.addAll(mergePieces(pieces));
        pieces.clear();
    }

    private List<String> mergePieces(List<String> pieces) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String piece : pieces) {
            if (current.isEmpty()) {
                current.append(piece);
                continue;
            }

            if (codePointLength(current.toString()) + codePointLength(piece) <= chunkSize()) {
                current.append(piece);
                continue;
            }

            String currentText = current.toString();
            result.add(currentText);

            int prefixLength = Math.min(overlap(), Math.max(0, chunkSize() - codePointLength(piece)));
            String overlapPrefix = suffixByCodePoints(currentText, prefixLength);
            current = new StringBuilder(overlapPrefix).append(piece);
        }

        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private List<String> splitBySeparator(String text, String separator) {
        List<String> pieces = new ArrayList<>();

        if (separator.startsWith(REGEX_PREFIX)) {
            String expression = separator.substring(REGEX_PREFIX.length());
            try {
                Matcher matcher = Pattern.compile(expression).matcher(text);
                int start = 0;
                while (matcher.find()) {
                    if (matcher.end() > start) {
                        pieces.add(text.substring(start, matcher.end()));
                    }
                    start = matcher.end();
                }
                if (start < text.length()) {
                    pieces.add(text.substring(start));
                }
            } catch (PatternSyntaxException e) {
                throw new RagException(RagErrorCode.SPLIT_CONFIG_INVALID,
                        "无效正则分隔符: " + expression, e);
            }
            return pieces;
        }

        int start = 0;
        int index;
        while ((index = text.indexOf(separator, start)) >= 0) {
            int end = index + separator.length();
            if (end > start) {
                pieces.add(text.substring(start, end));
            }
            start = end;
        }
        if (start < text.length()) {
            pieces.add(text.substring(start));
        }
        return pieces;
    }

    private static String suffixByCodePoints(String value, int length) {
        if (length <= 0 || value.isEmpty()) {
            return "";
        }
        int[] codePoints = value.codePoints().toArray();
        int start = Math.max(0, codePoints.length - length);
        return new String(codePoints, start, codePoints.length - start);
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    public static final class Builder {

        private int chunkSize = DEFAULT_CHUNK_SIZE;
        private int overlap = DEFAULT_OVERLAP;
        private boolean preserveImageRef = true;
        private boolean preserveTableRef = true;
        private List<String> separators = DEFAULT_SEPARATORS;
        private ChunkIdGenerator chunkIdGenerator = new UuidChunkIdGenerator();

        public Builder chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        public Builder overlap(int overlap) {
            this.overlap = overlap;
            return this;
        }

        public Builder preserveImageRef(boolean preserveImageRef) {
            this.preserveImageRef = preserveImageRef;
            return this;
        }

        public Builder preserveTableRef(boolean preserveTableRef) {
            this.preserveTableRef = preserveTableRef;
            return this;
        }

        public Builder separators(List<String> separators) {
            this.separators = separators;
            return this;
        }

        public Builder separators(String... separators) {
            this.separators = List.of(separators);
            return this;
        }

        public Builder chunkIdGenerator(ChunkIdGenerator chunkIdGenerator) {
            this.chunkIdGenerator = chunkIdGenerator;
            return this;
        }

        public RecursiveSplitter build() {
            Objects.requireNonNull(separators, "separators must not be null");
            if (separators.isEmpty()) {
                throw new RagException(RagErrorCode.SPLIT_CONFIG_INVALID,
                        "RecursiveSplitter 必须配置至少一个分隔符");
            }
            return new RecursiveSplitter(this);
        }
    }
}

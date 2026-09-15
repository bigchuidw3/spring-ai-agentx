package com.agentx.ai.rag.splitter;

import com.agentx.ai.rag.common.ChunkIdGenerator;
import com.agentx.ai.rag.common.UuidChunkIdGenerator;

import java.util.List;

/**
 * 按固定窗口长度切分的分块器。
 *
 * <p>每块最多 {@code chunkSize} 个 code point，尾部取 {@code overlap} 个 code point
 * 作为下一块前缀，适用于结构不清晰的通用文本。
 *
 * @author bigchui
 */
public class SlidingWindowSplitter extends AbstractDocumentSplitter {

    private SlidingWindowSplitter(Builder builder) {
        super(builder.chunkSize, builder.overlap, builder.chunkIdGenerator,
                builder.preserveImageRef, builder.preserveTableRef);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected List<String> splitText(String text) {
        return splitFixedWindow(text, chunkSize(), overlap(),
                preserveImageRef(), preserveTableRef());
    }

    public static final class Builder {

        private int chunkSize = DEFAULT_CHUNK_SIZE;
        private int overlap = DEFAULT_OVERLAP;
        private boolean preserveImageRef = true;
        private boolean preserveTableRef = true;
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

        public Builder chunkIdGenerator(ChunkIdGenerator chunkIdGenerator) {
            this.chunkIdGenerator = chunkIdGenerator;
            return this;
        }

        public SlidingWindowSplitter build() {
            return new SlidingWindowSplitter(this);
        }
    }
}

package com.agentx.ai.rag.parser.tika;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import com.agentx.ai.rag.common.ContentType;
import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.parser.DocumentParser;
import com.agentx.ai.rag.parser.ParsedBlock;
import com.agentx.ai.rag.parser.ParsedDocument;
import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Apache Tika 的通用文本解析器。
 *
 * <p>适合 txt、markdown、html、csv 以及 Office 等简单文本场景。复杂 PDF
 * 表格、图片混排应使用 MinerU 多模态解析器。
 *
 * @author bigchui
 */
public class TikaDocumentParser implements DocumentParser {

    private final Tika tika = new Tika();

    @Override
    public boolean supports(String fileName) {
        return fileName != null && !fileName.isBlank();
    }

    @Override
    public ParsedDocument parse(byte[] content, String fileName) {
        Objects.requireNonNull(content, "content");
        if (!supports(fileName)) {
            throw new RagException(RagErrorCode.UNSUPPORTED_DOCUMENT_TYPE,
                    "Tika 解析器需要有效的文件名: " + fileName);
        }

        try {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            String text = tika.parseToString(TikaInputStream.get(content), metadata);
            if (text == null) {
                text = "";
            }

            Map<String, Object> docMetadata = new LinkedHashMap<>();
            docMetadata.put(MetadataKeys.FILE_NAME, fileName);
            docMetadata.put(MetadataKeys.CONTENT_TYPE, ContentType.TEXT.code());

            return new ParsedDocument(
                    fileName,
                    List.of(ParsedBlock.text(text)),
                    docMetadata);
        } catch (Exception e) {
            throw new RagException(RagErrorCode.DOCUMENT_PARSE_FAILED,
                    "文档解析失败: " + fileName, e);
        }
    }
}

package com.agentx.ai.rag.parser.image;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.Objects;

/**
 * 基于 Spring AI ChatModel 的图片语义理解实现。
 *
 * <p>调用方传入的 ChatModel 必须支持图片输入。图片以字节形式传给模型，
 * 不要求图片先拥有公网 URL。
 *
 * @author bigchui
 */
public final class ChatModelImageDescriber implements ImageDescriber {

    private final ChatModel chatModel;
    private final String modelName;

    public ChatModelImageDescriber(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.modelName = defaultModelName(chatModel);
    }

    @Override
    public ImageDescription describe(ImageDescriptionRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            MimeType mimeType = MimeTypeUtils.parseMimeType(request.mediaType());
            Media media = new Media(mimeType, new ByteArrayResource(request.image()));
            UserMessage userMessage = UserMessage.builder()
                    .text(prompt(request))
                    .media(List.of(media))
                    .build();

            String text = chatModel.call(new Prompt(List.of(userMessage)))
                    .getResult()
                    .getOutput()
                    .getText();
            if (text == null || text.isBlank()) {
                throw new RagException(RagErrorCode.IMAGE_UNDERSTANDING_FAILED,
                        "图片语义描述为空: " + request.imageFileName());
            }
            return new ImageDescription(normalize(text), modelName);
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            throw new RagException(RagErrorCode.IMAGE_UNDERSTANDING_FAILED,
                    "图片语义理解失败: " + request.imageFileName(), e);
        }
    }

    private String prompt(ImageDescriptionRequest request) {
        return "你是文档图片理解引擎。请根据图片和所属文档信息生成用于知识库检索的图片描述。"
                + "要求说明图片类型、场景、对象、布局、流程、数据和图中重要文字；"
                + "结合上下文说明图片的作用；不要编造不存在的信息。"
                + "直接输出纯文本，不要换行，不要 Markdown，不要额外解释。"
                + "所属文档：" + request.documentFileName()
                + "；图片标题：" + request.caption()
                + "；图片文件：" + request.imageFileName() + "。";
    }

    private static String defaultModelName(ChatModel chatModel) {
        Objects.requireNonNull(chatModel, "chatModel");
        try {
            ChatOptions options = chatModel.getDefaultOptions();
            if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
                return options.getModel();
            }
        } catch (RuntimeException ignored) {
            // 个别实现可能不暴露默认配置，此时退回到实现类名作为描述来源。
        }
        return chatModel.getClass().getName();
    }

    private String normalize(String text) {
        return text.replaceAll("[\\r\\n]+", " ").trim();
    }
}

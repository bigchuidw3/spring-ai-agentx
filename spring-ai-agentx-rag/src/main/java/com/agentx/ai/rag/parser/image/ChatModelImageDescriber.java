package com.agentx.ai.rag.parser.image;

import com.agentx.ai.rag.exception.RagErrorCode;
import com.agentx.ai.rag.exception.RagException;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
        log.debug("图片语义理解开始: image={}, model={}, bytes={}",
                request.imageFileName(), modelName, request.image() == null ? 0 : request.image().length);
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
            log.debug("图片语义理解完成: image={}, descLen={}", request.imageFileName(), text.length());
            return new ImageDescription(normalize(text), modelName);
        } catch (RagException e) {
            log.warn("图片语义理解失败: image={}, err={}", request.imageFileName(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("图片语义理解异常: image={}, err={}", request.imageFileName(), e.getMessage());
            throw new RagException(RagErrorCode.IMAGE_UNDERSTANDING_FAILED,
                    "图片语义理解失败: " + request.imageFileName(), e);
        }
    }

    private String prompt(ImageDescriptionRequest request) {
        return """
                你是文档图片理解引擎。请生成一段用于知识库检索的图片描述，做到「精简且全面」：
                用最少的字覆盖最多的信息点，不遗漏关键细节。

                必须覆盖的信息点：
                - 图片类型与场景
                - 主体对象及其关系、布局/结构
                - 流程或操作步骤（如有）
                - 关键数据（数字、单位、比例、阈值）
                - 图中出现的所有重要文字（按钮、菜单、状态、专有名词等）
                - 结合所属文档上下文点明图片的作用

                表达规则：
                1. 用短句、名词短语、顿号罗列，删除形容词、修饰语和无信息量的连接词。
                2. 信息密度优先，杜绝重复表达；通常 150 字以内即可，复杂示意图以完整为准。
                3. 只陈述事实，不编造；看不清的内容不要猜测。
                4. 直接输出纯文本，不要换行，不要 Markdown，不要标题，不要额外解释。

                所属文档：%s
                图片标题：%s
                图片文件：%s
                """.formatted(request.documentFileName(), request.caption(), request.imageFileName());
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

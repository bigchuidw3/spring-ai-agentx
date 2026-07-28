package com.agentx.ai.core.tools;

import com.agentx.ai.core.memory.store.SessionMessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 上下文回溯工具 — 让 LLM 主动取回被 offload 的原始消息。
 * 各压缩层在替换原文后都会留下 offload 引用或 uuid 提示，
 * LLM 看到这些标记后可调用本工具按 uuid 取回完整内容。
 *
 * @author bigchui
 */
public class ContextReloadTool {

    private static final Logger log = LoggerFactory.getLogger(ContextReloadTool.class);

    private static final int MAX_RETURN_CHARS = 32000;

    private final SessionMessageStore sessionMessageStore;

    public ContextReloadTool(SessionMessageStore sessionMessageStore) {
        this.sessionMessageStore = sessionMessageStore;
    }

    @Tool(name = "context_reload", description = """
            取回此前被压缩/offload 的消息原文。当你在上下文中看到形如 [offload:uuid] 或 [内容已 offload, uuid=xxx] 的标记时，
            如果需要完整内容来回答用户问题，可调用本工具按 uuid 取回。无需取回时不要调用。
            """)
    public String contextReload(
            @ToolParam(description = "offload 引用中的 UUID（仅含 0-9、a-f、A-F 和连字符）") String uuid) {
        if (sessionMessageStore == null) {
            return "[error] offload store unavailable";
        }
        try {
            List<Message> messages = sessionMessageStore.getOffloadedByUuid(uuid);
            if (messages.isEmpty()) {
                return "[not_found] uuid=" + uuid + " not in offload_context";
            }
            String text = extract(messages);
            if (text.length() > MAX_RETURN_CHARS) {
                text = text.substring(0, MAX_RETURN_CHARS)
                        + "\n...[truncated, total " + text.length() + " chars]";
            }
            log.info("[ContextReloadTool] reloaded uuid={}, messages={}, chars={}", uuid, messages.size(), text.length());
            return text;
        } catch (Exception e) {
            log.warn("[ContextReloadTool] failed uuid={}: {}", uuid, e.getMessage());
            return "[error] " + e.getMessage();
        }
    }

    private String extract(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append(extractSingle(messages.get(i)));
        }
        return sb.toString();
    }

    private String extractSingle(Message msg) {
        if (msg instanceof AssistantMessage am) {
            StringBuilder sb = new StringBuilder();
            if (am.getText() != null && !am.getText().isEmpty()) {
                sb.append(am.getText());
            }
            if (am.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    sb.append("\n[ToolCall ").append(tc.name())
                            .append(" args=").append(tc.arguments()).append("]");
                }
            }
            return sb.toString();
        }
        if (msg instanceof ToolResponseMessage trm) {
            StringBuilder sb = new StringBuilder();
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                if (resp.responseData() != null) {
                    sb.append(resp.responseData());
                }
            }
            return sb.toString();
        }
        String text = msg.getText();
        return text != null ? text : "";
    }
}

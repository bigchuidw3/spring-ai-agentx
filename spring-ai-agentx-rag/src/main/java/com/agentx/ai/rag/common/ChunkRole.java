package com.agentx.ai.rag.common;

/**
 * RAG 分块角色。
 *
 * <p>角色描述块在父子检索结构中的位置；是否向量化由 skipEmbedding 单独控制。
 *
 * @author bigchui
 */
public enum ChunkRole {

    PARENT("parent"),
    CHILD("child");

    private final String code;

    ChunkRole(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}

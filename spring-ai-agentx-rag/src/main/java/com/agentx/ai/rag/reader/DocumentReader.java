package com.agentx.ai.rag.reader;

/**
 * 文档读取 SPI。
 *
 * <p>读取层负责屏蔽本地文件、远程下载、对象存储等来源差异，统一输出
 * {@link RawDocument} 给解析层。
 *
 * @author bigchui
 */
public interface DocumentReader {

    RawDocument read();
}

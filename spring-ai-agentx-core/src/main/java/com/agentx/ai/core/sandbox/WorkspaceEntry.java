package com.agentx.ai.core.sandbox;

import java.nio.file.Path;
import java.util.Objects;

/**
 * workspace 物化条目。
 *
 * <p>每种条目描述一种「宿主机 → 容器」的文件同步方式，沙箱创建时统一物化到容器 workspace。
 *
 * <p>sealed 接口，当前三种实现：
 * <ul>
 *   <li>{@link DirCopy} — 把宿主机目录递归拷贝到容器目录</li>
 *   <li>{@link FileCopy} — 把单个宿主机文件拷贝到容器目标路径</li>
 *   <li>{@link InlineText} — 把内联文本写入容器目标路径（用于生成脚本 / 配置文件）</li>
 * </ul>
 *
 * @author bigchui
 */
public sealed interface WorkspaceEntry permits WorkspaceEntry.DirCopy, WorkspaceEntry.FileCopy, WorkspaceEntry.InlineText {

    /**
     * 容器内目标路径（绝对路径，如 {@code /workspace/skills}）。
     *
     * @return 容器路径
     */
    String containerPath();

    /**
     * 拷贝宿主机目录到容器。
     *
     * @param hostSource     宿主机源目录
     * @param containerPath  容器目标路径
     */
    record DirCopy(Path hostSource, String containerPath) implements WorkspaceEntry {
        public DirCopy {
            Objects.requireNonNull(hostSource, "hostSource must not be null");
            Objects.requireNonNull(containerPath, "containerPath must not be null");
        }
    }

    /**
     * 拷贝单个宿主机文件到容器。
     *
     * @param hostSource     宿主机源文件
     * @param containerPath  容器目标路径
     */
    record FileCopy(Path hostSource, String containerPath) implements WorkspaceEntry {
        public FileCopy {
            Objects.requireNonNull(hostSource, "hostSource must not be null");
            Objects.requireNonNull(containerPath, "containerPath must not be null");
        }
    }

    /**
     * 将内联文本写入容器目标路径。
     *
     * @param content        文本内容
     * @param containerPath  容器目标路径
     */
    record InlineText(String content, String containerPath) implements WorkspaceEntry {
        public InlineText {
            Objects.requireNonNull(content, "content must not be null");
            Objects.requireNonNull(containerPath, "containerPath must not be null");
        }
    }
}

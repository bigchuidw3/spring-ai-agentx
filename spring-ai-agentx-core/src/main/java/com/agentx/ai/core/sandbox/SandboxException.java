package com.agentx.ai.core.sandbox;

import com.agentx.ai.core.exception.AgentException;
import com.agentx.ai.core.exception.AgentErrorCode;

/**
 * 沙箱操作异常。
 *
 * <p>覆盖容器创建、销毁、快照、命令执行等全链路错误。异常 message 建议包含
 * scope_key 和 container_name，便于排障。
 *
 * @author bigchui
 */
public class SandboxException extends AgentException {

    /**
     * 镜像拉取失败（网络不可达、registry 鉴权失败、镜像不存在等）。
     */
    public static class ImagePullFailed extends SandboxException {
        public ImagePullFailed(String image, String message, Throwable cause) {
            super(AgentErrorCode.SANDBOX_IMAGE_PULL_FAILED,
                    "镜像拉取失败 [" + image + "]: " + message, cause);
        }
    }

    /**
     * 命令执行超时。
     */
    public static class ExecTimeout extends SandboxException {
        public ExecTimeout(String command, long timeoutMs) {
            super(AgentErrorCode.SANDBOX_EXEC_TIMEOUT,
                    "命令执行超时 (" + timeoutMs + "ms): " + command);
        }
    }

    public SandboxException(AgentErrorCode code, String message) {
        super(code, message);
    }

    public SandboxException(AgentErrorCode code, String message, Throwable cause) {
        super(code, message, cause);
    }
}

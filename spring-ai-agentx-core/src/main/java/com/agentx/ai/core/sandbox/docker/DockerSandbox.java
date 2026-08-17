package com.agentx.ai.core.sandbox.docker;

import com.agentx.ai.core.sandbox.ExecutionBackend;
import com.agentx.ai.core.sandbox.Sandbox;

/**
 * Docker 沙箱运行时实例。
 *
 * <p>持有容器标识、工作目录、{@link DockerClient} 引用与
 * {@link DockerExecutionBackend}。生命周期由 {@link DockerBackend} 管理。
 *
 * @author bigchui
 */
public class DockerSandbox implements Sandbox {

    private final DockerClient client;
    private final String containerName;
    private final String workingDirectory;
    private final DockerExecutionBackend executionBackend;

    public DockerSandbox(DockerClient client, String containerName,
                         String workingDirectory, DockerExecutionBackend executionBackend) {
        this.client = client;
        this.containerName = containerName;
        this.workingDirectory = workingDirectory;
        this.executionBackend = executionBackend;
    }

    @Override
    public String getContainerName() {
        return containerName;
    }

    @Override
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    @Override
    public boolean isRunning() {
        return client.isContainerRunning(containerName);
    }

    @Override
    public ExecutionBackend getExecutionBackend() {
        return executionBackend;
    }

    DockerClient getDockerClient() {
        return client;
    }
}

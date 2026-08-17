package com.agentx.ai.core.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * workspace 物化规格。
 *
 * <p>封装零到多个 {@link WorkspaceEntry}，沙箱创建时按顺序逐条物化到容器 workspace。
 *
 * <p>用法：
 * <pre>{@code
 * WorkspaceSpec spec = WorkspaceSpec.builder()
 *     .addDir(Path.of("./skills"), "/workspace/skills")
 *     .addFile(Path.of("./config.yaml"), "/workspace/config.yaml")
 *     .addInlineText("echo hello", "/workspace/test.sh")
 *     .build();
 * }</pre>
 *
 * @author bigchui
 */
public final class WorkspaceSpec {

    private final List<WorkspaceEntry> entries;

    private WorkspaceSpec(List<WorkspaceEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<WorkspaceEntry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static WorkspaceSpec empty() {
        return new WorkspaceSpec(Collections.emptyList());
    }

    public static final class Builder {

        private final List<WorkspaceEntry> entries = new ArrayList<>();

        public Builder addDir(Path hostSource, String containerPath) {
            entries.add(new WorkspaceEntry.DirCopy(hostSource, containerPath));
            return this;
        }

        public Builder addFile(Path hostSource, String containerPath) {
            entries.add(new WorkspaceEntry.FileCopy(hostSource, containerPath));
            return this;
        }

        public Builder addInlineText(String content, String containerPath) {
            entries.add(new WorkspaceEntry.InlineText(content, containerPath));
            return this;
        }

        public Builder add(WorkspaceEntry entry) {
            Objects.requireNonNull(entry, "entry must not be null");
            entries.add(entry);
            return this;
        }

        public WorkspaceSpec build() {
            return new WorkspaceSpec(entries);
        }
    }
}

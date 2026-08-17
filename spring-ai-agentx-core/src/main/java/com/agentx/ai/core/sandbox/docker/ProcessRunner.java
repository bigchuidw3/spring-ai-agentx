package com.agentx.ai.core.sandbox.docker;

import com.agentx.ai.core.exception.AgentErrorCode;
import com.agentx.ai.core.sandbox.SandboxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一的 ProcessBuilder 执行封装。
 *
 * <p>所有 docker CLI 调用必须经过本类，杜绝在 DockerClient 中重复编写
 * ProcessBuilder + drain + waitFor 模板。约束：
 * <ul>
 *   <li>命令用 {@code List<String>} 传递，由 OS 做参数边界，不做字符串拼接</li>
 *   <li>stdout / stderr 并行 drain，避免管道写满导致死锁</li>
 *   <li>所有调用有超时，超时后 {@code destroyForcibly}</li>
 *   <li>输出截断到 2MB，截断后追加标记</li>
 * </ul>
 *
 * @author bigchui
 */
public final class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    /** 单流最大输出字节数 */
    public static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    /** drain 线程工厂 */
    private static final ThreadFactory DRAIN_FACTORY = new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "agentx-sandbox-drain-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    };

    private ProcessRunner() {
    }

    /**
     * 执行命令，捕获 stdout / stderr 为字符串。
     *
     * @param command   命令（List 形式，第一个元素是可执行文件）
     * @param timeoutMs 超时毫秒
     * @return 进程结果
     */
    public static ProcessResult run(List<String> command, long timeoutMs) {
        final Process process = startProcess(command);

        ExecutorService drainer = Executors.newFixedThreadPool(2, DRAIN_FACTORY);
        Future<OutputChunk> stdoutFuture = drainer.submit(() -> readStream(process.getInputStream()));
        Future<OutputChunk> stderrFuture = drainer.submit(() -> readStream(process.getErrorStream()));

        try {
            boolean exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                drainer.shutdownNow();
                throw new SandboxException.ExecTimeout(String.join(" ", command), timeoutMs);
            }
            OutputChunk stdout = stdoutFuture.get();
            OutputChunk stderr = stderrFuture.get();
            drainer.shutdown();
            return new ProcessResult(process.exitValue(), stdout.content(), stderr.content());
        } catch (SandboxException e) {
            throw e;
        } catch (Exception e) {
            process.destroyForcibly();
            drainer.shutdownNow();
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "进程执行异常: " + e.getMessage(), e);
        }
    }

    /**
     * 执行命令，stdin / stdout 以流式管道连接（用于 tar 拷贝等大流量场景）。
     *
     * <p>调用方负责关闭传入的 InputStream / OutputStream。
     *
     * @param command   命令
     * @param stdin     喂给进程标准输入的流（可为 {@code null}）
     * @param stdout    接收进程标准输出的流（可为 {@code null}，此时仅 drain）
     * @param timeoutMs 超时毫秒
     * @return 进程退出码
     */
    public static int runWithStream(List<String> command, InputStream stdin,
                                     OutputStream stdout, long timeoutMs) {
        final Process process = startProcess(command);

        ExecutorService pool = Executors.newFixedThreadPool(3, DRAIN_FACTORY);

        Future<?> stdinFuture = stdin != null
                ? pool.submit(() -> transferAndClose(stdin, process.getOutputStream()))
                : pool.submit(() -> silentlyClose(process.getOutputStream()));

        Future<?> stdoutFuture = stdout != null
                ? pool.submit(() -> transfer(process.getInputStream(), stdout))
                : pool.submit(() -> readStream(process.getInputStream()));

        Future<OutputChunk> stderrFuture = pool.submit(() -> readStream(process.getErrorStream()));

        try {
            boolean exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                pool.shutdownNow();
                throw new SandboxException.ExecTimeout(String.join(" ", command), timeoutMs);
            }
            stdinFuture.get();
            stdoutFuture.get();
            OutputChunk stderr = stderrFuture.get();
            pool.shutdown();

            if (process.exitValue() != 0 && !stderr.content().isEmpty()) {
                log.debug("进程非零退出 [{}]: stderr={}", String.join(" ", command),
                        truncateForLog(stderr.content()));
            }
            return process.exitValue();
        } catch (SandboxException e) {
            throw e;
        } catch (Exception e) {
            process.destroyForcibly();
            pool.shutdownNow();
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "进程执行异常: " + e.getMessage(), e);
        }
    }

    /**
     * 启动进程，失败时抛 {@link SandboxException}。
     */
    private static Process startProcess(List<String> command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new SandboxException(AgentErrorCode.SANDBOX_CONTAINER_NOT_FOUND,
                    "无法启动进程: " + String.join(" ", command), e);
        }
    }

    /**
     * 读取 InputStream 到 MAX_OUTPUT_BYTES，超出部分持续排水到 EOF（防止管道
     * 写满阻塞进程导致超时误判），并在截断时于末尾追加标记。
     */
    private static OutputChunk readStream(InputStream in) {
        try {
            byte[] buf = new byte[MAX_OUTPUT_BYTES];
            int total = 0;
            int read;
            while (total < MAX_OUTPUT_BYTES && (read = in.read(buf, total, MAX_OUTPUT_BYTES - total)) != -1) {
                total += read;
            }
            boolean truncated = total == MAX_OUTPUT_BYTES;
            if (truncated) {
                byte[] discard = new byte[8192];
                while (in.read(discard) != -1) {
                    // 丢弃超出上限的输出，保持管道畅通
                }
            }
            String content = new String(buf, 0, total, StandardCharsets.UTF_8);
            if (truncated) {
                content = content + "\n...[输出超过 " + (MAX_OUTPUT_BYTES / 1024 / 1024) + "MB 已截断]";
            }
            return new OutputChunk(content, truncated);
        } catch (IOException e) {
            return new OutputChunk("", false);
        }
    }

    private static void transfer(InputStream in, OutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException e) {
            log.debug("流传输异常: {}", e.getMessage());
        }
    }

    private static void transferAndClose(InputStream in, OutputStream out) {
        try {
            in.transferTo(out);
        } catch (IOException e) {
            log.debug("流传输异常: {}", e.getMessage());
        } finally {
            silentlyClose(out);
        }
    }

    private static void silentlyClose(OutputStream out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // 静默关闭
        }
    }

    private static String truncateForLog(String s) {
        return s.length() > 4096 ? s.substring(0, 4096) + "...[truncated]" : s;
    }

    /**
     * 进程执行结果。
     *
     * @param exitCode 退出码
     * @param stdout   标准输出
     * @param stderr   标准错误
     */
    public record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    /** 内部读取结果 */
    private record OutputChunk(String content, boolean truncated) {
    }
}

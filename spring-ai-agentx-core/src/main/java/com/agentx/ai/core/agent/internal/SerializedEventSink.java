package com.agentx.ai.core.agent.internal;

import com.agentx.ai.core.model.AgentStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitFailureHandler;
import reactor.core.publisher.Sinks.EmitResult;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 并发安全的事件发射器：多线程生产事件入队，单一发射者串行 drain 到 unicast sink，
 * 避免多线程直接发射 unicast 导致 FAIL_NON_SERIALIZED 丢事件。
 *
 * @author bigchui
 */
public class SerializedEventSink implements Sinks.Many<AgentStreamEvent> {

    private static final Logger log = LoggerFactory.getLogger(SerializedEventSink.class);

    private final Sinks.Many<AgentStreamEvent> delegate;
    private final Queue<AgentStreamEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger wip = new AtomicInteger();
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private volatile boolean terminated = false;
    private volatile boolean completed = false;

    public SerializedEventSink(Sinks.Many<AgentStreamEvent> delegate) {
        this.delegate = delegate;
    }

    @Override
    public EmitResult tryEmitNext(AgentStreamEvent value) {
        if (terminated) {
            return EmitResult.FAIL_TERMINATED;
        }
        queue.offer(value);
        drain();
        return EmitResult.OK;
    }

    @Override
    public EmitResult tryEmitComplete() {
        return terminate(null);
    }

    @Override
    public EmitResult tryEmitError(Throwable e) {
        return terminate(e);
    }

    private EmitResult terminate(Throwable e) {
        if (terminated) {
            return EmitResult.FAIL_TERMINATED;
        }
        if (e != null) {
            error.set(e);
        }
        terminated = true;
        drain();
        return EmitResult.OK;
    }

    private void drain() {
        if (wip.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        for (; ; ) {
            AgentStreamEvent event;
            while ((event = queue.poll()) != null) {
                if (completed) {
                    continue;
                }
                if (delegate.tryEmitNext(event) != EmitResult.OK) {
                    log.warn("[SerializedEventSink] delegate emitNext failed");
                }
            }
            if (terminated && !completed) {
                completed = true;
                Throwable e = error.get();
                if (e != null) {
                    delegate.tryEmitError(e);
                } else {
                    delegate.tryEmitComplete();
                }
            }
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                break;
            }
        }
    }

    @Override
    public Flux<AgentStreamEvent> asFlux() {
        return delegate.asFlux();
    }

    @Override
    public int currentSubscriberCount() {
        return delegate.currentSubscriberCount();
    }

    @Override
    public void emitNext(AgentStreamEvent value, EmitFailureHandler failureHandler) {
        EmitResult result = tryEmitNext(value);
        if (result != EmitResult.OK) {
            throw new Sinks.EmissionException(result, "SerializedEventSink emitNext failed: " + result);
        }
    }

    @Override
    public void emitComplete(EmitFailureHandler failureHandler) {
        EmitResult result = tryEmitComplete();
        if (result != EmitResult.OK) {
            throw new Sinks.EmissionException(result, "SerializedEventSink emitComplete failed: " + result);
        }
    }

    @Override
    public void emitError(Throwable e, EmitFailureHandler failureHandler) {
        EmitResult result = tryEmitError(e);
        if (result != EmitResult.OK) {
            throw new Sinks.EmissionException(result, "SerializedEventSink emitError failed: " + result);
        }
    }

    @Override
    public Object scanUnsafe(Scannable.Attr key) {
        return null;
    }
}

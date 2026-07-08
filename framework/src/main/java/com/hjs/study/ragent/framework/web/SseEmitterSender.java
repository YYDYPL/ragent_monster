package com.hjs.study.ragent.framework.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class SseEmitterSender {


    private final SseEmitter emitter;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseEmitterSender(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(t -> closed.set(true));
    }

    public void sendEvent(String eventName, Object data){
        if (closed.get()){
            return;
        }

        try{
            if (eventName== null){
                emitter.send(data);
                return;
            }
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        }catch (Exception e){
            fail(e);
        }
    }

    /**
     * 正常完成并关闭 SSE 连接
     *
     * <p>
     * 使用 CAS 操作确保连接只被关闭一次，避免重复关闭导致的问题。
     * 因此该方法是幂等的，多次调用只有第一次会真正执行关闭逻辑
     * </p>
     */
    public void complete() {
        // 使用 CAS 原子操作，确保只关闭一次
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    /**
     * 异常结束并关闭 SSE 连接
     *
     * <p>
     * 当发生异常时调用此方法，会执行以下操作：
     * </p>
     * <ol>
     *   <li>关闭 SSE 连接并通知客户端异常信息</li>
     *   <li>不再抛出异常，避免在流式响应已开始后触发全局异常处理器导致响应冲突</li>
     * </ol>
     *
     * <p>
     * 这一点对流式输出特别重要，因为一旦 HTTP 响应已经开始写出，
     * 再走普通异常响应流程就可能出现“响应已提交”的冲突问题
     * </p>
     *
     * @param throwable 导致失败的异常对象
     */
    public void fail(Throwable throwable) {
        closeWithError(throwable);
        log.warn("SSE send failed", throwable);
    }

    /**
     * 内部方法：以异常方式关闭连接
     *
     * <p>
     * 使用 CAS 操作确保连接只被关闭一次，
     * 然后调用 {@link SseEmitter#completeWithError(Throwable)} 通知客户端连接异常终止
     * </p>
     *
     * @param throwable 导致连接关闭的异常对象
     */
    private void closeWithError(Throwable throwable) {
        // 使用 CAS 原子操作，确保只关闭一次
        if (closed.compareAndSet(false, true)) {
            emitter.completeWithError(throwable);
        }
    }
}

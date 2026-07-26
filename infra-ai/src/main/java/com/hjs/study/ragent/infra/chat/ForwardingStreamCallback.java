/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hjs.study.ragent.infra.chat;

import com.hjs.study.ragent.framework.convention.GroundingChunk;
import com.hjs.study.ragent.framework.convention.SourceRef;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 透传式 {@link StreamCallback} 装饰器 —— 终态 once-语义 + 首包钩子
 * <p>
 * onContent / onThinking / onReplyToMessageId / onSources / onGroundingChunks 直接透传给 delegate。
 * onComplete / onError 在透传后通过 CAS-once 触发 {@link #onFinish(boolean, Throwable)}，
 * 确保终态收尾逻辑（如 trace span 结束、资源清理）仅执行一次。
 * <p>
 * 额外提供：
 * <ul>
 *   <li>{@link #onFirstContent()} —— 首个 onContent 到达时触发一次（常用于记录用户感知 TTFT）</li>
 *   <li>{@link #finishExternally(boolean, Throwable)} —— 外部路径（如 cancel）触发的收尾入口</li>
 * </ul>
 * <p>
 * 子类只需覆写 {@link #onFinish(boolean, Throwable)} 实现具体的收尾逻辑
 */
public abstract class ForwardingStreamCallback implements StreamCallback {

    private final StreamCallback delegate;
    /** CAS 守卫：确保 onFinish 仅触发一次 */
    private final AtomicBoolean finished = new AtomicBoolean(false);
    /** CAS 守卫：确保 onFirstContent 仅触发一次 */
    private final AtomicBoolean firstContentSeen = new AtomicBoolean(false);

    protected ForwardingStreamCallback(StreamCallback delegate) {
        this.delegate = delegate;
    }

    /**
     * 透传 onContent，并在首次到达时触发 {@link #onFirstContent} 钩子。
     * 钩子异常被静默吞掉，确保不影响正常推流
     */
    @Override
    public final void onContent(String content) {
        if (firstContentSeen.compareAndSet(false, true)) {
            try {
                onFirstContent();
            } catch (Throwable ex) {
                // 钩子异常不能影响正常推流
            }
        }
        delegate.onContent(content);
    }

    @Override
    public final void onThinking(String content) {
        delegate.onThinking(content);
    }

    @Override
    public final void onReplyToMessageId(String messageId) {
        delegate.onReplyToMessageId(messageId);
    }

    @Override
    public final void onSources(List<SourceRef> sources) {
        delegate.onSources(sources);
    }

    @Override
    public final void onGroundingChunks(List<GroundingChunk> chunks) {
        delegate.onGroundingChunks(chunks);
    }

    /**
     * 流式响应到达「第一个 onContent」时触发一次，常用于记录用户感知首包 TTFT（Time To First Token）
     * 默认空实现，子类可按需覆写
     */
    protected void onFirstContent() {
    }

    /**
     * 流正常结束时透传 + 触发收尾
     */
    @Override
    public final void onComplete() {
        try {
            delegate.onComplete();
        } finally {
            finishOnce(true, null);
        }
    }

    /**
     * 流异常结束时透传 + 触发收尾
     */
    @Override
    public final void onError(Throwable error) {
        try {
            delegate.onError(error);
        } finally {
            finishOnce(false, error);
        }
    }

    /**
     * 外部路径（如 cancel）触发收尾，不再透传 delegate（因为 delegate 已在 cancel 时处理）
     * <p>
     * 与 onComplete/onError 不同，此方法不会再次调用 delegate.onError/onComplete，
     * 仅触发 {@link #onFinish} 做资源清理
     *
     * @param success 是否成功
     * @param error   失败时的异常
     */
    protected final void finishExternally(boolean success, Throwable error) {
        finishOnce(success, error);
    }

    /**
     * CAS-once 确保 {@link #onFinish} 仅触发一次（无论 onComplete/onError/cancel 哪个先到达）
     */
    private void finishOnce(boolean success, Throwable error) {
        if (!finished.compareAndSet(false, true)) {
            return; // 已触发过，幂等跳过
        }
        onFinish(success, error);
    }

    /**
     * 流式终态收尾，仅会触发一次
     *
     * @param success 是否成功结束
     * @param error   失败时的异常，成功为 null
     */
    protected abstract void onFinish(boolean success, Throwable error);
}

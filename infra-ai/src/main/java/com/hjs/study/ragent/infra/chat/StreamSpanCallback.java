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

import com.hjs.study.ragent.framework.trace.RagStreamTraceSupport.StreamSpan;

/**
 * 流式 Span 回调装饰器 —— 将 {@link StreamSpan} 的生命周期绑定到流式回调的终态事件
 * <p>
 * 继承 {@link ForwardingStreamCallback}，通过覆写 {@link #onFinish} 在流式终态
 * （onComplete / onError / cancel）时自动结束对应的 trace span 节点。
 * <p>
 * 这使得 {@code *-stream-chat} trace 节点可以记录从流式请求发起
 * 到 SSE 流结束的真实端到端耗时，而非仅记录同步发起阶段的耗时。
 * <p>
 * 额外提供 {@link #onCancel()} 方法处理取消场景：
 * 先尝试以 CANCELLED 状态结束 span（若 span 仍在 RUNNING），
 * 再调用 {@link ForwardingStreamCallback#finishExternally} 触发 {@link #onFinish} 收尾
 *
 * @see ForwardingStreamCallback 父类提供终态回调 once-语义
 * @see StreamSpan trace span 抽象
 */
public final class StreamSpanCallback extends ForwardingStreamCallback {

    private final StreamSpan span;

    public StreamSpanCallback(StreamCallback delegate, StreamSpan span) {
        super(delegate);
        this.span = span;
    }

    /**
     * 流式终态收尾：成功→finishSuccess，失败→finishError
     */
    @Override
    protected void onFinish(boolean success, Throwable error) {
        if (success) {
            span.finishSuccess();
        } else {
            span.finishError(error);
        }
    }

    /**
     * 取消时由外部调用方触发（如 {@link AbstractOpenAIStyleChatClient#doStreamChat} 的取消句柄）
     * <p>
     * 执行顺序：
     * <ol>
     *   <li>若 span 仍在 RUNNING 状态 → 以 CANCELLED 结束（避免 trace 行悬挂）</li>
     *   <li>调用 {@link ForwardingStreamCallback#finishExternally} → 触发 {@link #onFinish}</li>
     * </ol>
     */
    public void onCancel() {
        span.finishCancelledIfRunning();
        finishExternally(false, null);
    }
}

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

import com.hjs.study.ragent.infra.http.ModelClientErrorType;
import com.hjs.study.ragent.infra.http.ModelClientException;
import lombok.NoArgsConstructor;
import okhttp3.Call;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 流式任务异步执行器
 * 统一处理线程池提交、拒绝兜底和取消句柄构建逻辑
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class StreamAsyncExecutor {

    private static final String STREAM_BUSY_MESSAGE = "流式线程池繁忙";

    /**
     * 将流式任务提交到指定线程池异步执行
     * <p>
     * 执行流程：
     * <ol>
     *   <li>通过 {@link CompletableFuture#runAsync} 提交到线程池</li>
     *   <li>若线程池拒绝（RejectedExecutionException），取消 OkHttp Call 并回调 onError</li>
     *   <li>返回 {@link StreamCancellationHandle}：cancel 时同时设置 cancelled 标志 + 取消 OkHttp Call</li>
     * </ol>
     *
     * @param executor   线程池
     * @param call       OkHttp Call（用于 cancel 时释放连接）
     * @param callback   流式回调（线程池拒绝时通过 onError 通知）
     * @param streamTask 实际的 SSE 读取任务（接收 AtomicBoolean 取消信号）
     * @return 取消句柄
     */
    static StreamCancellationHandle submit(Executor executor,
                                           Call call,
                                           StreamCallback callback,
                                           Consumer<AtomicBoolean> streamTask) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        try {
            CompletableFuture.runAsync(() -> streamTask.accept(cancelled), executor);
        } catch (RejectedExecutionException ex) {
            call.cancel();
            callback.onError(new ModelClientException(STREAM_BUSY_MESSAGE, ModelClientErrorType.SERVER_ERROR, null, ex));
            return StreamCancellationHandles.noop();
        }
        return StreamCancellationHandles.fromOkHttp(call, cancelled);
    }
}

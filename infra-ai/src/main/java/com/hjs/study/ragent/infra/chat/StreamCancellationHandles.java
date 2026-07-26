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

import lombok.NoArgsConstructor;
import okhttp3.Call;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link StreamCancellationHandle} 工具类
 * <p>
 * 提供两种取消句柄的工厂方法：
 * <ul>
 *   <li>{@link #noop()} —— 空操作句柄（线程池拒绝等异常场景下使用）</li>
 *   <li>{@link #fromOkHttp(Call, AtomicBoolean)} —— OkHttp 取消句柄（正常流式场景）</li>
 * </ul>
 * 统一幂等取消语义：无论调用多少次 cancel()，底层取消操作只执行一次
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class StreamCancellationHandles {

    /** 全局共享的空操作取消句柄单例 */
    private static final StreamCancellationHandle NOOP = () -> {
    };

    /**
     * 返回空操作取消句柄，用于无法正常取消的异常场景（如线程池拒绝）
     */
    public static StreamCancellationHandle noop() {
        return NOOP;
    }

    /**
     * 创建基于 OkHttp Call 的取消句柄
     * <p>
     * cancel() 时的执行顺序：
     * <ol>
     *   <li>通过 AtomicBoolean CAS 保证幂等（仅首次调用生效）</li>
     *   <li>设置 cancelled 标志 → SSE 读取循环检测到后退出</li>
     *   <li>调用 OkHttp Call.cancel() → 释放底层 TCP 连接</li>
     * </ol>
     *
     * @param call      待取消的 OkHttp Call
     * @param cancelled 取消标志，SSE 循环通过此标志检测取消信号
     * @return 幂等的取消句柄
     */
    public static StreamCancellationHandle fromOkHttp(Call call, AtomicBoolean cancelled) {
        return new OkHttpCancellationHandle(call, cancelled);
    }

    /**
     * OkHttp 取消句柄实现：CAS-once 保证 cancel() 幂等
     */
    private static final class OkHttpCancellationHandle implements StreamCancellationHandle {

        private final Call call;
        private final AtomicBoolean cancelled;
        /** 确保 cancel() 只执行一次底层取消操作 */
        private final AtomicBoolean once = new AtomicBoolean(false);

        private OkHttpCancellationHandle(Call call, AtomicBoolean cancelled) {
            this.call = call;
            this.cancelled = cancelled;
        }

        @Override
        public void cancel() {
            if (!once.compareAndSet(false, true)) {
                return; // 已取消过，幂等返回
            }
            // 1. 信号标志：通知 SSE 循环退出
            if (cancelled != null) {
                cancelled.set(true);
            }
            // 2. 底层取消：释放 HTTP 连接
            if (call != null) {
                call.cancel();
            }
        }
    }
}

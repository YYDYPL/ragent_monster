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

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 流式首包探测桥接器 —— 阻塞等待首包 + 缓存/提交模式
 * <p>
 * 核心机制：
 * <ol>
 *   <li><b>探测阶段</b>：流式回调（onContent/onThinking/onComplete/onError）到达时，
 *       先完成 {@link CompletableFuture} 通知阻塞等待方，同时将回调动作暂存到 buffer，
 *       <em>不立即转发</em>给下游 callback</li>
 *   <li><b>阻塞等待</b>：调用方通过 {@link #awaitFirstPacket} 阻塞等待首包结果，
 *       结果可能是 SUCCESS / ERROR / TIMEOUT / NO_CONTENT 四种之一</li>
 *   <li><b>提交（commit）</b>：仅当探测结果为 SUCCESS 时才调用 {@link #commit()}，
 *       将 buffer 中暂存的所有回调一次性回放给下游；非 SUCCESS 结果丢弃 buffer</li>
 * </ol>
 * <p>
 * 设计意图：在流式路由场景中，需要先确认首包到达（TTFT 检测）再决定是否继续使用该模型。
 * 若首包超时或失败，则不向用户推送任何内容，直接切换下一个候选模型；
 * 若首包成功到达，则 "解锁" buffer，后续增量内容也开始实时转发
 *
 * @see RoutingLLMService 流式路由（使用本桥接器做首包探测）
 * @see LlmFirstPacketProbe 首包探测 bean（AOP trace 节点）
 */
public final class ProbeStreamBridge implements StreamCallback {

    /** 下游真实回调，仅在 commit 后才会收到事件 */
    private final StreamCallback downstream;
    /** 首包探测的 CompletableFuture，到达首个回调事件或终态时完成 */
    private final CompletableFuture<ProbeResult> probe = new CompletableFuture<>();
    /** buffer 的同步锁 */
    private final Object lock = new Object();
    /** 暂存未提交的回调动作（Runnable），commit 后一次性回放 */
    private final List<Runnable> buffer = new ArrayList<>();
    /** 是否已提交：true 表示首包成功，后续回调直接实时转发不再缓存 */
    private volatile boolean committed;

    ProbeStreamBridge(StreamCallback downstream) {
        this.downstream = downstream;
    }

    @Override
    public void onContent(String content) {
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onContent(content));
    }

    @Override
    public void onThinking(String content) {
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onThinking(content));
    }

    @Override
    public void onComplete() {
        probe.complete(ProbeResult.noContent());
        bufferOrDispatch(downstream::onComplete);
    }

    @Override
    public void onError(Throwable t) {
        probe.complete(ProbeResult.error(t));
        bufferOrDispatch(() -> downstream.onError(t));
    }

    /**
     * 阻塞等待首包探测结果，SUCCESS 时自动提交缓冲
     */
    ProbeResult awaitFirstPacket(long timeout, TimeUnit unit) throws InterruptedException {
        ProbeResult result;
        try {
            result = probe.get(timeout, unit);
        } catch (TimeoutException e) {
            return ProbeResult.timeout();
        } catch (ExecutionException e) {
            return ProbeResult.error(e.getCause());
        }

        if (result.isSuccess()) {
            commit();
        }
        return result;
    }

    /**
     * 探测完成 → commit：将 buffer 中的回调一次性回放给下游，后续回调直接实时转发
     */
    private void commit() {
        synchronized (lock) {
            if (committed) {
                return; // 防止重复提交
            }
            committed = true;
            buffer.forEach(Runnable::run); // 回放所有暂存回调
        }
    }

    /**
     * 缓冲或直接分发：未提交时暂存到 buffer，已提交时立即执行
     * <p>
     * 利用 committed 的 volatile 语义做快速路径：在同步块外先检查，
     * 减少锁竞争（大部分回调在 commit 后到达，走快速路径直接分发）
     */
    private void bufferOrDispatch(Runnable action) {
        boolean dispatchNow;
        synchronized (lock) {
            dispatchNow = committed;
            if (!dispatchNow) {
                buffer.add(action); // 探测阶段：暂存
            }
        }
        if (dispatchNow) {
            action.run(); // 提交后：直接执行（在同步块外执行，避免死锁）
        }
    }

    /**
     * 探测结果 —— 封装首包等待的四种可能结局
     */
    @Getter
    public static class ProbeResult {

        /** 探测结果类型枚举 */
        enum Type {SUCCESS, ERROR, TIMEOUT, NO_CONTENT}

        private final Type type;
        private final Throwable error;

        private ProbeResult(Type type, Throwable error) {
            this.type = type;
            this.error = error;
        }

        /** 首包成功到达（onContent 或 onThinking 触发） */
        static ProbeResult success() {
            return new ProbeResult(Type.SUCCESS, null);
        }

        /** 流式请求过程中抛出异常 */
        static ProbeResult error(Throwable t) {
            return new ProbeResult(Type.ERROR, t);
        }

        /** 在预算时间内未收到任何回调 */
        static ProbeResult timeout() {
            return new ProbeResult(Type.TIMEOUT, null);
        }

        /** 流正常结束但未产生任何内容（onComplete 在首包之前到达） */
        static ProbeResult noContent() {
            return new ProbeResult(Type.NO_CONTENT, null);
        }

        boolean isSuccess() {
            return type == Type.SUCCESS;
        }
    }
}

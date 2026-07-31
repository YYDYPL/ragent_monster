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

package com.hjs.study.ragent.core.parser.mineru;

import com.hjs.study.ragent.framework.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MinerU 共享轮询调度器。
 * <p>
 * B-lite 异步模型核心组件：把 HTTP 轮询从业务消费者线程剥离到独立调度池。
 * 全局 outstanding 并发由 {@link MinerUDocumentParser} 的分布式信号量控制。
 * <ul>
 *   <li>消费者线程仍阻塞 await(B-lite 本质,与真 B 区别)</li>
 *   <li>轮询动作由 4 个共享调度线程执行,数百个 outstanding 任务共用</li>
 * </ul>
 * <p>
 * 每个任务只持有一个 CompletableFuture 和一个轻量定时句柄，不为每个 batch 创建线程。
 * 调度线程会执行同步 HTTP 查询，因此实际吞吐仍受 HTTP 超时和固定线程数影响；全局任务数量
 * 则由外层 Redisson 许可限制。
 */
@Slf4j
@Component
public class MinerUPollingExecutor {

    /** 共享轮询线程数，不等于允许的 MinerU outstanding 任务数。 */
    private static final int SCHEDULER_THREADS = 4;

    /** Spring 停机时等待正在运行轮询回调结束的最长秒数。 */
    private static final long SHUTDOWN_AWAIT_SECONDS = 10;

    private final MinerUClient client;
    private final MinerUProperties properties;

    private ScheduledExecutorService scheduler;

    public MinerUPollingExecutor(MinerUClient client, MinerUProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 在 Bean 完成依赖注入后创建调度池。线程使用 daemon 模式，真正的生命周期仍由
     * {@link #shutdown()} 主动管理。
     */
    @PostConstruct
    void init() {
        this.scheduler = Executors.newScheduledThreadPool(SCHEDULER_THREADS, namedFactory());
        log.info("MinerUPollingExecutor 启动: schedulerThreads={}", SCHEDULER_THREADS);
    }

    /**
     * 注册一个 batch 的周期轮询并立即返回 Future。
     * <p>
     * 本方法自身不阻塞；当前 MinerUDocumentParser 会在返回的 Future 上调用 get，因此业务线程
     * 仍等待结果，但等待期间不执行 sleep 或 HTTP。Future 成功、失败或被取消时都会取消周期任务。
     *
     * @param batchId MinerU 分配的 batch_id
     * @param timeout 超时时长
     * @return Future，完成时携带 DONE 状态的 MinerUStatus（含 zipUrl）
     */
    public CompletableFuture<MinerUStatus> submitAndAwait(String batchId, Duration timeout) {
        if (batchId == null || batchId.isBlank()) {
            CompletableFuture<MinerUStatus> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ServiceException("batchId 不能为空"));
            return failed;
        }

        CompletableFuture<MinerUStatus> future = new CompletableFuture<>();
        Instant deadline = Instant.now().plus(timeout);

        // 使用单元素数组是为了让 poll lambda 与 whenComplete 都能访问“稍后才创建”的调度句柄。
        ScheduledFuture<?>[] holder = new ScheduledFuture[1];
        Runnable poll = () -> doPoll(batchId, future, deadline, holder);

        // 最小间隔 100ms(生产配置 5s,这里宽松下限让测试场景能用短间隔)
        long intervalMs = Math.max(100L, properties.getPollIntervalSeconds() * 1000L);
        // 首次查询也延迟一个 interval，给 MinerU 留出接收上传并建立任务的时间。
        holder[0] = scheduler.scheduleAtFixedRate(poll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        // future 完成时(无论成功失败)兜底取消调度任务
        future.whenComplete((status, throwable) -> {
            ScheduledFuture<?> task = holder[0];
            if (task != null) {
                task.cancel(false);
            }
        });

        return future;
    }

    /**
     * 执行一次状态查询。
     * <p>
     * DONE/FAILED 是终态；RUNNING/UNKNOWN 等待下一轮。单次网络异常不会立即结束 Future，只有异常
     * 持续到 deadline 才失败。complete 方法的原子返回值可防止并发回调重复终结同一 Future。
     */
    private void doPoll(String batchId,
                        CompletableFuture<MinerUStatus> future,
                        Instant deadline,
                        ScheduledFuture<?>[] holder) {
        if (future.isDone()) {
            return;
        }
        try {
            MinerUStatus status = client.queryResult(batchId);
            if (status.completed()) {
                complete(future, status, holder);
            } else if (status.failed()) {
                completeExceptionally(future, new ServiceException(
                                "MinerU 任务失败 batchId=" + batchId + " err=" + status.errorMessage()), holder);
            } else if (Instant.now().isAfter(deadline)) {
                completeExceptionally(future,
                        new TimeoutException("MinerU 任务超时 batchId=" + batchId), holder);
            }
        } catch (Exception e) {
            // 瞬时网络错误不立即终止,等下一轮重试;超时由 deadline 检查兜底
            log.warn("MinerU 轮询临时异常 batchId={}: {}", batchId, e.getMessage());
            if (Instant.now().isAfter(deadline)) {
                completeExceptionally(future,
                        new ServiceException("MinerU 轮询持续失败到超时 batchId=" + batchId + ": " + e.getMessage()),
                        holder);
            }
        }
    }

    /**
     * 原子完成 Future，并仅由获胜线程取消轮询。
     */
    private void complete(CompletableFuture<MinerUStatus> future,
                          MinerUStatus status,
                          ScheduledFuture<?>[] holder) {
        if (future.complete(status)) {
            cancelPolling(holder);
        }
    }

    /**
     * 原子异常完成 Future，并仅由获胜线程取消轮询。
     */
    private void completeExceptionally(CompletableFuture<MinerUStatus> future,
                                       Throwable error,
                                       ScheduledFuture<?>[] holder) {
        if (future.completeExceptionally(error)) {
            cancelPolling(holder);
        }
    }

    /**
     * 取消后续调度但不打断正在执行的 HTTP 查询。
     */
    private void cancelPolling(ScheduledFuture<?>[] holder) {
        ScheduledFuture<?> task = holder[0];
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * Spring 容器关闭时停止接收新轮询，等待短暂宽限后中断剩余任务。
     */
    @PreDestroy
    void shutdown() {
        if (scheduler == null) {
            return;
        }
        log.info("MinerUPollingExecutor 优雅停机中，等待 active 任务最多 {}s", SHUTDOWN_AWAIT_SECONDS);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("MinerUPollingExecutor 强制停机");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    /**
     * 创建带稳定前缀的 daemon 线程，便于线程转储和日志排障。
     */
    private static ThreadFactory namedFactory() {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, "minerU-poll-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}

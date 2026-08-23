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

package com.hjs.study.ragent.rag.projection.worker;

import cn.hutool.core.util.IdUtil;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hjs.study.ragent.rag.projection.config.ProjectionProperties;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionAttemptDO;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionAttemptMapper;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionAction;
import com.hjs.study.ragent.rag.projection.model.ProjectionStatus;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/** 统一执行状态机：版本 CAS、无限退避重试、不可重试阻断和执行审计。 */
@Slf4j
@Service
public class ProjectionJobExecutor {

    private final ProjectionLeaseService leaseService;
    private final ProjectionStateMapper stateMapper;
    private final ProjectionAttemptMapper attemptMapper;
    private final ProjectionProperties properties;
    private final MeterRegistry meterRegistry;
    private final Executor workerExecutor;
    private final Map<ProjectionTarget, ProjectionProcessor> processors = new EnumMap<>(ProjectionTarget.class);
    private final String owner = UUID.randomUUID().toString();
    private final Semaphore executionSlots = new Semaphore(4);
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "projection-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public ProjectionJobExecutor(ProjectionLeaseService leaseService,
                                 ProjectionStateMapper stateMapper,
                                 ProjectionAttemptMapper attemptMapper,
                                 ProjectionProperties properties,
                                 MeterRegistry meterRegistry,
                                 @Qualifier("projectionWorkerExecutor") Executor workerExecutor,
                                 List<ProjectionProcessor> processorList) {
        this.leaseService = leaseService;
        this.stateMapper = stateMapper;
        this.attemptMapper = attemptMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.workerExecutor = workerExecutor;
        processorList.forEach(processor -> processors.put(processor.target(), processor));
    }

    public void dispatchDue() {
        for (ProjectionTarget target : ProjectionTarget.values()) {
            int available = executionSlots.availablePermits();
            if (!processors.containsKey(target) || available <= 0) {
                continue;
            }
            leaseService.claimDue(target, owner, available).forEach(this::submitClaimed);
        }
    }

    public void executeById(String projectionId) {
        if (!executionSlots.tryAcquire()) {
            return;
        }
        ProjectionStateDO state = leaseService.claimById(projectionId, owner);
        if (state != null) {
            workerExecutor.execute(() -> executeAndReleaseSlot(state));
        } else {
            executionSlots.release();
        }
    }

    private void submitClaimed(ProjectionStateDO state) {
        if (!executionSlots.tryAcquire()) {
            releaseAsPending(state, null);
            return;
        }
        workerExecutor.execute(() -> executeAndReleaseSlot(state));
    }

    private void executeAndReleaseSlot(ProjectionStateDO state) {
        try {
            execute(state);
        } finally {
            executionSlots.release();
        }
    }

    public boolean supports(ProjectionTarget target) {
        return processors.containsKey(target);
    }

    private void execute(ProjectionStateDO state) {
        ProjectionTarget target = ProjectionTarget.valueOf(state.getTarget());
        ProjectionProcessor processor = processors.get(target);
        if (processor == null) {
            releaseAsPending(state, "后端未启用");
            return;
        }
        long started = System.currentTimeMillis();
        String resultName = ProjectionStatus.SUCCEEDED.name();
        String phase = state.getPhase();
        String error = null;
        long heartbeatSeconds = Math.max(10, properties.getLeaseSeconds() / 3L);
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> renewLeaseSafely(state.getId()), heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        try {
            leaseService.renew(state.getId(), owner);
            ProjectionExecutionResult result = processor.process(state);
            phase = result.getPhase();
            if (result.isComplete()) {
                boolean applied = markSucceeded(state, result);
                if (applied) {
                    try {
                        processor.afterSuccess(state, result);
                    } catch (Exception cleanupError) {
                        log.warn("投影成功后的旧版本物理清理失败，交由对账处理, projectionId={}", state.getId(), cleanupError);
                    }
                } else {
                    resultName = ProjectionStatus.PENDING.name();
                }
            } else {
                resultName = ProjectionStatus.WAIT_EXTERNAL.name();
                markWaiting(state, result);
            }
        } catch (Throwable ex) {
            boolean retryable = isRetryable(ex);
            resultName = retryable ? ProjectionStatus.RETRY_WAIT.name() : ProjectionStatus.BLOCKED.name();
            phase = ex instanceof ProjectionExecutionException projectionError
                    ? projectionError.getPhase() : phase;
            error = sanitize(ex);
            if (retryable) {
                markRetry(state, phase, error);
            } else {
                markBlocked(state, phase, error);
            }
            Counter.builder("rag.projection.failures")
                    .tag("target", state.getTarget())
                    .tag("retryable", Boolean.toString(retryable))
                    .register(meterRegistry)
                    .increment();
            log.warn("投影执行失败, projectionId={}, target={}, retryable={}",
                    state.getId(), state.getTarget(), retryable, ex);
        } finally {
            heartbeat.cancel(false);
            saveAttempt(state, resultName, phase, error, started);
        }
    }

    private boolean markSucceeded(ProjectionStateDO state, ProjectionExecutionResult result) {
        Date now = new Date();
        int updated = stateMapper.update(null, Wrappers.lambdaUpdate(ProjectionStateDO.class)
                .eq(ProjectionStateDO::getId, state.getId())
                .eq(ProjectionStateDO::getDesiredVersion, state.getDesiredVersion())
                .eq(ProjectionStateDO::getStatus, ProjectionStatus.RUNNING.name())
                .eq(ProjectionStateDO::getLeaseOwner, owner)
                .set(ProjectionStateDO::getAppliedVersion, state.getDesiredVersion())
                .set(ProjectionStateDO::getStatus, ProjectionStatus.SUCCEEDED.name())
                .set(ProjectionStateDO::getPhase, result.getPhase())
                .set(ProjectionStateDO::getExternalTrackId, result.getExternalTrackId())
                .set(ProjectionStateDO::getExternalTrackVersion, result.getExternalTrackVersion())
                .set(ProjectionStateDO::getExternalDocumentId, result.getExternalDocumentId())
                .set(ProjectionStateDO::getLeaseOwner, null)
                .set(ProjectionStateDO::getLeaseUntil, null)
                .set(ProjectionStateDO::getNextRetryTime, null)
                .set(ProjectionStateDO::getLastError, null)
                .set(ProjectionStateDO::getCompletedAt, now)
                .set(ProjectionStateDO::getUpdateTime, now));
        if (updated == 1) {
            long lagMs = state.getDesiredTime() == null ? 0L
                    : Math.max(0L, now.getTime() - state.getDesiredTime().getTime());
            Timer.builder("rag.projection.lag")
                    .tag("target", state.getTarget())
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(lagMs, TimeUnit.MILLISECONDS);
            return true;
        }
        stateMapper.update(null, Wrappers.lambdaUpdate(ProjectionStateDO.class)
                .eq(ProjectionStateDO::getId, state.getId())
                .eq(ProjectionStateDO::getStatus, ProjectionStatus.RUNNING.name())
                .eq(ProjectionStateDO::getLeaseOwner, owner)
                .set(ProjectionStateDO::getStatus, ProjectionStatus.PENDING.name())
                .set(ProjectionStateDO::getLeaseOwner, null)
                .set(ProjectionStateDO::getLeaseUntil, null)
                .set(ProjectionStateDO::getUpdateTime, now));
        return false;
    }

    private void markWaiting(ProjectionStateDO state, ProjectionExecutionResult result) {
        Date nextPoll = Date.from(Instant.now().plusSeconds(5));
        int updated = stateMapper.update(null, Wrappers.lambdaUpdate(ProjectionStateDO.class)
                .eq(ProjectionStateDO::getId, state.getId())
                .eq(ProjectionStateDO::getDesiredVersion, state.getDesiredVersion())
                .eq(ProjectionStateDO::getStatus, ProjectionStatus.RUNNING.name())
                .eq(ProjectionStateDO::getLeaseOwner, owner)
                .set(ProjectionStateDO::getStatus, ProjectionStatus.WAIT_EXTERNAL.name())
                .set(ProjectionStateDO::getPhase, result.getPhase())
                .set(ProjectionStateDO::getExternalTrackId, result.getExternalTrackId())
                .set(ProjectionStateDO::getExternalTrackVersion, result.getExternalTrackVersion())
                .set(ProjectionStateDO::getExternalDocumentId, result.getExternalDocumentId())
                .set(ProjectionStateDO::getNextRetryTime, nextPoll)
                .set(ProjectionStateDO::getLeaseOwner, null)
                .set(ProjectionStateDO::getLeaseUntil, null)
                .set(ProjectionStateDO::getUpdateTime, new Date()));
        if (updated == 0) {
            releaseAsPending(state, null);
        }
    }

    private void markRetry(ProjectionStateDO state, String phase, String error) {
        long exponent = Math.min(30, Math.max(0, state.getAttemptCount() - 1));
        long base = Math.min(properties.getMaxBackoffMs(), 1000L << Math.min(20, exponent));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, base / 4));
        Date retryAt = new Date(System.currentTimeMillis() + Math.min(properties.getMaxBackoffMs(), base + jitter));
        int updated = stateMapper.update(null, Wrappers.lambdaUpdate(ProjectionStateDO.class)
                .eq(ProjectionStateDO::getId, state.getId())
                .eq(ProjectionStateDO::getDesiredVersion, state.getDesiredVersion())
                .eq(ProjectionStateDO::getStatus, ProjectionStatus.RUNNING.name())
                .eq(ProjectionStateDO::getLeaseOwner, owner)
                .set(ProjectionStateDO::getStatus, ProjectionStatus.RETRY_WAIT.name())
                .set(ProjectionStateDO::getPhase, phase)
                .set(ProjectionStateDO::getNextRetryTime, retryAt)
                .set(ProjectionStateDO::getLastError, error)
                .set(ProjectionStateDO::getLeaseOwner, null)
                .set(ProjectionStateDO::getLeaseUntil, null)
                .set(ProjectionStateDO::getUpdateTime, new Date()));
        if (updated == 0) {
            releaseAsPending(state, null);
        }
    }

    private void markBlocked(ProjectionStateDO state, String phase, String error) {
        int updated = stateMapper.update(null, Wrappers.lambdaUpdate(ProjectionStateDO.class)
                .eq(ProjectionStateDO::getId, state.getId())
                .eq(ProjectionStateDO::getDesiredVersion, state.getDesiredVersion())
                .eq(ProjectionStateDO::getStatus, ProjectionStatus.RUNNING.name())
                .eq(ProjectionStateDO::getLeaseOwner, owner)
                .set(ProjectionStateDO::getStatus, ProjectionStatus.BLOCKED.name())
                .set(ProjectionStateDO::getPhase, phase)
                .set(ProjectionStateDO::getLastError, error)
                .set(ProjectionStateDO::getLeaseOwner, null)
                .set(ProjectionStateDO::getLeaseUntil, null)
                .set(ProjectionStateDO::getUpdateTime, new Date()));
        if (updated == 0) {
            releaseAsPending(state, null);
        }
    }

    private void releaseAsPending(ProjectionStateDO state, String reason) {
        stateMapper.update(null, Wrappers.lambdaUpdate(ProjectionStateDO.class)
                .eq(ProjectionStateDO::getId, state.getId())
                .eq(ProjectionStateDO::getStatus, ProjectionStatus.RUNNING.name())
                .eq(ProjectionStateDO::getLeaseOwner, owner)
                .set(ProjectionStateDO::getStatus, ProjectionStatus.PENDING.name())
                .set(ProjectionStateDO::getLastError, reason)
                .set(ProjectionStateDO::getLeaseOwner, null)
                .set(ProjectionStateDO::getLeaseUntil, null)
                .set(ProjectionStateDO::getUpdateTime, new Date()));
    }

    private void renewLeaseSafely(String projectionId) {
        try {
            if (!leaseService.renew(projectionId, owner)) {
                log.warn("投影租约续期失败，任务可能已被新版本取代, projectionId={}", projectionId);
            }
        } catch (RuntimeException ex) {
            log.warn("投影租约续期异常, projectionId={}", projectionId, ex);
        }
    }

    @PreDestroy
    public void shutdownHeartbeat() {
        heartbeatExecutor.shutdownNow();
    }

    private void saveAttempt(ProjectionStateDO state, String result, String phase, String error, long started) {
        long finished = System.currentTimeMillis();
        Timer.builder("rag.projection.execution")
                .tag("target", state.getTarget())
                .tag("result", result)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(finished - started, TimeUnit.MILLISECONDS);
        attemptMapper.insert(ProjectionAttemptDO.builder()
                .id(IdUtil.getSnowflakeNextIdStr())
                .projectionId(state.getId())
                .target(state.getTarget())
                .desiredVersion(state.getDesiredVersion())
                .attemptNo(state.getAttemptCount())
                .result(result)
                .phase(phase)
                .errorSummary(error)
                .startedAt(new Date(started))
                .finishedAt(new Date(finished))
                .durationMs(finished - started)
                .createTime(new Date())
                .build());
    }

    private boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ProjectionExecutionException projectionError) {
                return projectionError.isRetryable();
            }
            if (current instanceof SocketTimeoutException || current instanceof IOException) {
                return true;
            }
            if (current instanceof ElasticsearchException esError && esError.response() != null) {
                int status = esError.response().status();
                return status == 429 || status >= 500;
            }
            current = current.getCause();
        }
        return false;
    }

    private String sanitize(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        message = message.replaceAll("(?i)(api[-_ ]?key|authorization|token|password)\\s*[:=]\\s*[^,;\\s]+", "$1=***");
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}

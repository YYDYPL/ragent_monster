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

package com.hjs.study.ragent.rag.projection.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hjs.study.ragent.framework.context.UserContext;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.hjs.study.ragent.rag.projection.config.ProjectionProperties;
import com.hjs.study.ragent.rag.projection.controller.request.ProjectionBatchRequest;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionAttemptDO;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionBatchDO;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionAttemptMapper;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionBatchMapper;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionStatus;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import com.hjs.study.ragent.rag.projection.worker.ProjectionJobExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProjectionAdminService {

    private final ProjectionStateMapper stateMapper;
    private final ProjectionAttemptMapper attemptMapper;
    private final ProjectionBatchMapper batchMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final ProjectionCoordinator coordinator;
    private final ProjectionJobExecutor jobExecutor;
    private final ProjectionProperties properties;

    public Map<String, Object> summary() {
        List<ProjectionStateDO> states = stateMapper.selectList(Wrappers.lambdaQuery(ProjectionStateDO.class));
        Map<String, Long> counts = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        long esSloBreaches = 0;
        long graphSloBreaches = 0;
        for (ProjectionStateDO state : states) {
            counts.merge(state.getTarget() + ":" + state.getStatus(), 1L, Long::sum);
            if (!ProjectionStatus.SUCCEEDED.name().equals(state.getStatus()) && state.getDesiredTime() != null) {
                long lag = now - state.getDesiredTime().getTime();
                if (ProjectionTarget.ELASTICSEARCH.name().equals(state.getTarget()) && lag > 60_000) {
                    esSloBreaches++;
                }
                if (ProjectionTarget.LIGHTRAG.name().equals(state.getTarget()) && lag > 900_000) {
                    graphSloBreaches++;
                }
            }
        }
        return Map.of(
                "total", states.size(),
                "counts", counts,
                "esSloBreaches", esSloBreaches,
                "graphSloBreaches", graphSloBreaches,
                "generatedAt", new Date());
    }

    public IPage<ProjectionStateDO> page(int current, int size, String target, String status,
                                         String kbId, String docId, Date from, Date to) {
        LambdaQueryWrapper<ProjectionStateDO> query = Wrappers.lambdaQuery(ProjectionStateDO.class)
                .eq(StringUtils.hasText(target), ProjectionStateDO::getTarget, normalizeTarget(target))
                .eq(StringUtils.hasText(status), ProjectionStateDO::getStatus,
                        StringUtils.hasText(status) ? status.toUpperCase() : null)
                .eq(StringUtils.hasText(kbId), ProjectionStateDO::getKbId, kbId)
                .eq(StringUtils.hasText(docId), ProjectionStateDO::getDocId, docId)
                .ge(from != null, ProjectionStateDO::getDesiredTime, from)
                .le(to != null, ProjectionStateDO::getDesiredTime, to)
                .orderByDesc(ProjectionStateDO::getDesiredTime);
        return stateMapper.selectPage(new Page<>(Math.max(1, current), Math.min(200, Math.max(1, size))), query);
    }

    public List<ProjectionAttemptDO> attempts(String projectionId) {
        return attemptMapper.selectList(Wrappers.lambdaQuery(ProjectionAttemptDO.class)
                .eq(ProjectionAttemptDO::getProjectionId, projectionId)
                .orderByDesc(ProjectionAttemptDO::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    public void retry(String projectionId) {
        ProjectionStateDO state = stateMapper.selectById(projectionId);
        if (state == null) {
            throw new IllegalArgumentException("投影任务不存在");
        }
        if (ProjectionStatus.RUNNING.name().equals(state.getStatus())) {
            throw new IllegalStateException("运行中的任务不能手工重试");
        }
        stateMapper.update(null, Wrappers.lambdaUpdate(ProjectionStateDO.class)
                .eq(ProjectionStateDO::getId, projectionId)
                .set(ProjectionStateDO::getStatus, ProjectionStatus.PENDING.name())
                .set(ProjectionStateDO::getNextRetryTime, null)
                .set(ProjectionStateDO::getLastError, null)
                .set(ProjectionStateDO::getLeaseOwner, null)
                .set(ProjectionStateDO::getLeaseUntil, null)
                .set(ProjectionStateDO::getUpdateTime, new Date()));
        jobExecutor.executeById(projectionId);
    }

    @Transactional(rollbackFor = Exception.class)
    public String rebuild(ProjectionBatchRequest request) {
        return createBatch("REBUILD", request);
    }

    @Transactional(rollbackFor = Exception.class)
    public String reconcile(ProjectionBatchRequest request) {
        // 对账批次会重新从 PostgreSQL 生成期望投影，幂等地修复缺失、旧版或额外数据。
        return createBatch("RECONCILE", request);
    }

    protected String createBatch(String type, ProjectionBatchRequest request) {
        ProjectionBatchRequest actual = request == null ? new ProjectionBatchRequest() : request;
        List<ProjectionTarget> targets = resolveTargets(actual.getTarget());
        LambdaQueryWrapper<KnowledgeDocumentDO> query = Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                .eq(StringUtils.hasText(actual.getKbId()), KnowledgeDocumentDO::getKbId, actual.getKbId())
                .eq(StringUtils.hasText(actual.getDocId()), KnowledgeDocumentDO::getId, actual.getDocId())
                .orderByAsc(KnowledgeDocumentDO::getId);
        List<KnowledgeDocumentDO> documents = documentMapper.selectList(query);
        String batchId = IdUtil.getSnowflakeNextIdStr();
        int total = documents.size() * targets.size();
        int enqueued = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (KnowledgeDocumentDO document : documents) {
            for (ProjectionTarget target : targets) {
                try {
                    coordinator.requeueCurrentDocument(document.getId(), target);
                    enqueued++;
                } catch (RuntimeException ex) {
                    failed++;
                    if (errors.size() < 10) {
                        errors.add(document.getId() + "/" + target + ": " + ex.getMessage());
                    }
                }
            }
        }
        String status = enqueued == 0 ? (failed == 0 ? "SUCCEEDED" : "BLOCKED") : "RUNNING";
        Date now = new Date();
        batchMapper.insert(ProjectionBatchDO.builder()
                .id(batchId)
                .batchType(type)
                .target(targets.stream().map(Enum::name).sorted().reduce((a, b) -> a + "," + b).orElse(""))
                .kbId(actual.getKbId())
                .docId(actual.getDocId())
                .status(status)
                .totalCount(total)
                .successCount(0)
                .failedCount(failed)
                .pendingCount(enqueued)
                .requestedBy(UserContext.getUsername())
                .lastError(errors.isEmpty() ? null : String.join("\n", errors))
                .startedAt(now)
                .completedAt("RUNNING".equals(status) ? null : now)
                .createTime(now)
                .updateTime(now)
                .build());
        return batchId;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void refreshBatches() {
        List<ProjectionBatchDO> batches = batchMapper.selectList(Wrappers.lambdaQuery(ProjectionBatchDO.class)
                .eq(ProjectionBatchDO::getStatus, "RUNNING"));
        for (ProjectionBatchDO batch : batches) {
            LambdaQueryWrapper<ProjectionStateDO> query = Wrappers.lambdaQuery(ProjectionStateDO.class)
                    .eq(StringUtils.hasText(batch.getKbId()), ProjectionStateDO::getKbId, batch.getKbId())
                    .eq(StringUtils.hasText(batch.getDocId()), ProjectionStateDO::getDocId, batch.getDocId());
            List<String> targets = List.of(batch.getTarget().split(","));
            query.in(!targets.isEmpty(), ProjectionStateDO::getTarget, targets);
            List<ProjectionStateDO> states = stateMapper.selectList(query);
            int succeeded = (int) states.stream().filter(s -> ProjectionStatus.SUCCEEDED.name().equals(s.getStatus())).count();
            int blocked = (int) states.stream().filter(s -> ProjectionStatus.BLOCKED.name().equals(s.getStatus())).count();
            int pending = Math.max(0, states.size() - succeeded - blocked);
            String status = pending > 0 ? "RUNNING" : (blocked > 0 ? "BLOCKED" : "SUCCEEDED");
            batchMapper.update(null, Wrappers.lambdaUpdate(ProjectionBatchDO.class)
                    .eq(ProjectionBatchDO::getId, batch.getId())
                    .set(ProjectionBatchDO::getStatus, status)
                    .set(ProjectionBatchDO::getSuccessCount, succeeded)
                    .set(ProjectionBatchDO::getFailedCount, blocked)
                    .set(ProjectionBatchDO::getPendingCount, pending)
                    .set(ProjectionBatchDO::getCompletedAt, "RUNNING".equals(status) ? null : new Date())
                    .set(ProjectionBatchDO::getUpdateTime, new Date()));
        }
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeAuditHistory() {
        Date attemptBefore = Date.from(Instant.now().minus(properties.getAttemptRetentionDays(), ChronoUnit.DAYS));
        Date batchBefore = Date.from(Instant.now().minus(properties.getBatchRetentionDays(), ChronoUnit.DAYS));
        attemptMapper.delete(Wrappers.lambdaQuery(ProjectionAttemptDO.class)
                .lt(ProjectionAttemptDO::getCreateTime, attemptBefore));
        batchMapper.delete(Wrappers.lambdaQuery(ProjectionBatchDO.class)
                .lt(ProjectionBatchDO::getCreateTime, batchBefore)
                .ne(ProjectionBatchDO::getStatus, "RUNNING"));
    }

    private List<ProjectionTarget> resolveTargets(String target) {
        if (StringUtils.hasText(target)) {
            ProjectionTarget parsed = ProjectionTarget.valueOf(normalizeTarget(target));
            if (!jobExecutor.supports(parsed)) {
                throw new IllegalStateException(parsed + " 投影后端未启用");
            }
            return List.of(parsed);
        }
        return java.util.Arrays.stream(ProjectionTarget.values()).filter(jobExecutor::supports).toList();
    }

    private String normalizeTarget(String target) {
        String normalized = target.toUpperCase();
        return "ES".equals(normalized) ? ProjectionTarget.ELASTICSEARCH.name() : normalized;
    }
}

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

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hjs.study.ragent.rag.projection.config.ProjectionProperties;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionStatus;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 通过 PostgreSQL 行锁领取任务；租约过期后其他实例可安全接管。 */
@Service
@RequiredArgsConstructor
public class ProjectionLeaseService {

    private final ProjectionStateMapper stateMapper;
    private final ProjectionProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public List<ProjectionStateDO> claimDue(ProjectionTarget target, String owner) {
        return claimDue(target, owner, properties.getClaimBatchSize());
    }

    @Transactional(rollbackFor = Exception.class)
    public List<ProjectionStateDO> claimDue(ProjectionTarget target, String owner, int limit) {
        Date now = new Date();
        QueryWrapper<ProjectionStateDO> query = Wrappers.query();
        query.eq("target", target.name())
                .and(due -> due.eq("status", ProjectionStatus.PENDING.name())
                        .or(wait -> wait.in("status", ProjectionStatus.RETRY_WAIT.name(),
                                        ProjectionStatus.WAIT_EXTERNAL.name())
                                .and(time -> time.isNull("next_retry_time").or().le("next_retry_time", now)))
                        .or(expired -> expired.eq("status", ProjectionStatus.RUNNING.name())
                                .lt("lease_until", now)))
                .orderByAsc("desired_time")
                .last("LIMIT " + Math.max(1, Math.min(properties.getClaimBatchSize(), limit))
                        + " FOR UPDATE SKIP LOCKED");
        List<ProjectionStateDO> candidates = stateMapper.selectList(query);
        List<ProjectionStateDO> claimed = new ArrayList<>();
        Set<String> graphWorkspaces = new HashSet<>();
        for (ProjectionStateDO state : candidates) {
            if (target == ProjectionTarget.LIGHTRAG && !graphWorkspaces.add(state.getKbId())) {
                continue;
            }
            claimLocked(state, owner);
            claimed.add(state);
        }
        return claimed;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectionStateDO claimById(String projectionId, String owner) {
        ProjectionStateDO state = stateMapper.selectOne(Wrappers.<ProjectionStateDO>query()
                .eq("id", projectionId)
                .last("FOR UPDATE"));
        if (state == null || !isDue(state, new Date())) {
            return null;
        }
        claimLocked(state, owner);
        return state;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean renew(String projectionId, String owner) {
        Date leaseUntil = Date.from(Instant.now().plusSeconds(Math.max(30, properties.getLeaseSeconds())));
        return stateMapper.update(null, Wrappers.lambdaUpdate(ProjectionStateDO.class)
                .eq(ProjectionStateDO::getId, projectionId)
                .eq(ProjectionStateDO::getLeaseOwner, owner)
                .eq(ProjectionStateDO::getStatus, ProjectionStatus.RUNNING.name())
                .set(ProjectionStateDO::getLeaseUntil, leaseUntil)
                .set(ProjectionStateDO::getUpdateTime, new Date())) == 1;
    }

    private void claimLocked(ProjectionStateDO state, String owner) {
        Date now = new Date();
        Date leaseUntil = Date.from(now.toInstant().plusSeconds(Math.max(30, properties.getLeaseSeconds())));
        int nextAttempt = state.getAttemptCount() == null ? 1 : state.getAttemptCount() + 1;
        stateMapper.update(null, Wrappers.lambdaUpdate(ProjectionStateDO.class)
                .eq(ProjectionStateDO::getId, state.getId())
                .set(ProjectionStateDO::getStatus, ProjectionStatus.RUNNING.name())
                .set(ProjectionStateDO::getLeaseOwner, owner)
                .set(ProjectionStateDO::getLeaseUntil, leaseUntil)
                .set(ProjectionStateDO::getAttemptCount, nextAttempt)
                .set(ProjectionStateDO::getStartedAt, now)
                .set(ProjectionStateDO::getUpdateTime, now));
        state.setStatus(ProjectionStatus.RUNNING.name());
        state.setLeaseOwner(owner);
        state.setLeaseUntil(leaseUntil);
        state.setAttemptCount(nextAttempt);
        state.setStartedAt(now);
    }

    private boolean isDue(ProjectionStateDO state, Date now) {
        if (ProjectionStatus.PENDING.name().equals(state.getStatus())) {
            return true;
        }
        if (ProjectionStatus.RETRY_WAIT.name().equals(state.getStatus())
                || ProjectionStatus.WAIT_EXTERNAL.name().equals(state.getStatus())) {
            return state.getNextRetryTime() == null || !state.getNextRetryTime().after(now);
        }
        return ProjectionStatus.RUNNING.name().equals(state.getStatus())
                && state.getLeaseUntil() != null && state.getLeaseUntil().before(now);
    }
}

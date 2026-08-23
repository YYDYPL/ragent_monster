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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hjs.study.ragent.framework.convention.RetrievedChunk;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionAction;
import com.hjs.study.ragent.rag.projection.model.ProjectionScopeType;
import com.hjs.study.ragent.rag.projection.model.ProjectionStatus;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 批量验证“命中版本 = PostgreSQL 当前版本 = 投影成功版本”。 */
@Service
public class ProjectionFreshnessGate {

    private final KnowledgeDocumentMapper documentMapper;
    private final ProjectionStateMapper stateMapper;
    private final MeterRegistry meterRegistry;
    private final ThreadLocal<MutableStats> requestStats = ThreadLocal.withInitial(MutableStats::new);

    public ProjectionFreshnessGate(KnowledgeDocumentMapper documentMapper,
                                   ProjectionStateMapper stateMapper,
                                   MeterRegistry meterRegistry) {
        this.documentMapper = documentMapper;
        this.stateMapper = stateMapper;
        this.meterRegistry = meterRegistry;
    }

    public List<RetrievedChunk> filter(ProjectionTarget target, List<RetrievedChunk> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<String> docIds = new LinkedHashSet<>();
        candidates.forEach(chunk -> {
            if (chunk.getDocId() != null) {
                docIds.add(chunk.getDocId());
            }
        });
        if (docIds.isEmpty()) {
            recordDropped(target, candidates.size());
            return List.of();
        }
        Map<String, KnowledgeDocumentDO> documents = new HashMap<>();
        documentMapper.selectByIds(docIds).forEach(document -> documents.put(document.getId(), document));
        Map<String, ProjectionStateDO> states = new HashMap<>();
        stateMapper.selectList(Wrappers.lambdaQuery(ProjectionStateDO.class)
                        .eq(ProjectionStateDO::getTarget, target.name())
                        .eq(ProjectionStateDO::getScopeType, ProjectionScopeType.DOCUMENT.name())
                        .in(ProjectionStateDO::getDocId, docIds))
                .forEach(state -> states.put(state.getDocId(), state));

        states.values().forEach(state -> {
            if (state.getDesiredTime() != null) {
                long end = state.getCompletedAt() == null
                        ? System.currentTimeMillis() : state.getCompletedAt().getTime();
                requestStats.get().projectionLagMs = Math.max(requestStats.get().projectionLagMs,
                        Math.max(0, end - state.getDesiredTime().getTime()));
            }
        });

        List<RetrievedChunk> allFresh = candidates.stream()
                .filter(chunk -> isFresh(chunk, documents.get(chunk.getDocId()), states.get(chunk.getDocId())))
                .toList();
        recordDropped(target, Math.max(0, candidates.size() - allFresh.size()));
        return allFresh.stream().limit(limit).toList();
    }

    private boolean isFresh(RetrievedChunk hit, KnowledgeDocumentDO source, ProjectionStateDO state) {
        if (hit.getDocId() == null || hit.getKbId() == null || hit.getContentVersion() == null
                || source == null || state == null) {
            return false;
        }
        long version = hit.getContentVersion();
        return hit.getKbId().equals(source.getKbId())
                && Integer.valueOf(1).equals(source.getEnabled())
                && source.getContentVersion() != null && source.getContentVersion() == version
                && ProjectionStatus.SUCCEEDED.name().equals(state.getStatus())
                && ProjectionAction.UPSERT.name().equals(state.getAction())
                && state.getDesiredVersion() != null && state.getDesiredVersion() == version
                && state.getAppliedVersion() != null && state.getAppliedVersion() == version;
    }

    private void recordDropped(ProjectionTarget target, int count) {
        if (count <= 0) {
            return;
        }
        requestStats.get().staleDropped += count;
        Counter.builder("rag.search.stale.dropped")
                .tag("target", target.name())
                .register(meterRegistry)
                .increment(count);
    }

    public void resetStats() {
        requestStats.set(new MutableStats());
    }

    public GateStats consumeStats() {
        MutableStats value = requestStats.get();
        requestStats.remove();
        return new GateStats(value.staleDropped, value.projectionLagMs);
    }

    public record GateStats(int staleDropped, long projectionLagMs) {
    }

    private static final class MutableStats {
        private int staleDropped;
        private long projectionLagMs;
    }
}

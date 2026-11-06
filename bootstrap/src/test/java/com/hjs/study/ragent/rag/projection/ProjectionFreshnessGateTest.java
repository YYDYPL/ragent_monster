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

package com.hjs.study.ragent.rag.projection;

import com.hjs.study.ragent.framework.convention.RetrievedChunk;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionAction;
import com.hjs.study.ragent.rag.projection.model.ProjectionStatus;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import com.hjs.study.ragent.rag.projection.service.ProjectionFreshnessGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectionFreshnessGateTest {

    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    private final ProjectionStateMapper stateMapper = mock(ProjectionStateMapper.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProjectionFreshnessGate gate = new ProjectionFreshnessGate(documentMapper, stateMapper, registry);

    @Test
    void keepsOnlyHitsMatchingSourceAndAppliedProjectionVersion() {
        when(documentMapper.selectByIds(any())).thenReturn(List.of(document(2L)));
        when(stateMapper.selectList(any())).thenReturn(List.of(succeededState(2L)));

        gate.resetStats();
        List<RetrievedChunk> result = gate.filter(ProjectionTarget.ELASTICSEARCH,
                List.of(hit("new", 2L), hit("old", 1L)), 10);

        assertThat(result).extracting(RetrievedChunk::getId).containsExactly("new");
        assertThat(gate.consumeStats().staleDropped()).isEqualTo(1);
        assertThat(registry.counter("rag.search.stale.dropped", "target", "ELASTICSEARCH").count())
                .isEqualTo(1D);
    }

    @Test
    void dropsAllHitsWhileProjectionIsRetrying() {
        ProjectionStateDO state = succeededState(3L);
        state.setStatus(ProjectionStatus.RETRY_WAIT.name());
        when(documentMapper.selectByIds(any())).thenReturn(List.of(document(3L)));
        when(stateMapper.selectList(any())).thenReturn(List.of(state));

        gate.resetStats();
        List<RetrievedChunk> result = gate.filter(ProjectionTarget.LIGHTRAG,
                List.of(hit("candidate", 3L)), 10);

        assertThat(result).isEmpty();
        assertThat(gate.consumeStats().staleDropped()).isEqualTo(1);
    }

    private KnowledgeDocumentDO document(long version) {
        return KnowledgeDocumentDO.builder()
                .id("doc-1")
                .kbId("kb-1")
                .enabled(1)
                .contentVersion(version)
                .build();
    }

    private ProjectionStateDO succeededState(long version) {
        return ProjectionStateDO.builder()
                .id("projection-1")
                .target(ProjectionTarget.ELASTICSEARCH.name())
                .docId("doc-1")
                .desiredVersion(version)
                .appliedVersion(version)
                .action(ProjectionAction.UPSERT.name())
                .status(ProjectionStatus.SUCCEEDED.name())
                .desiredTime(new Date(System.currentTimeMillis() - 100))
                .completedAt(new Date())
                .build();
    }

    private RetrievedChunk hit(String id, long version) {
        return RetrievedChunk.builder()
                .id(id)
                .docId("doc-1")
                .kbId("kb-1")
                .contentVersion(version)
                .text(id)
                .build();
    }
}

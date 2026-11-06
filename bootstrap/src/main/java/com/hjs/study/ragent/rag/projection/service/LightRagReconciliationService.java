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
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.hjs.study.ragent.rag.core.graph.LightRagWorkspaceClient;
import com.hjs.study.ragent.rag.projection.dao.entity.GraphWorkspaceBindingDO;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.GraphWorkspaceBindingMapper;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionAction;
import com.hjs.study.ragent.rag.projection.model.ProjectionStatus;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 按源文档覆盖、版本、处理状态和内部文档 ID 清理 LightRAG 额外/过期记录。 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.graph", name = "type", havingValue = "lightrag")
public class LightRagReconciliationService {

    private static final Pattern SOURCE = Pattern.compile("^kb=([^;]+);doc=([^;]+);version=(\\d+)$");

    private final GraphWorkspaceBindingMapper workspaceMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final ProjectionStateMapper stateMapper;
    private final LightRagWorkspaceClient client;
    private final MeterRegistry meterRegistry;

    public void reconcileAll() {
        workspaceMapper.selectList(Wrappers.lambdaQuery(GraphWorkspaceBindingDO.class)
                        .eq(GraphWorkspaceBindingDO::getEnabled, 1))
                .forEach(this::reconcileWorkspaceSafely);
    }

    private void reconcileWorkspaceSafely(GraphWorkspaceBindingDO workspace) {
        try {
            reconcileWorkspace(workspace.getKbId());
        } catch (RuntimeException ex) {
            log.warn("LightRAG workspace 对账失败, kbId={}", workspace.getKbId(), ex);
        }
    }

    private void reconcileWorkspace(String kbId) {
        Map<String, KnowledgeDocumentDO> sources = documentMapper.selectList(
                        Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                                .eq(KnowledgeDocumentDO::getKbId, kbId))
                .stream().collect(Collectors.toMap(KnowledgeDocumentDO::getId, Function.identity()));
        Map<String, ProjectionStateDO> states = stateMapper.selectList(Wrappers.lambdaQuery(ProjectionStateDO.class)
                        .eq(ProjectionStateDO::getTarget, ProjectionTarget.LIGHTRAG.name())
                        .eq(ProjectionStateDO::getKbId, kbId)
                        .isNotNull(ProjectionStateDO::getDocId))
                .stream().collect(Collectors.toMap(ProjectionStateDO::getDocId, Function.identity(), (a, b) -> a));

        List<LightRagWorkspaceClient.DocumentReference> external = client.listDocuments(kbId);
        for (LightRagWorkspaceClient.DocumentReference document : external) {
            SourceRef ref = parse(document.fileSource());
            ProjectionStateDO state = ref == null ? null : states.get(ref.docId());
            KnowledgeDocumentDO source = ref == null ? null : sources.get(ref.docId());
            boolean active = state != null && (ProjectionStatus.RUNNING.name().equals(state.getStatus())
                    || ProjectionStatus.WAIT_EXTERNAL.name().equals(state.getStatus()));
            boolean failed = document.status() != null
                    && document.status().toLowerCase(Locale.ROOT).matches(".*(fail|error|cancel).*");
            boolean belongsToActiveMutation = active && ref != null && kbId.equals(ref.kbId()) && source != null;
            boolean succeededCurrent = ref != null && kbId.equals(ref.kbId()) && source != null
                    && source.getContentVersion() != null && source.getContentVersion() == ref.version()
                    && state != null && ProjectionAction.UPSERT.name().equals(state.getAction())
                    && ProjectionStatus.SUCCEEDED.name().equals(state.getStatus())
                    && document.documentId().equals(state.getExternalDocumentId());
            boolean current = belongsToActiveMutation || succeededCurrent;
            if (!current || failed) {
                try {
                    client.deleteDocument(kbId, document.documentId());
                    Counter.builder("rag.projection.reconcile.differences")
                            .tag("target", ProjectionTarget.LIGHTRAG.name())
                            .register(meterRegistry).increment();
                } catch (RuntimeException ex) {
                    log.warn("清理 LightRAG 额外或过期文档失败, kbId={}, externalId={}",
                            kbId, document.documentId(), ex);
                }
            }
        }
    }

    private SourceRef parse(String source) {
        if (source == null) {
            return null;
        }
        Matcher matcher = SOURCE.matcher(source);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new SourceRef(matcher.group(1), matcher.group(2), Long.parseLong(matcher.group(3)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record SourceRef(String kbId, String docId, long version) {
    }
}

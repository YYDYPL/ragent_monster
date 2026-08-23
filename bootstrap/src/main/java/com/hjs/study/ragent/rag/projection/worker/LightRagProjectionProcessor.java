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

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.hjs.study.ragent.rag.config.GraphProperties;
import com.hjs.study.ragent.rag.core.graph.LightRagWorkspaceClient;
import com.hjs.study.ragent.rag.projection.config.ProjectionProperties;
import com.hjs.study.ragent.rag.projection.dao.entity.GraphWorkspaceBindingDO;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.GraphWorkspaceBindingMapper;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionAction;
import com.hjs.study.ragent.rag.projection.model.ProjectionScopeType;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import com.hjs.study.ragent.rag.projection.service.GraphWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** LightRAG 每个 workspace 串行、整篇重建，并等待异步 track 状态完成。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.graph", name = "type", havingValue = "lightrag")
public class LightRagProjectionProcessor implements ProjectionProcessor {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final ProjectionStateMapper stateMapper;
    private final GraphWorkspaceBindingMapper bindingMapper;
    private final LightRagWorkspaceClient client;
    private final GraphWorkspaceService workspaceService;
    private final ProjectionProperties projectionProperties;
    private final GraphProperties graphProperties;

    @Override
    public ProjectionTarget target() {
        return ProjectionTarget.LIGHTRAG;
    }

    @Override
    public ProjectionExecutionResult process(ProjectionStateDO state) {
        String owner = state.getLeaseOwner();
        if (!workspaceService.acquire(state.getKbId(), owner, projectionProperties.getLeaseSeconds())) {
            throw new ProjectionExecutionException("workspace 正在处理其他变更", true, "LIGHTRAG_WORKSPACE_LOCK");
        }
        try {
            validateBinding(state);
            ProjectionAction action = ProjectionAction.valueOf(state.getAction());
            if (action == ProjectionAction.PURGE_KB) {
                purgeKnowledgeBase(state);
                return ProjectionExecutionResult.success();
            }
            if (action == ProjectionAction.DELETE) {
                client.deleteDocument(state.getKbId(), state.getExternalDocumentId());
                return ProjectionExecutionResult.success();
            }
            return upsert(state);
        } finally {
            workspaceService.release(state.getKbId(), owner);
        }
    }

    @Override
    public void afterSuccess(ProjectionStateDO state, ProjectionExecutionResult result) {
        if (ProjectionAction.PURGE_KB.name().equals(state.getAction())) {
            bindingMapper.update(null, Wrappers.lambdaUpdate(GraphWorkspaceBindingDO.class)
                    .eq(GraphWorkspaceBindingDO::getKbId, state.getKbId())
                    .eq(GraphWorkspaceBindingDO::getHealthStatus, "DISABLING")
                    .set(GraphWorkspaceBindingDO::getEnabled, 0)
                    .set(GraphWorkspaceBindingDO::getHealthStatus, "DISABLED")
                    .set(GraphWorkspaceBindingDO::getLeaseOwner, null)
                    .set(GraphWorkspaceBindingDO::getLeaseUntil, null));
        }
    }

    private ProjectionExecutionResult upsert(ProjectionStateDO state) {
        if (StrUtil.isNotBlank(state.getExternalTrackId())) {
            LightRagWorkspaceClient.TrackResult tracked = client.track(state.getKbId(), state.getExternalTrackId());
            if (tracked.state() == LightRagWorkspaceClient.TrackState.PROCESSING) {
                return ProjectionExecutionResult.waiting("LIGHTRAG_TRACK", state.getExternalTrackId(),
                        state.getExternalTrackVersion(), state.getExternalDocumentId());
            }
            if (tracked.state() == LightRagWorkspaceClient.TrackState.FAILED) {
                ProjectionExecutionException failure = pipelineFailure(tracked.error());
                if (failure.isRetryable()) {
                    stateMapper.update(null, Wrappers.lambdaUpdate(ProjectionStateDO.class)
                            .eq(ProjectionStateDO::getId, state.getId())
                            .eq(ProjectionStateDO::getDesiredVersion, state.getDesiredVersion())
                            .eq(ProjectionStateDO::getExternalTrackId, state.getExternalTrackId())
                            .set(ProjectionStateDO::getExternalTrackId, null)
                            .set(ProjectionStateDO::getExternalTrackVersion, null));
                }
                throw failure;
            }
            if (!state.getDesiredVersion().equals(state.getExternalTrackVersion())) {
                // 旧 track 必须先收敛，再按保存下来的内部文档 ID 删除并提交新版本。
                return ProjectionExecutionResult.waiting("LIGHTRAG_SUPERSEDED", null, null,
                        tracked.documentId());
            }
            return ProjectionExecutionResult.successWithExternal(state.getExternalTrackId(),
                    state.getExternalTrackVersion(), tracked.documentId());
        }

        KnowledgeDocumentDO document = documentMapper.selectById(state.getDocId());
        if (document == null || !state.getDesiredVersion().equals(document.getContentVersion())) {
            ProjectionStateDO latest = stateMapper.selectById(state.getId());
            if (latest != null && !state.getDesiredVersion().equals(latest.getDesiredVersion())) {
                return ProjectionExecutionResult.success();
            }
            throw new ProjectionExecutionException("文档版本与图谱任务不一致", false, "LIGHTRAG_LOAD_SOURCE");
        }
        List<KnowledgeChunkDO> chunks = chunkMapper.selectList(Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                .eq(KnowledgeChunkDO::getDocId, state.getDocId())
                .eq(KnowledgeChunkDO::getEnabled, 1)
                .orderByAsc(KnowledgeChunkDO::getChunkIndex));
        if (chunks.isEmpty()) {
            throw new ProjectionExecutionException("图谱 UPSERT 没有有效 Chunk", false, "LIGHTRAG_LOAD_SOURCE");
        }
        if (StrUtil.isNotBlank(state.getExternalDocumentId())) {
            client.deleteDocument(state.getKbId(), state.getExternalDocumentId());
        }
        String text = String.join("\n\n", chunks.stream().map(KnowledgeChunkDO::getContent).toList());
        String fileSource = "kb=" + state.getKbId() + ";doc=" + state.getDocId()
                + ";version=" + state.getDesiredVersion();
        LightRagWorkspaceClient.SubmitResult submitted = client.submit(state.getKbId(), text, fileSource);
        return ProjectionExecutionResult.waiting("LIGHTRAG_TRACK", submitted.trackId(),
                state.getDesiredVersion(), null);
    }

    private void purgeKnowledgeBase(ProjectionStateDO state) {
        List<ProjectionStateDO> documents = stateMapper.selectList(Wrappers.lambdaQuery(ProjectionStateDO.class)
                .eq(ProjectionStateDO::getTarget, ProjectionTarget.LIGHTRAG.name())
                .eq(ProjectionStateDO::getScopeType, ProjectionScopeType.DOCUMENT.name())
                .eq(ProjectionStateDO::getKbId, state.getKbId())
                .isNotNull(ProjectionStateDO::getExternalDocumentId));
        for (ProjectionStateDO document : documents) {
            client.deleteDocument(state.getKbId(), document.getExternalDocumentId());
        }
    }

    private void validateBinding(ProjectionStateDO state) {
        GraphWorkspaceBindingDO binding = bindingMapper.selectOne(Wrappers.lambdaQuery(GraphWorkspaceBindingDO.class)
                .eq(GraphWorkspaceBindingDO::getKbId, state.getKbId())
                .eq(GraphWorkspaceBindingDO::getEnabled, 1));
        boolean purgeWhileDisabling = ProjectionAction.PURGE_KB.name().equals(state.getAction())
                && binding != null && "DISABLING".equals(binding.getHealthStatus());
        boolean rebuilding = binding != null && "REBUILDING".equals(binding.getHealthStatus());
        if (binding == null || (!"HEALTHY".equals(binding.getHealthStatus())
                && !purgeWhileDisabling && !rebuilding)) {
            throw new ProjectionExecutionException("workspace 未启用或不健康", true, "LIGHTRAG_HEALTH");
        }
        if (!workspaceService.expectedFingerprint().equals(binding.getConfigFingerprint())
                || !binding.getConfigFingerprint().equals(state.getConfigFingerprint())) {
            throw new ProjectionExecutionException("workspace 配置指纹已变化，必须全量重建",
                    false, "LIGHTRAG_CONFIG");
        }
        if (!graphProperties.getLightrag().isSecurityApproved()) {
            throw new ProjectionExecutionException("LightRAG 安全版本尚未批准", false, "LIGHTRAG_SECURITY");
        }
    }

    private ProjectionExecutionException pipelineFailure(String error) {
        String normalized = StrUtil.blankToDefault(error, "pipeline failed").toLowerCase(Locale.ROOT);
        boolean nonRetryable = normalized.contains("auth") || normalized.contains("api key")
                || normalized.contains("dimension") || normalized.contains("model")
                || normalized.contains("configuration");
        return new ProjectionExecutionException(error, !nonRetryable, "LIGHTRAG_PIPELINE");
    }
}

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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hjs.study.ragent.framework.mq.producer.MessageQueueProducer;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.hjs.study.ragent.knowledge.enums.DocumentStatus;
import com.hjs.study.ragent.rag.config.GraphProperties;
import com.hjs.study.ragent.rag.config.KeywordProperties;
import com.hjs.study.ragent.rag.projection.config.ProjectionProperties;
import com.hjs.study.ragent.rag.projection.dao.entity.GraphWorkspaceBindingDO;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.GraphWorkspaceBindingMapper;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionAction;
import com.hjs.study.ragent.rag.projection.model.ProjectionScopeType;
import com.hjs.study.ragent.rag.projection.model.ProjectionStatus;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import com.hjs.study.ragent.rag.projection.model.ProjectionWakeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 在业务事务内更新文档版本并合并投影任务。任何外部系统调用都发生在事务提交之后。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectionCoordinator {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ProjectionStateMapper projectionStateMapper;
    private final GraphWorkspaceBindingMapper workspaceBindingMapper;
    private final MessageQueueProducer messageQueueProducer;
    private final ProjectionProperties projectionProperties;
    private final KeywordProperties keywordProperties;
    private final GraphProperties graphProperties;

    @Value("rag-projection-wakeup_topic${unique-name:}")
    private String wakeupTopic;

    /** 影响现有检索内容的文档/Chunk 变更。调用者必须已经处于业务事务中。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long documentChanged(String docId) {
        int updated = documentMapper.incrementContentVersion(docId);
        if (updated != 1) {
            throw new IllegalStateException("文档不存在，无法创建投影任务: " + docId);
        }
        KnowledgeDocumentDO document = documentMapper.selectById(docId);
        if (!projectionProperties.isEnabled()) {
            return document.getContentVersion();
        }
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(document.getKbId());
        if (knowledgeBase == null) {
            throw new IllegalStateException("知识库不存在，无法创建投影任务: " + document.getKbId());
        }
        long enabledChunks = chunkMapper.selectCount(Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                .eq(KnowledgeChunkDO::getDocId, docId)
                .eq(KnowledgeChunkDO::getEnabled, 1));
        ProjectionAction action = Integer.valueOf(1).equals(document.getEnabled())
                && DocumentStatus.SUCCESS.getCode().equals(document.getStatus())
                && enabledChunks > 0 ? ProjectionAction.UPSERT : ProjectionAction.DELETE;
        enqueueDocumentTargets(document, knowledgeBase.getCollectionName(), action);
        return document.getContentVersion();
    }

    /** 文档逻辑删除前调用，使 DELETE 与逻辑删除处在同一个数据库事务。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long documentDeleted(KnowledgeDocumentDO document, String collectionName) {
        int updated = documentMapper.incrementContentVersion(document.getId());
        if (updated != 1) {
            throw new IllegalStateException("文档不存在，无法创建删除投影: " + document.getId());
        }
        KnowledgeDocumentDO latest = documentMapper.selectById(document.getId());
        if (!projectionProperties.isEnabled()) {
            return latest.getContentVersion();
        }
        enqueueDocumentTargets(latest, collectionName, ProjectionAction.DELETE);
        return latest.getContentVersion();
    }

    /** 知识库删除前创建两个派生存储的清理任务。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void knowledgeBaseDeleted(String kbId, String collectionName) {
        if (!projectionProperties.isEnabled()) {
            return;
        }
        long version = System.currentTimeMillis();
        List<String> wakeups = new ArrayList<>();
        if (keywordEnabled()) {
            wakeups.add(merge(ProjectionTarget.ELASTICSEARCH, ProjectionScopeType.KNOWLEDGE_BASE,
                    kbId, kbId, collectionName, null, version, ProjectionAction.PURGE_KB, null));
        }
        if (graphEnabledForKnowledgeBase(kbId)) {
            wakeups.add(merge(ProjectionTarget.LIGHTRAG, ProjectionScopeType.KNOWLEDGE_BASE,
                    kbId, kbId, collectionName, null, version, ProjectionAction.PURGE_KB, null));
        }
        registerWakeups(wakeups);
    }

    /** 关闭图谱时先异步清空 workspace；清理完成后 Worker 才真正禁用绑定。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public String knowledgeGraphDisabled(String kbId, String collectionName, String fingerprint) {
        String projectionId = merge(ProjectionTarget.LIGHTRAG, ProjectionScopeType.KNOWLEDGE_BASE,
                kbId, kbId, collectionName, null, System.currentTimeMillis(),
                ProjectionAction.PURGE_KB, fingerprint);
        registerWakeups(List.of(projectionId));
        return projectionId;
    }

    /**
     * 管理端重建/对账使用当前版本重新排队，不伪造一次内容变更。
     * <p>
     * 批量重建会逐文档尝试多个投影目标。目标后端未启用时属于可预期的业务失败，
     * 不应把调用方的整个批事务标记为 rollback-only；否则外层捕获异常后，事务提交
     * 阶段仍会抛 UnexpectedRollbackException，页面只显示“系统执行出错”。
     */
    @Transactional(rollbackFor = Exception.class, noRollbackFor = IllegalStateException.class)
    public String requeueCurrentDocument(String docId, ProjectionTarget target) {
        KnowledgeDocumentDO document = documentMapper.selectById(docId);
        if (document == null) {
            throw new IllegalStateException("文档不存在: " + docId);
        }
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(document.getKbId());
        if (knowledgeBase == null) {
            throw new IllegalStateException("知识库不存在: " + document.getKbId());
        }
        long enabledChunks = chunkMapper.selectCount(Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                .eq(KnowledgeChunkDO::getDocId, docId)
                .eq(KnowledgeChunkDO::getEnabled, 1));
        ProjectionAction action = Integer.valueOf(1).equals(document.getEnabled())
                && DocumentStatus.SUCCESS.getCode().equals(document.getStatus())
                && enabledChunks > 0 ? ProjectionAction.UPSERT : ProjectionAction.DELETE;
        String fingerprint;
        if (target == ProjectionTarget.ELASTICSEARCH) {
            if (!keywordEnabled()) {
                throw new IllegalStateException("Elasticsearch 投影未启用");
            }
            fingerprint = "es-mapping-v2";
        } else {
            GraphWorkspaceBindingDO binding = findWorkspace(document.getKbId());
            if (!graphEnabledForKnowledgeBase(document.getKbId()) || binding == null) {
                throw new IllegalStateException("知识库 LightRAG workspace 未启用: " + document.getKbId());
            }
            fingerprint = binding.getConfigFingerprint();
        }
        String projectionId = merge(target, ProjectionScopeType.DOCUMENT, document.getId(),
                document.getKbId(), knowledgeBase.getCollectionName(), document.getId(),
                document.getContentVersion(), action, fingerprint);
        if (target == ProjectionTarget.LIGHTRAG) {
            // 同版本重建必须重新提交；保留 externalDocumentId 以便先精确删除旧文档。
            projectionStateMapper.update(null, Wrappers.lambdaUpdate(ProjectionStateDO.class)
                    .eq(ProjectionStateDO::getId, projectionId)
                    .notIn(ProjectionStateDO::getStatus,
                            ProjectionStatus.RUNNING.name(), ProjectionStatus.WAIT_EXTERNAL.name())
                    .set(ProjectionStateDO::getExternalTrackId, null)
                    .set(ProjectionStateDO::getExternalTrackVersion, null));
        }
        registerWakeups(List.of(projectionId));
        return projectionId;
    }

    private void enqueueDocumentTargets(KnowledgeDocumentDO document, String collectionName,
                                        ProjectionAction action) {
        List<String> wakeups = new ArrayList<>();
        if (keywordEnabled()) {
            wakeups.add(merge(ProjectionTarget.ELASTICSEARCH, ProjectionScopeType.DOCUMENT,
                    document.getId(), document.getKbId(), collectionName, document.getId(),
                    document.getContentVersion(), action, "es-mapping-v2"));
        }
        if (graphEnabledForKnowledgeBase(document.getKbId())) {
            GraphWorkspaceBindingDO binding = findWorkspace(document.getKbId());
            wakeups.add(merge(ProjectionTarget.LIGHTRAG, ProjectionScopeType.DOCUMENT,
                    document.getId(), document.getKbId(), collectionName, document.getId(),
                    document.getContentVersion(), action, binding.getConfigFingerprint()));
        }
        registerWakeups(wakeups);
    }

    private String merge(ProjectionTarget target, ProjectionScopeType scopeType, String entityId,
                         String kbId, String collectionName, String docId, long desiredVersion,
                         ProjectionAction action, String fingerprint) {
        ProjectionStateDO state = ProjectionStateDO.builder()
                .id(IdUtil.getSnowflakeNextIdStr())
                .target(target.name())
                .scopeType(scopeType.name())
                .entityId(entityId)
                .kbId(kbId)
                .collectionName(collectionName)
                .docId(docId)
                .desiredVersion(desiredVersion)
                .action(action.name())
                .status(ProjectionStatus.PENDING.name())
                .configFingerprint(fingerprint)
                .build();
        projectionStateMapper.mergeDesiredState(state);
        ProjectionStateDO merged = projectionStateMapper.selectOne(Wrappers.lambdaQuery(ProjectionStateDO.class)
                .eq(ProjectionStateDO::getTarget, target.name())
                .eq(ProjectionStateDO::getScopeType, scopeType.name())
                .eq(ProjectionStateDO::getEntityId, entityId));
        return merged.getId();
    }

    private boolean keywordEnabled() {
        return "es".equalsIgnoreCase(keywordProperties.getType());
    }

    private boolean graphEnabledForKnowledgeBase(String kbId) {
        return graphProperties.isLightrag() && graphProperties.getIngestion().isEnabled()
                && findWorkspace(kbId) != null;
    }

    private GraphWorkspaceBindingDO findWorkspace(String kbId) {
        return workspaceBindingMapper.selectOne(Wrappers.lambdaQuery(GraphWorkspaceBindingDO.class)
                .eq(GraphWorkspaceBindingDO::getKbId, kbId)
                .eq(GraphWorkspaceBindingDO::getEnabled, 1));
    }

    private void registerWakeups(List<String> projectionIds) {
        if (projectionIds.isEmpty()) {
            return;
        }
        Runnable send = () -> projectionIds.forEach(this::sendWakeupSafely);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void sendWakeupSafely(String projectionId) {
        try {
            messageQueueProducer.send(wakeupTopic, projectionId, "检索投影任务唤醒",
                    new ProjectionWakeMessage(projectionId));
        } catch (Exception ex) {
            // MQ 不是任务真源，发送失败由数据库定时扫描补偿。
            log.warn("发送投影唤醒消息失败，将由定时扫描补偿, projectionId={}", projectionId, ex);
        }
    }
}

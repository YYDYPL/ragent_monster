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
import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hjs.study.ragent.rag.config.GraphProperties;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import com.hjs.study.ragent.rag.core.graph.LightRagWorkspaceClient;
import com.hjs.study.ragent.rag.projection.dao.entity.GraphWorkspaceBindingDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.GraphWorkspaceBindingMapper;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/** workspace 地址、名称和配置指纹全部由服务端确定。 */
@Service
@RequiredArgsConstructor
public class GraphWorkspaceService {

    private final GraphWorkspaceBindingMapper bindingMapper;
    private final GraphProperties graphProperties;
    private final ObjectProvider<LightRagWorkspaceClient> clientProvider;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ProjectionCoordinator projectionCoordinator;
    private final ProjectionStateMapper projectionStateMapper;

    public List<GraphWorkspaceBindingDO> list() {
        backfillBindings();
        return bindingMapper.selectList(Wrappers.lambdaQuery(GraphWorkspaceBindingDO.class)
                .orderByAsc(GraphWorkspaceBindingDO::getKbId));
    }

    /** 升级已有数据库时为历史知识库补齐确定性绑定。 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 10_000)
    public void backfillBindings() {
        knowledgeBaseMapper.selectList(Wrappers.lambdaQuery(KnowledgeBaseDO.class))
                .forEach(knowledgeBase -> ensureBinding(knowledgeBase.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public GraphWorkspaceBindingDO ensureBinding(String kbId) {
        GraphWorkspaceBindingDO existing = find(kbId);
        if (existing != null) {
            return existing;
        }
        GraphWorkspaceBindingDO binding = GraphWorkspaceBindingDO.builder()
                .id(IdUtil.getSnowflakeNextIdStr())
                .kbId(kbId)
                .workspaceKey("kb_" + kbId)
                .enabled(0)
                .healthStatus("DISABLED")
                .build();
        try {
            bindingMapper.insert(binding);
        } catch (DuplicateKeyException ignored) {
            // 并发首次访问由另一个请求创建。
        }
        return find(kbId);
    }

    @Transactional(rollbackFor = Exception.class)
    public GraphWorkspaceBindingDO validate(String kbId) {
        GraphWorkspaceBindingDO binding = ensureBinding(kbId);
        String expected = expectedFingerprint();
        Date now = new Date();
        try {
            LightRagWorkspaceClient.HealthResult health = requireClient().health(kbId);
            boolean changed = binding.getConfigFingerprint() != null
                    && !binding.getConfigFingerprint().equals(expected);
            bindingMapper.update(null, Wrappers.lambdaUpdate(GraphWorkspaceBindingDO.class)
                    .eq(GraphWorkspaceBindingDO::getKbId, kbId)
                    .set(GraphWorkspaceBindingDO::getHealthStatus, changed ? "CONFIG_CHANGED" : "HEALTHY")
                    .set(GraphWorkspaceBindingDO::getLightragVersion, health.lightragVersion())
                    .set(GraphWorkspaceBindingDO::getApiVersion, health.apiVersion())
                    .set(GraphWorkspaceBindingDO::getConfigFingerprint, expected)
                    .set(GraphWorkspaceBindingDO::getLastHealthTime, now)
                    .set(GraphWorkspaceBindingDO::getLastError, changed
                            ? "模型、维度、API版本或镜像发生变化，必须全量重建" : null)
                    .set(GraphWorkspaceBindingDO::getUpdateTime, now));
        } catch (RuntimeException ex) {
            bindingMapper.update(null, Wrappers.lambdaUpdate(GraphWorkspaceBindingDO.class)
                    .eq(GraphWorkspaceBindingDO::getKbId, kbId)
                    .set(GraphWorkspaceBindingDO::getHealthStatus, "UNHEALTHY")
                    .set(GraphWorkspaceBindingDO::getLastHealthTime, now)
                    .set(GraphWorkspaceBindingDO::getLastError, safeMessage(ex))
                    .set(GraphWorkspaceBindingDO::getUpdateTime, now));
        }
        return find(kbId);
    }

    /** 已启用 workspace 的持续健康检查；失败只暂停检索，不清除启用意图。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void refreshEnabledHealth() {
        bindingMapper.selectList(Wrappers.lambdaQuery(GraphWorkspaceBindingDO.class)
                        .eq(GraphWorkspaceBindingDO::getEnabled, 1)
                        .notIn(GraphWorkspaceBindingDO::getHealthStatus,
                                "DISABLING", "REBUILDING", "CONFIG_CHANGED"))
                .forEach(binding -> validate(binding.getKbId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public GraphWorkspaceBindingDO enable(String kbId) {
        GraphWorkspaceBindingDO binding = validate(kbId);
        if (!"HEALTHY".equals(binding.getHealthStatus())
                && !"CONFIG_CHANGED".equals(binding.getHealthStatus())) {
            throw new IllegalStateException("workspace 未通过健康与配置校验");
        }
        bindingMapper.update(null, Wrappers.lambdaUpdate(GraphWorkspaceBindingDO.class)
                .eq(GraphWorkspaceBindingDO::getKbId, kbId)
                .set(GraphWorkspaceBindingDO::getEnabled, 1)
                .set(GraphWorkspaceBindingDO::getHealthStatus, "REBUILDING")
                .set(GraphWorkspaceBindingDO::getLastError, null)
                .set(GraphWorkspaceBindingDO::getUpdateTime, new Date()));
        documentMapper.selectList(Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getKbId, kbId))
                .forEach(document -> projectionCoordinator.requeueCurrentDocument(
                        document.getId(), ProjectionTarget.LIGHTRAG));
        return find(kbId);
    }

    /** 全部当前文档都以当前配置指纹成功后，才恢复该 workspace 的查询流量。 */
    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void completeRebuilds() {
        List<GraphWorkspaceBindingDO> rebuilding = bindingMapper.selectList(
                Wrappers.lambdaQuery(GraphWorkspaceBindingDO.class)
                        .eq(GraphWorkspaceBindingDO::getEnabled, 1)
                        .eq(GraphWorkspaceBindingDO::getHealthStatus, "REBUILDING"));
        for (GraphWorkspaceBindingDO binding : rebuilding) {
            long documentCount = documentMapper.selectCount(Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                    .eq(KnowledgeDocumentDO::getKbId, binding.getKbId()));
            long succeeded = projectionStateMapper.selectCount(Wrappers.<ProjectionStateDO>query()
                    .eq("target", ProjectionTarget.LIGHTRAG.name())
                    .eq("scope_type", "DOCUMENT")
                    .eq("kb_id", binding.getKbId())
                    .eq("config_fingerprint", binding.getConfigFingerprint())
                    .eq("status", ProjectionStatus.SUCCEEDED.name())
                    .apply("applied_version = desired_version"));
            if (succeeded >= documentCount) {
                bindingMapper.update(null, Wrappers.lambdaUpdate(GraphWorkspaceBindingDO.class)
                        .eq(GraphWorkspaceBindingDO::getKbId, binding.getKbId())
                        .eq(GraphWorkspaceBindingDO::getHealthStatus, "REBUILDING")
                        .set(GraphWorkspaceBindingDO::getHealthStatus, "HEALTHY")
                        .set(GraphWorkspaceBindingDO::getLastHealthTime, new Date())
                        .set(GraphWorkspaceBindingDO::getUpdateTime, new Date()));
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void disable(String kbId) {
        GraphWorkspaceBindingDO binding = find(kbId);
        if (binding == null || !Integer.valueOf(1).equals(binding.getEnabled())) {
            return;
        }
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(kbId);
        if (knowledgeBase == null) {
            throw new IllegalStateException("知识库不存在: " + kbId);
        }
        bindingMapper.update(null, Wrappers.lambdaUpdate(GraphWorkspaceBindingDO.class)
                .eq(GraphWorkspaceBindingDO::getKbId, kbId)
                .set(GraphWorkspaceBindingDO::getHealthStatus, "DISABLING")
                .set(GraphWorkspaceBindingDO::getUpdateTime, new Date()));
        projectionCoordinator.knowledgeGraphDisabled(kbId, knowledgeBase.getCollectionName(),
                binding.getConfigFingerprint());
    }

    public boolean acquire(String kbId, String owner, int leaseSeconds) {
        Date now = new Date();
        Date until = Date.from(Instant.now().plusSeconds(Math.max(30, leaseSeconds)));
        return bindingMapper.update(null, Wrappers.lambdaUpdate(GraphWorkspaceBindingDO.class)
                .eq(GraphWorkspaceBindingDO::getKbId, kbId)
                .eq(GraphWorkspaceBindingDO::getEnabled, 1)
                .and(lock -> lock.isNull(GraphWorkspaceBindingDO::getLeaseUntil)
                        .or().lt(GraphWorkspaceBindingDO::getLeaseUntil, now)
                        .or().eq(GraphWorkspaceBindingDO::getLeaseOwner, owner))
                .set(GraphWorkspaceBindingDO::getLeaseOwner, owner)
                .set(GraphWorkspaceBindingDO::getLeaseUntil, until)
                .set(GraphWorkspaceBindingDO::getUpdateTime, now)) == 1;
    }

    public void release(String kbId, String owner) {
        bindingMapper.update(null, Wrappers.lambdaUpdate(GraphWorkspaceBindingDO.class)
                .eq(GraphWorkspaceBindingDO::getKbId, kbId)
                .eq(GraphWorkspaceBindingDO::getLeaseOwner, owner)
                .set(GraphWorkspaceBindingDO::getLeaseOwner, null)
                .set(GraphWorkspaceBindingDO::getLeaseUntil, null)
                .set(GraphWorkspaceBindingDO::getUpdateTime, new Date()));
    }

    public String expectedFingerprint() {
        GraphProperties.LightRag config = graphProperties.getLightrag();
        String material = String.join("|",
                graphProperties.getEmbeddingModel(),
                String.valueOf(graphProperties.getEmbeddingDimension()),
                config.getApiVersion(),
                config.getImageDigest());
        return SecureUtil.sha256(material);
    }

    private GraphWorkspaceBindingDO find(String kbId) {
        return bindingMapper.selectOne(Wrappers.lambdaQuery(GraphWorkspaceBindingDO.class)
                .eq(GraphWorkspaceBindingDO::getKbId, kbId));
    }

    private LightRagWorkspaceClient requireClient() {
        LightRagWorkspaceClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("LightRAG 后端未启用");
        }
        return client;
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}

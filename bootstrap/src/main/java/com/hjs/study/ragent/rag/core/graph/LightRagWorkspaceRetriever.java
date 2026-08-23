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

package com.hjs.study.ragent.rag.core.graph;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.hjs.study.ragent.framework.convention.RetrievedChunk;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.hjs.study.ragent.rag.config.GraphProperties;
import com.hjs.study.ragent.rag.projection.dao.entity.GraphWorkspaceBindingDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.GraphWorkspaceBindingMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import com.hjs.study.ragent.rag.projection.service.ProjectionFreshnessGate;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按 KB 独立实例查询；无意图时最多对 20 个健康 workspace 并发 fan-out。 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "rag.graph", name = "type", havingValue = "lightrag")
public class LightRagWorkspaceRetriever {

    private static final Pattern SOURCE = Pattern.compile(
            "kb=([^;/\\\\]+);doc=([^;/\\\\]+);version=(\\d+)");

    private final LightRagWorkspaceClient client;
    private final GraphWorkspaceBindingMapper bindingMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ProjectionFreshnessGate freshnessGate;
    private final GraphProperties properties;
    private final Executor queryExecutor;
    private final MeterRegistry meterRegistry;

    public LightRagWorkspaceRetriever(LightRagWorkspaceClient client,
                                      GraphWorkspaceBindingMapper bindingMapper,
                                      KnowledgeBaseMapper knowledgeBaseMapper,
                                      ProjectionFreshnessGate freshnessGate,
                                      GraphProperties properties,
                                      @Qualifier("graphQueryExecutor") Executor queryExecutor,
                                      MeterRegistry meterRegistry) {
        this.client = client;
        this.bindingMapper = bindingMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.freshnessGate = freshnessGate;
        this.properties = properties;
        this.queryExecutor = queryExecutor;
        this.meterRegistry = meterRegistry;
    }

    public RetrievalResult retrieve(String question, String mode, int topK,
                                    List<String> intentCollections) {
        List<GraphWorkspaceBindingDO> workspaces = resolveWorkspaces(intentCollections);
        if (workspaces.isEmpty()) {
            return new RetrievalResult(List.of(), "no-healthy-workspace");
        }
        List<String> degraded = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<List<RetrievedChunk>>> futures = workspaces.stream()
                .map(workspace -> CompletableFuture.supplyAsync(
                        () -> queryWorkspace(workspace.getKbId(), question, mode, topK), queryExecutor)
                        .exceptionally(error -> {
                            recordDegraded(workspace.getKbId(), error, degraded);
                            return List.of();
                        }))
                .toList();
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        try {
            all.get(Math.max(1000, properties.getLightrag().getFanoutTimeoutMs()), TimeUnit.MILLISECONDS);
        } catch (Exception timeout) {
            futures.stream().filter(future -> !future.isDone()).forEach(future -> future.cancel(true));
            recordDegraded("fanout", timeout, degraded);
        }
        List<RetrievedChunk> candidates = new ArrayList<>();
        futures.stream().filter(CompletableFuture::isDone).filter(future -> !future.isCancelled())
                .forEach(future -> candidates.addAll(future.getNow(List.of())));
        candidates.sort(Comparator.comparing(RetrievedChunk::getScore,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<RetrievedChunk> fresh = freshnessGate.filter(ProjectionTarget.LIGHTRAG, candidates, topK);
        return new RetrievalResult(fresh, degraded.isEmpty() ? null : String.join("; ", degraded));
    }

    private List<GraphWorkspaceBindingDO> resolveWorkspaces(List<String> intentCollections) {
        List<String> kbIds = null;
        if (intentCollections != null && !intentCollections.isEmpty()) {
            kbIds = knowledgeBaseMapper.selectList(Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                            .in(KnowledgeBaseDO::getCollectionName, intentCollections))
                    .stream().map(KnowledgeBaseDO::getId).toList();
            if (kbIds.isEmpty()) {
                return List.of();
            }
        }
        var query = Wrappers.lambdaQuery(GraphWorkspaceBindingDO.class)
                .eq(GraphWorkspaceBindingDO::getEnabled, 1)
                .eq(GraphWorkspaceBindingDO::getHealthStatus, "HEALTHY")
                .orderByAsc(GraphWorkspaceBindingDO::getKbId)
                .last("LIMIT " + Math.min(20, Math.max(1, properties.getLightrag().getMaxWorkspaces())));
        if (kbIds != null) {
            query.in(GraphWorkspaceBindingDO::getKbId, kbIds);
        }
        return bindingMapper.selectList(query);
    }

    private List<RetrievedChunk> queryWorkspace(String kbId, String question, String mode, int topK) {
        JsonNode root = client.query(kbId, question, mode, topK);
        JsonNode references = root == null ? null : root.path("references");
        if (references == null || !references.isArray()) {
            return List.of();
        }
        List<RetrievedChunk> result = new ArrayList<>();
        int rank = 0;
        for (JsonNode reference : references) {
            String source = reference.path("file_source").asText(reference.path("file_path").asText(""));
            Matcher matcher = SOURCE.matcher(source);
            if (!matcher.find()) {
                continue;
            }
            String sourceKb = matcher.group(1);
            String docId = matcher.group(2);
            long version = Long.parseLong(matcher.group(3));
            if (!kbId.equals(sourceKb)) {
                continue;
            }
            String text = referenceText(reference);
            if (StrUtil.isBlank(text)) {
                continue;
            }
            String refId = reference.path("reference_id").asText(String.valueOf(rank));
            result.add(RetrievedChunk.builder()
                    .id("graph:" + kbId + ":" + refId)
                    .text(text)
                    .score(1.0f / (rank + 1))
                    .kbId(kbId)
                    .docId(docId)
                    .contentVersion(version)
                    .build());
            rank++;
        }
        return result;
    }

    private String referenceText(JsonNode reference) {
        JsonNode content = reference.path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        content.forEach(node -> {
            if (node.isTextual() && StrUtil.isNotBlank(node.asText())) {
                parts.add(node.asText());
            }
        });
        return String.join("\n", parts);
    }

    private void recordDegraded(String workspace, Throwable error, List<String> degraded) {
        meterRegistry.counter("rag.graph.query.degraded", "workspace", workspace).increment();
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        degraded.add(workspace + ":" + reason);
        log.warn("LightRAG workspace 查询降级, workspace={}, reason={}", workspace,
                reason);
    }

    public record RetrievalResult(List<RetrievedChunk> chunks, String degradedReason) {
    }
}

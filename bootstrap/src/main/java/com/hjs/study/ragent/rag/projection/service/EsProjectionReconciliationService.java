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

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregation;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.hjs.study.ragent.knowledge.enums.DocumentStatus;
import com.hjs.study.ragent.rag.config.KeywordProperties;
import com.hjs.study.ragent.rag.core.keyword.EsKeywordIndexService;
import com.hjs.study.ragent.rag.core.keyword.EsProjectionFingerprint;
import com.hjs.study.ragent.rag.core.keyword.KeywordBackendHealth;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionAction;
import com.hjs.study.ragent.rag.projection.model.ProjectionScopeType;
import com.hjs.study.ragent.rag.projection.model.ProjectionStatus;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 使用 composite aggregation 分页核对 ES 与 PostgreSQL 的文档版本、有效 Chunk 数和内容哈希。
 * 仅不一致文档进入重投；每周全量重放仍作为独立兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
public class EsProjectionReconciliationService {

    private static final String AGGREGATION = "projection_chunks";
    private static final int PAGE_SIZE = 1_000;

    private final ElasticsearchClient esClient;
    private final KeywordProperties properties;
    private final KeywordBackendHealth backendHealth;
    private final EsKeywordIndexService indexService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final ProjectionStateMapper stateMapper;
    private final ProjectionCoordinator coordinator;
    private final MeterRegistry meterRegistry;

    public int reconcile() {
        if (!backendHealth.isHealthy()) {
            throw new IllegalStateException("Elasticsearch 关键词通道不健康: " + backendHealth.getReason());
        }
        Map<DocumentKey, ExternalDocumentBuilder> externalBuilders = readExternalProjection();
        Map<DocumentKey, ExternalDocument> external = externalBuilders.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().finish()));

        Map<String, KnowledgeBaseDO> knowledgeBases = knowledgeBaseMapper.selectList(
                        Wrappers.lambdaQuery(KnowledgeBaseDO.class))
                .stream().collect(Collectors.toMap(KnowledgeBaseDO::getId, Function.identity()));
        List<KnowledgeDocumentDO> documents = documentMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class));
        Map<String, List<KnowledgeChunkDO>> chunksByDocument = chunkMapper.selectList(
                        Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                                .eq(KnowledgeChunkDO::getEnabled, 1))
                .stream().collect(Collectors.groupingBy(KnowledgeChunkDO::getDocId));
        Map<String, ProjectionStateDO> states = stateMapper.selectList(
                        Wrappers.lambdaQuery(ProjectionStateDO.class)
                                .eq(ProjectionStateDO::getTarget, ProjectionTarget.ELASTICSEARCH.name())
                                .eq(ProjectionStateDO::getScopeType, ProjectionScopeType.DOCUMENT.name()))
                .stream().collect(Collectors.toMap(ProjectionStateDO::getDocId, Function.identity(), (a, b) -> a));

        int differences = 0;
        for (KnowledgeDocumentDO document : documents) {
            KnowledgeBaseDO knowledgeBase = knowledgeBases.get(document.getKbId());
            if (knowledgeBase == null) {
                continue;
            }
            DocumentKey key = new DocumentKey(document.getKbId(), knowledgeBase.getCollectionName(), document.getId());
            ExternalDocument actual = external.remove(key);
            List<KnowledgeChunkDO> chunks = chunksByDocument.getOrDefault(document.getId(), List.of());
            boolean shouldExist = Integer.valueOf(1).equals(document.getEnabled())
                    && DocumentStatus.SUCCESS.getCode().equals(document.getStatus()) && !chunks.isEmpty();
            if (!shouldExist) {
                if (actual != null) {
                    differences++;
                    indexService.deleteDocumentIndex(key.collectionName(), key.docId());
                }
                continue;
            }

            ProjectionSignature expected = signature(chunks);
            ProjectionSignature indexed = actual == null ? null : actual.onlyVersion(document.getContentVersion());
            boolean bytesMatch = actual != null && actual.versionCount() == 1 && expected.equals(indexed);
            boolean stateMatches = stateMatches(states.get(document.getId()), document.getContentVersion());
            if (!bytesMatch || !stateMatches) {
                differences++;
                coordinator.requeueCurrentDocument(document.getId(), ProjectionTarget.ELASTICSEARCH);
            }
        }

        // 数据库已删除的文档，或知识库 collection 发生异常漂移时，直接移除额外投影。
        for (DocumentKey extra : external.keySet()) {
            differences++;
            indexService.deleteDocumentIndex(extra.collectionName(), extra.docId());
        }
        if (differences > 0) {
            Counter.builder("rag.projection.reconcile.differences")
                    .tag("target", ProjectionTarget.ELASTICSEARCH.name())
                    .register(meterRegistry).increment(differences);
        }
        log.info("Elasticsearch composite 对账完成, differences={}", differences);
        return differences;
    }

    private Map<DocumentKey, ExternalDocumentBuilder> readExternalProjection() {
        Map<DocumentKey, ExternalDocumentBuilder> documents = new LinkedHashMap<>();
        Map<String, FieldValue> after = Map.of();
        do {
            CompositeAggregation.Builder composite = new CompositeAggregation.Builder()
                    .size(PAGE_SIZE)
                    .sources(compositeSources());
            if (!after.isEmpty()) {
                composite.after(after);
            }
            SearchRequest request = new SearchRequest.Builder()
                    .index(properties.writeIndex())
                    .size(0)
                    .aggregations(AGGREGATION, new Aggregation.Builder().composite(composite.build()).build())
                    .build();
            try {
                SearchResponse<Map> response = esClient.search(request, Map.class);
                CompositeAggregate aggregate = response.aggregations().get(AGGREGATION).composite();
                List<CompositeBucket> buckets = aggregate.buckets().array();
                for (CompositeBucket bucket : buckets) {
                    addBucket(documents, bucket);
                }
                after = buckets.isEmpty() ? Map.of() : aggregate.afterKey();
            } catch (IOException ex) {
                throw new IllegalStateException("读取 Elasticsearch composite 投影失败", ex);
            }
        } while (!after.isEmpty());
        return documents;
    }

    private List<Map<String, CompositeAggregationSource>> compositeSources() {
        return List.of(
                source("kb", "kb_id", false),
                source("collection", "collection_name", false),
                source("doc", "doc_id", false),
                source("version", "content_version", false),
                source("chunk_index", "chunk_index", true),
                source("chunk_id", "chunk_id", false),
                source("content_hash", "content_hash", true));
    }

    private Map<String, CompositeAggregationSource> source(String name, String field, boolean missingBucket) {
        return Map.of(name, CompositeAggregationSource.of(source -> source
                .terms(terms -> terms.field(field).missingBucket(missingBucket))));
    }

    private void addBucket(Map<DocumentKey, ExternalDocumentBuilder> documents, CompositeBucket bucket) {
        Map<String, FieldValue> key = bucket.key();
        DocumentKey documentKey = new DocumentKey(string(key.get("kb")), string(key.get("collection")),
                string(key.get("doc")));
        long version = number(key.get("version"));
        ExternalDocumentBuilder document = documents.computeIfAbsent(documentKey,
                ignored -> new ExternalDocumentBuilder());
        document.add(version, nullableNumber(key.get("chunk_index")), string(key.get("chunk_id")),
                string(key.get("content_hash")), bucket.docCount());
    }

    private ProjectionSignature signature(List<KnowledgeChunkDO> chunks) {
        DigestAccumulator digest = new DigestAccumulator();
        chunks.stream()
                .sorted(Comparator.comparing(KnowledgeChunkDO::getChunkIndex,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(KnowledgeChunkDO::getId))
                .forEach(chunk -> digest.add(chunk.getChunkIndex(), chunk.getId(),
                        EsProjectionFingerprint.contentHash(chunk.getContentHash(), chunk.getContent()), 1));
        return digest.finish();
    }

    private boolean stateMatches(ProjectionStateDO state, Long sourceVersion) {
        return state != null && sourceVersion != null
                && ProjectionStatus.SUCCEEDED.name().equals(state.getStatus())
                && ProjectionAction.UPSERT.name().equals(state.getAction())
                && Objects.equals(sourceVersion, state.getDesiredVersion())
                && Objects.equals(sourceVersion, state.getAppliedVersion());
    }

    private String string(FieldValue value) {
        return value == null || value.isNull() ? "" : value.stringValue();
    }

    private long number(FieldValue value) {
        if (value == null || value.isNull()) {
            throw new IllegalStateException("Elasticsearch composite 缺少必填数字键");
        }
        return value.isLong() ? value.longValue() : (long) value.doubleValue();
    }

    private Long nullableNumber(FieldValue value) {
        return value == null || value.isNull() ? null : number(value);
    }

    private record DocumentKey(String kbId, String collectionName, String docId) {
    }

    private record ProjectionSignature(long chunkCount, String contentHash) {
    }

    private record ExternalDocument(Map<Long, ProjectionSignature> versions) {
        private ProjectionSignature onlyVersion(Long version) {
            return version == null ? null : versions.get(version);
        }

        private int versionCount() {
            return versions.size();
        }
    }

    private static final class ExternalDocumentBuilder {
        private final Map<Long, DigestAccumulator> versions = new HashMap<>();

        private void add(long version, Long chunkIndex, String chunkId, String contentHash, long count) {
            versions.computeIfAbsent(version, ignored -> new DigestAccumulator())
                    .add(chunkIndex, chunkId, contentHash, count);
        }

        private ExternalDocument finish() {
            Map<Long, ProjectionSignature> signatures = versions.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().finish()));
            return new ExternalDocument(signatures);
        }
    }

    private static final class DigestAccumulator {
        private final MessageDigest digest = EsProjectionFingerprint.newDigest();
        private long count;

        private void add(Object chunkIndex, String chunkId, String contentHash, long occurrences) {
            EsProjectionFingerprint.add(digest, chunkIndex);
            EsProjectionFingerprint.add(digest, chunkId);
            EsProjectionFingerprint.add(digest, contentHash);
            EsProjectionFingerprint.add(digest, occurrences);
            count += occurrences;
        }

        private ProjectionSignature finish() {
            return new ProjectionSignature(count, EsProjectionFingerprint.finish(digest));
        }
    }
}

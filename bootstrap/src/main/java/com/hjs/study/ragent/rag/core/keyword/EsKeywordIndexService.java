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

package com.hjs.study.ragent.rag.core.keyword;

import cn.hutool.core.collection.CollUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.hjs.study.ragent.core.chunk.VectorChunk;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.hjs.study.ragent.rag.config.KeywordProperties;
import com.hjs.study.ragent.rag.projection.worker.ProjectionExecutionException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 版本化 Elasticsearch 关键词投影写入器。 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
public class EsKeywordIndexService implements KeywordIndexService {

    private static final int MAX_CONTENT_LENGTH = 65_535;

    private final ElasticsearchClient esClient;
    private final KeywordProperties properties;
    private final KeywordBackendHealth backendHealth;

    /** 启动校验失败只熔断关键词通道，不能阻止主应用和向量检索启动。 */
    @PostConstruct
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void validateBackend() {
        try {
            String actualVersion = esClient.info().version().number();
            String requiredVersion = properties.getEs().getRequiredVersion();
            if (!requiredVersion.equals(actualVersion)) {
                throw new IllegalStateException("Elasticsearch 版本不匹配，required="
                        + requiredVersion + ", actual=" + actualVersion);
            }
            ensurePhysicalIndexAndAliases();
            esClient.indices().analyze(a -> a.index(physicalIndex())
                    .analyzer(properties.getEs().getAnalyzer()).text("中文分词健康检查"));
            backendHealth.healthy();
            log.info("Elasticsearch 关键词通道验证成功, version={}, physicalIndex={}",
                    actualVersion, physicalIndex());
        } catch (Exception ex) {
            backendHealth.unhealthy(safeMessage(ex));
            log.error("Elasticsearch 关键词通道验证失败，主应用继续启动且关键词检索保持关闭", ex);
        }
    }

    /** 先写完整新版本；Bulk 部分失败时，新版本不会通过投影状态门控。 */
    public void writeDocumentVersion(String kbId, String collectionName, String docId,
                                     long contentVersion, List<KnowledgeChunkDO> chunks) {
        requireHealthy();
        if (CollUtil.isEmpty(chunks)) {
            throw new ProjectionExecutionException("UPSERT 文档没有有效 Chunk", false, "ES_VALIDATE_SOURCE");
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder().refresh(Refresh.WaitFor);
        for (KnowledgeChunkDO chunk : chunks) {
            String esId = versionedId(docId, contentVersion, chunk.getId());
            Map<String, Object> document = buildDocument(kbId, collectionName, docId,
                    contentVersion, chunk.getId(), chunk.getChunkIndex(), chunk.getContent(),
                    chunk.getContentHash(), null, null);
            bulk.operations(op -> op.index(idx -> idx.index(writeIndex()).id(esId).document(document)));
        }
        executeBulk(bulk.build(), collectionName, docId);
    }

    /** CAS 成功后清理旧物理版本；失败不影响正确性，由对账继续清理。 */
    public void deleteOldDocumentVersions(String collectionName, String docId, long currentVersion) {
        try {
            esClient.deleteByQuery(d -> d.index(writeIndex())
                    .refresh(true)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("collection_name").value(collectionName)))
                            .filter(f -> f.term(t -> t.field("doc_id").value(docId)))
                            .mustNot(n -> n.term(t -> t.field("content_version").value(currentVersion))))));
        } catch (Exception ex) {
            throw new RuntimeException("ES 旧版本清理失败, docId=" + docId, ex);
        }
    }

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<VectorChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        List<KnowledgeChunkDO> source = chunks.stream().map(chunk -> KnowledgeChunkDO.builder()
                .id(chunk.getChunkId())
                .chunkIndex(chunk.getIndex())
                .content(chunk.getContent())
                .enabled(1)
                .build()).toList();
        writeDocumentVersion("", collectionName, docId, 0L, source);
    }

    @Override
    public void updateChunk(String collectionName, String docId, VectorChunk chunk) {
        throw new UnsupportedOperationException("ES 只允许按 PostgreSQL 文档版本完整投影");
    }

    @Override
    public void deleteDocumentIndex(String collectionName, String docId) {
        try {
            esClient.deleteByQuery(d -> d.index(writeIndex())
                    .ignoreUnavailable(true)
                    .allowNoIndices(true)
                    .refresh(true)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("collection_name").value(collectionName)))
                            .filter(f -> f.term(t -> t.field("doc_id").value(docId))))));
        } catch (Exception ex) {
            if (!isNotFound(ex)) {
                throw new RuntimeException("ES 文档投影删除失败, docId=" + docId, ex);
            }
        }
    }

    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        deleteChunksByIds(collectionName, List.of(chunkId));
    }

    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        if (CollUtil.isEmpty(chunkIds)) {
            return;
        }
        try {
            List<FieldValue> values = chunkIds.stream().map(FieldValue::of).toList();
            esClient.deleteByQuery(d -> d.index(writeIndex()).refresh(true)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("collection_name").value(collectionName)))
                            .filter(f -> f.terms(t -> t.field("chunk_id").terms(v -> v.value(values)))))));
        } catch (Exception ex) {
            if (!isNotFound(ex)) {
                throw new RuntimeException("ES Chunk 投影删除失败", ex);
            }
        }
    }

    @Override
    public void deleteByCollection(String collectionName) {
        try {
            esClient.deleteByQuery(d -> d.index(writeIndex())
                    .ignoreUnavailable(true)
                    .allowNoIndices(true)
                    .refresh(true)
                    .query(q -> q.term(t -> t.field("collection_name").value(collectionName))));
        } catch (Exception ex) {
            if (!isNotFound(ex)) {
                throw new RuntimeException("ES 知识库投影删除失败, collection=" + collectionName, ex);
            }
        }
    }

    private void executeBulk(BulkRequest request, String collectionName, String docId) {
        try {
            BulkResponse response = esClient.bulk(request);
            if (!response.errors()) {
                return;
            }
            List<String> errors = new ArrayList<>();
            boolean retryable = false;
            for (var item : response.items()) {
                if (item.error() != null && errors.size() < 10) {
                    errors.add(item.id() + ":" + item.status() + ":" + item.error().reason());
                }
                retryable |= item.status() == 429 || item.status() >= 500;
            }
            throw new ProjectionExecutionException("ES Bulk 部分失败: " + String.join(" | ", errors),
                    retryable, "ES_BULK");
        } catch (ProjectionExecutionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("ES Bulk 写入失败, collection=" + collectionName + ", docId=" + docId, ex);
        }
    }

    private void ensurePhysicalIndexAndAliases() throws Exception {
        ensureIndexTemplate();
        if (!esClient.indices().exists(e -> e.index(physicalIndex())).value()) {
            esClient.indices().create(c -> c.index(physicalIndex()));
        }
        ensureAlias(properties.getEs().getReadAlias(), false);
        ensureAlias(properties.getEs().getWriteAlias(), true);
    }

    private void ensureIndexTemplate() throws Exception {
        if (!esClient.indices().existsIndexTemplate(e -> e
                .name(properties.getEs().getIndexTemplate())).value()) {
            throw new IllegalStateException("Elasticsearch 索引模板不存在: "
                    + properties.getEs().getIndexTemplate());
        }
    }

    private void ensureAlias(String alias, boolean write) throws Exception {
        boolean exists = esClient.indices().existsAlias(e -> e.name(alias)).value();
        if (!exists) {
            if (write) {
                esClient.indices().updateAliases(u -> u.actions(a -> a.add(add -> add
                        .index(physicalIndex()).alias(alias).isWriteIndex(true))));
            } else {
                esClient.indices().updateAliases(u -> u.actions(a -> a.add(add -> add
                        .index(physicalIndex()).alias(alias))));
            }
            return;
        }
        if (write && !esClient.indices().getAlias(g -> g.name(alias)).result().containsKey(physicalIndex())) {
            throw new IllegalStateException("别名未指向预期物理索引: " + alias);
        }
    }

    private Map<String, Object> buildDocument(String kbId, String collectionName, String docId,
                                               long version, String chunkId, Integer chunkIndex,
                                               String rawContent, String contentHash,
                                               String outline, String blockType) {
        String content = rawContent == null ? "" : rawContent;
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }
        Map<String, Object> document = new HashMap<>();
        document.put("kb_id", kbId);
        document.put("collection_name", collectionName);
        document.put("doc_id", docId);
        document.put("chunk_id", chunkId);
        document.put("chunk_index", chunkIndex);
        document.put("content", content);
        document.put("content_hash", EsProjectionFingerprint.contentHash(contentHash, rawContent));
        document.put("content_version", version);
        document.put("indexed_at", Instant.now().toString());
        if (StringUtils.hasText(outline)) {
            document.put("outline", outline);
        }
        if (StringUtils.hasText(blockType)) {
            document.put("block_type", blockType);
        }
        return document;
    }

    private void requireHealthy() {
        if (!backendHealth.isHealthy()) {
            throw new ProjectionExecutionException("ES 通道不健康: " + backendHealth.getReason(),
                    true, "ES_HEALTH");
        }
    }

    private String physicalIndex() {
        return properties.getEs().getPhysicalIndex();
    }

    private String writeIndex() {
        return properties.writeIndex();
    }

    private String versionedId(String docId, long version, String chunkId) {
        return docId + ":" + version + ":" + chunkId;
    }

    private boolean isNotFound(Exception ex) {
        return ex instanceof ElasticsearchException esException
                && esException.response() != null && esException.response().status() == 404;
    }

    private String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}

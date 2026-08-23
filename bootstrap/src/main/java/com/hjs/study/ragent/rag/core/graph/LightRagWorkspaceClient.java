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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hjs.study.ragent.rag.config.GraphProperties;
import com.hjs.study.ragent.rag.projection.worker.ProjectionExecutionException;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 严格的逐知识库 LightRAG API 客户端；地址只能由服务端模板产生。 */
@Component
@ConditionalOnProperty(prefix = "rag.graph", name = "type", havingValue = "lightrag")
public class LightRagWorkspaceClient {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final Pattern SAFE_KB_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GraphProperties properties;

    public LightRagWorkspaceClient(@Qualifier("syncHttpClient") OkHttpClient httpClient,
                                   ObjectMapper objectMapper,
                                   GraphProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public SubmitResult submit(String kbId, String text, String fileSource) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);
        body.put("file_source", fileSource);
        JsonNode response = execute(kbId, "documents/text", "POST", body, false, "LIGHTRAG_SUBMIT");
        String trackId = response == null ? null : response.path("track_id").asText(null);
        if (StrUtil.isBlank(trackId)) {
            throw new ProjectionExecutionException("LightRAG 提交响应缺少 track_id", false, "LIGHTRAG_SUBMIT");
        }
        return new SubmitResult(trackId);
    }

    public TrackResult track(String kbId, String trackId) {
        JsonNode root = execute(kbId, "documents/track_status/" + trackId, "GET", null,
                false, "LIGHTRAG_TRACK");
        List<String> statuses = new ArrayList<>();
        if (root != null) {
            root.findValues("status").forEach(node -> {
                if (node.isValueNode() && StrUtil.isNotBlank(node.asText())) {
                    statuses.add(node.asText().toLowerCase(Locale.ROOT));
                }
            });
        }
        if (statuses.isEmpty()) {
            return new TrackResult(TrackState.PROCESSING, null, null);
        }
        if (statuses.stream().anyMatch(this::isFailureStatus)) {
            String error = firstText(root, "error", "message", "error_message");
            return new TrackResult(TrackState.FAILED, null,
                    StrUtil.blankToDefault(error, "LightRAG pipeline failed"));
        }
        boolean complete = statuses.stream().allMatch(this::isProcessedStatus);
        if (!complete) {
            return new TrackResult(TrackState.PROCESSING, null, null);
        }
        String documentId = findDocumentId(root);
        if (StrUtil.isBlank(documentId)) {
            throw new ProjectionExecutionException("LightRAG processed 响应缺少内部文档 ID",
                    false, "LIGHTRAG_TRACK");
        }
        return new TrackResult(TrackState.PROCESSED, documentId, null);
    }

    /** 直接按已保存的 LightRAG 内部文档 ID 删除；404 视为幂等成功。 */
    public void deleteDocument(String kbId, String documentId) {
        if (StrUtil.isBlank(documentId)) {
            return;
        }
        JsonNode response = execute(kbId, "documents/" + documentId, "DELETE", null,
                true, "LIGHTRAG_DELETE");
        String status = response == null ? "" : response.path("status").asText("");
        if ("busy".equalsIgnoreCase(status)) {
            throw new ProjectionExecutionException("LightRAG 删除返回 busy", true, "LIGHTRAG_DELETE");
        }
    }

    public HealthResult health(String kbId) {
        JsonNode root = execute(kbId, "health", "GET", null, false, "LIGHTRAG_HEALTH");
        String version = firstText(root, "version", "lightrag_version");
        String apiVersion = firstText(root, "api_version");
        return new HealthResult(version, apiVersion);
    }

    /** 对账专用：读取 workspace 文档清单，不在写入热路径做全量内存匹配。 */
    public List<DocumentReference> listDocuments(String kbId) {
        JsonNode root = execute(kbId, "documents", "GET", null, false, "LIGHTRAG_RECONCILE");
        if (root == null) {
            return List.of();
        }
        List<DocumentReference> documents = new ArrayList<>();
        for (JsonNode node : root.findParents("file_source")) {
            String id = firstText(node, "document_id", "doc_id", "id");
            String source = firstText(node, "file_source");
            String status = firstText(node, "status");
            if (StrUtil.isNotBlank(id)) {
                documents.add(new DocumentReference(id, source, status));
            }
        }
        return documents;
    }

    public JsonNode query(String kbId, String question, String mode, int topK) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", question);
        body.put("mode", StrUtil.blankToDefault(mode, "mix"));
        body.put("only_need_context", true);
        body.put("include_references", true);
        body.put("include_chunk_content", true);
        body.put("top_k", topK);
        return execute(kbId, "query", "POST", body, false, "LIGHTRAG_QUERY",
                Math.max(1000, properties.getLightrag().getQueryTimeoutMs()));
    }

    public JsonNode fetchGraph(String kbId, String label, int maxDepth, int maxNodes) {
        return getWithQuery(kbId, "graphs", Map.of(
                "label", StrUtil.blankToDefault(label, "*"),
                "max_depth", String.valueOf(maxDepth),
                "max_nodes", String.valueOf(maxNodes)), "LIGHTRAG_GRAPH");
    }

    public List<String> fetchLabels(String kbId, String keyword, int limit) {
        boolean popular = StrUtil.isBlank(keyword);
        JsonNode root = getWithQuery(kbId,
                popular ? "graph/label/popular" : "graph/label/search",
                popular ? Map.of("limit", String.valueOf(limit))
                        : Map.of("q", keyword, "limit", String.valueOf(limit)),
                "LIGHTRAG_LABELS");
        List<String> labels = new ArrayList<>();
        if (root != null && root.isArray()) {
            root.forEach(node -> {
                String value = node.isTextual()
                        ? node.asText() : node.path("label").asText(node.path("name").asText(""));
                if (StrUtil.isNotBlank(value)) {
                    labels.add(value);
                }
            });
        }
        return labels;
    }

    public String workspaceUrl(String kbId) {
        if (kbId == null || !SAFE_KB_ID.matcher(kbId).matches()) {
            throw new IllegalArgumentException("非法知识库 ID");
        }
        String template = properties.getLightrag().getBaseUrlTemplate();
        if (StrUtil.isBlank(template) || !template.contains("{kbId}")) {
            throw new IllegalStateException("rag.graph.lightrag.base-url-template 必须包含 {kbId}");
        }
        return StrUtil.removeSuffix(template.replace("{kbId}", kbId), "/");
    }

    private JsonNode execute(String kbId, String path, String method, JsonNode body,
                             boolean allowNotFound, String phase) {
        return execute(kbId, path, method, body, allowNotFound, phase,
                Math.max(1000, properties.getLightrag().getTimeoutMs()));
    }

    private JsonNode execute(String kbId, String path, String method, JsonNode body,
                             boolean allowNotFound, String phase, int timeoutMs) {
        validateSecurityGate(phase);
        HttpUrl base = HttpUrl.parse(workspaceUrl(kbId));
        if (base == null) {
            throw new ProjectionExecutionException("LightRAG 地址模板无效", false, phase);
        }
        HttpUrl target = base.newBuilder().addPathSegments(path).build();
        return executeTarget(target, method, body, allowNotFound, phase, timeoutMs);
    }

    private JsonNode getWithQuery(String kbId, String path, Map<String, String> query, String phase) {
        validateSecurityGate(phase);
        HttpUrl base = HttpUrl.parse(workspaceUrl(kbId));
        if (base == null) {
            throw new ProjectionExecutionException("LightRAG 地址模板无效", false, phase);
        }
        HttpUrl.Builder target = base.newBuilder().addPathSegments(path);
        query.forEach(target::addQueryParameter);
        return executeTarget(target.build(), "GET", null, false, phase,
                Math.max(1000, properties.getLightrag().getQueryTimeoutMs()));
    }

    private JsonNode executeTarget(HttpUrl target, String method, JsonNode body,
                                   boolean allowNotFound, String phase, int timeoutMs) {
        Request.Builder request = new Request.Builder().url(target);
        String apiKey = properties.getLightrag().getApiKey();
        request.header("X-API-Key", apiKey);
        if ("GET".equals(method)) {
            request.get();
        } else if ("DELETE".equals(method)) {
            request.delete(body == null ? null : RequestBody.create(body.toString(), JSON));
        } else {
            request.post(RequestBody.create(body.toString(), JSON));
        }
        OkHttpClient client = httpClient.newBuilder()
                .callTimeout(Duration.ofMillis(timeoutMs))
                .readTimeout(Duration.ofMillis(timeoutMs))
                .build();
        try (Response response = client.newCall(request.build()).execute()) {
            int code = response.code();
            String raw = response.body() == null ? "" : response.body().string();
            if (allowNotFound && code == 404) {
                return null;
            }
            if (!response.isSuccessful()) {
                boolean retryable = code == 409 || code == 423 || code == 429 || code >= 500;
                throw new ProjectionExecutionException("LightRAG HTTP " + code,
                        retryable, phase);
            }
            return StrUtil.isBlank(raw) ? null : objectMapper.readTree(raw);
        } catch (ProjectionExecutionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProjectionExecutionException("LightRAG 请求失败", ex, true, phase);
        }
    }

    private void validateSecurityGate(String phase) {
        GraphProperties.LightRag config = properties.getLightrag();
        if (!config.isSecurityApproved()) {
            throw new ProjectionExecutionException("LightRAG 版本尚未通过认证安全公告验收",
                    false, phase);
        }
        if (StrUtil.isBlank(config.getApiKey())) {
            throw new ProjectionExecutionException("LightRAG API Key 未配置", false, phase);
        }
        String digest = config.getImageDigest();
        if (StrUtil.isBlank(digest) || !digest.contains("sha256:")) {
            throw new ProjectionExecutionException("LightRAG 镜像未固定 digest", false, phase);
        }
    }

    private boolean isFailureStatus(String status) {
        return status.contains("fail") || status.contains("error") || status.contains("cancel");
    }

    private boolean isProcessedStatus(String status) {
        return "processed".equals(status) || "completed".equals(status) || "success".equals(status);
    }

    private String findDocumentId(JsonNode root) {
        if (root == null) {
            return null;
        }
        for (String container : List.of("documents", "data", "results")) {
            JsonNode node = root.path(container);
            if (node.isArray()) {
                for (JsonNode document : node) {
                    String id = firstText(document, "document_id", "doc_id", "id");
                    if (StrUtil.isNotBlank(id)) {
                        return id;
                    }
                }
            }
        }
        return firstText(root, "document_id", "doc_id");
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            List<JsonNode> values = node.findValues(field);
            for (JsonNode value : values) {
                if (value.isValueNode() && StrUtil.isNotBlank(value.asText())) {
                    return value.asText();
                }
            }
        }
        return null;
    }

    public record SubmitResult(String trackId) {
    }

    public record TrackResult(TrackState state, String documentId, String error) {
    }

    public record HealthResult(String lightragVersion, String apiVersion) {
    }

    public record DocumentReference(String documentId, String fileSource, String status) {
    }

    public enum TrackState {
        PROCESSING,
        PROCESSED,
        FAILED
    }
}

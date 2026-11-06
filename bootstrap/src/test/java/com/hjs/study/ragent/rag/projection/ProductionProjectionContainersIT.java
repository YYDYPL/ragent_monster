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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实容器合约。CI/nightly 设置 RAG_RUN_CONTAINER_TESTS=true，并提供由仓库 Dockerfile
 * 构建且已校验 IK SHA-256 的 RAG_ES_IK_TEST_IMAGE。
 */
@Tag("integration")
class ProductionProjectionContainersIT {

    @Test
    void postgresqlUpgradeCreatesVersionedProjectionSchema() throws Exception {
        assumeContainerTestsEnabled();
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:0.8.2-pg15-bookworm"))) {
            postgres.start();
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_knowledge_document (id VARCHAR(20) PRIMARY KEY)");
                Path migration = Path.of("..", "resources", "database", "upgrade_v1.6_to_v1.7.sql")
                        .toAbsolutePath().normalize();
                statement.execute(Files.readString(migration, StandardCharsets.UTF_8));
                try (var result = statement.executeQuery("""
                        SELECT content_version,
                               to_regclass('public.t_rag_projection_state') IS NOT NULL AS has_state,
                               to_regclass('public.t_graph_workspace_binding') IS NOT NULL AS has_workspace
                        FROM t_knowledge_document WHERE false
                        """)) {
                    assertThat(result.getMetaData().getColumnLabel(1)).isEqualTo("content_version");
                }
                try (var result = statement.executeQuery("""
                        SELECT to_regclass('public.t_rag_projection_state') IS NOT NULL,
                               to_regclass('public.t_rag_projection_attempt') IS NOT NULL,
                               to_regclass('public.t_rag_projection_batch') IS NOT NULL,
                               to_regclass('public.t_graph_workspace_binding') IS NOT NULL
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                    assertThat(result.getBoolean(2)).isTrue();
                    assertThat(result.getBoolean(3)).isTrue();
                    assertThat(result.getBoolean(4)).isTrue();
                }
            }
        }
    }

    @Test
    void elasticsearchImageHasExactVersionIkBulkAndAtomicAliases() throws Exception {
        assumeContainerTestsEnabled();
        String image = System.getenv("RAG_ES_IK_TEST_IMAGE");
        Assumptions.assumeTrue(image != null && image.contains("@sha256:"),
                "RAG_ES_IK_TEST_IMAGE 必须是固定 digest 的 ES 8.19.19 + IK 镜像");
        try (GenericContainer<?> elasticsearch = new GenericContainer<>(DockerImageName.parse(image))
                .withEnv("discovery.type", "single-node")
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/").forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)))) {
            elasticsearch.start();
            String base = "http://" + elasticsearch.getHost() + ":" + elasticsearch.getMappedPort(9200);
            HttpClient client = HttpClient.newHttpClient();

            assertThat(request(client, "GET", base + "/", null).body()).contains("\"number\":\"8.19.19\"");
            assertThat(request(client, "POST", base + "/_analyze",
                    "{\"analyzer\":\"ik_smart\",\"text\":\"中华人民共和国知识图谱\"}").statusCode())
                    .isEqualTo(200);

            assertThat(request(client, "PUT", base + "/rag_keyword_v2_000001", """
                    {"aliases":{"rag_keyword_read":{},"rag_keyword_write":{"is_write_index":true}},
                     "mappings":{"properties":{"doc_id":{"type":"keyword"},
                     "content":{"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
                     "content_version":{"type":"long"}}}}
                    """).statusCode()).isEqualTo(200);
            assertThat(request(client, "POST", base + "/_bulk?refresh=wait_for", """
                    {"index":{"_index":"rag_keyword_write","_id":"doc:1:chunk"}}
                    {"doc_id":"doc","content":"生产关键词检索","content_version":1}

                    """).statusCode()).isEqualTo(200);
            assertThat(request(client, "GET", base + "/rag_keyword_read/_count", null).body())
                    .contains("\"count\":1");

            assertThat(request(client, "PUT", base + "/rag_keyword_v2_000002", "{}").statusCode())
                    .isEqualTo(200);
            assertThat(request(client, "POST", base + "/_aliases", """
                    {"actions":[
                      {"remove":{"index":"rag_keyword_v2_000001","alias":"rag_keyword_read"}},
                      {"add":{"index":"rag_keyword_v2_000002","alias":"rag_keyword_read"}}
                    ]}
                    """).statusCode()).isEqualTo(200);
        }
    }

    private HttpResponse<String> request(HttpClient client, String method, String uri, String body)
            throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return client.send(HttpRequest.newBuilder(URI.create(uri))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .method(method, publisher)
                        .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assumeContainerTestsEnabled() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RAG_RUN_CONTAINER_TESTS")),
                "设置 RAG_RUN_CONTAINER_TESTS=true 后运行真实容器合约");
    }
}

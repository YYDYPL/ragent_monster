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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hjs.study.ragent.rag.config.GraphProperties;
import com.hjs.study.ragent.rag.projection.worker.ProjectionExecutionException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LightRagWorkspaceClientTest {

    private HttpServer server;
    private LightRagWorkspaceClient client;
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/kb-kb1/documents/text", exchange -> respond(exchange, 200,
                "{\"track_id\":\"track-1\"}"));
        server.createContext("/kb-kb1/documents/track_status/track-1", exchange -> respond(exchange, 200,
                "{\"documents\":[{\"status\":\"processed\",\"document_id\":\"external-1\"}]}"));
        server.createContext("/kb-kb1/documents/delete_document", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("busy")) {
                respond(exchange, 200, "{\"status\":\"busy\"}");
            } else if (body.contains("unauthorized")) {
                respond(exchange, 401, "{\"detail\":\"unauthorized\"}");
            } else {
                respond(exchange, 200, "{\"status\":\"deletion_started\"}");
            }
        });
        server.createContext("/kb-kb1/documents", exchange -> respond(exchange, 200,
                "{\"statuses\":{\"processed\":[]}}"));
        server.createContext("/kb-kb1/documents/unauthorized", exchange -> respond(exchange, 401,
                "{\"detail\":\"unauthorized\"}"));
        server.start();

        GraphProperties properties = new GraphProperties();
        GraphProperties.LightRag config = properties.getLightrag();
        config.setBaseUrlTemplate("http://127.0.0.1:" + server.getAddress().getPort() + "/kb-{kbId}");
        config.setApiKey("test-secret");
        config.setImageDigest("example/lightrag@sha256:abc");
        config.setSecurityApproved(true);
        client = new LightRagWorkspaceClient(new OkHttpClient(), new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void submitsWithSourceAndWaitsForProcessedDocumentId() {
        LightRagWorkspaceClient.SubmitResult submitted = client.submit(
                "kb1", "content", "kb=kb1;doc=doc1;version=7");
        LightRagWorkspaceClient.TrackResult tracked = client.track("kb1", submitted.trackId());

        assertThat(submitted.trackId()).isEqualTo("track-1");
        assertThat(tracked.state()).isEqualTo(LightRagWorkspaceClient.TrackState.PROCESSED);
        assertThat(tracked.documentId()).isEqualTo("external-1");
        assertThat(apiKey.get()).isEqualTo("test-secret");
        assertThat(requestBody.get()).contains("kb=kb1;doc=doc1;version=7");
    }

    @Test
    void classifiesBusyAsRetryableAndAuthenticationAsBlocked() {
        assertThatThrownBy(() -> client.deleteDocument("kb1", "busy"))
                .isInstanceOfSatisfying(ProjectionExecutionException.class,
                        error -> assertThat(error.isRetryable()).isTrue());
        assertThatThrownBy(() -> client.deleteDocument("kb1", "unauthorized"))
                .isInstanceOfSatisfying(ProjectionExecutionException.class,
                        error -> assertThat(error.isRetryable()).isFalse());
    }

    @Test
    void rejectsUnsafeKnowledgeBaseIdentifiersBeforeNetworkAccess() {
        assertThatThrownBy(() -> client.workspaceUrl("../admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
        String receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!receivedBody.isEmpty()) {
            requestBody.set(receivedBody);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

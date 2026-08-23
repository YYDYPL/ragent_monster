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

package com.hjs.study.ragent.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.hjs.study.ragent.rag.core.keyword.KeywordBackendHealth;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.io.File;

/**
 * Elasticsearch 客户端配置
 * <p>
 * 仅当 rag.keyword.type=es 且 classpath 存在 ES 客户端时才装配，
 * 是「关键词模态」可插拔三层条件门中的依赖层 + 配置层
 */
@Slf4j
@Configuration
@ConditionalOnClass(ElasticsearchClient.class)
@ConditionalOnProperty(name = "rag.keyword.type", havingValue = "es")
public class EsClientConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient(KeywordProperties keywordProperties,
                                                     KeywordBackendHealth backendHealth) {
        String uris = keywordProperties.getEs().getUris();
        HttpHost[] hosts;
        try {
            hosts = Arrays.stream(StringUtils.commaDelimitedListToStringArray(uris))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(HttpHost::create)
                    .toArray(HttpHost[]::new);
            if (hosts.length == 0) {
                throw new IllegalArgumentException("未配置 Elasticsearch URI");
            }
        } catch (RuntimeException ex) {
            // 配置错误只让关键词通道健康检查失败，不能阻止向量通道启动。
            log.error("Elasticsearch URI 配置无效，关键词通道将保持不健康", ex);
            backendHealth.configurationError("Elasticsearch URI 配置无效");
            hosts = new HttpHost[]{new HttpHost("127.0.0.1", 1, "http")};
        }

        RestClientBuilder builder = RestClient.builder(hosts);
        String apiKey = keywordProperties.getEs().getApiKey();
        if (StringUtils.hasText(apiKey)) {
            builder.setDefaultHeaders(new BasicHeader[]{new BasicHeader("Authorization", "ApiKey " + apiKey.trim())});
        }
        String caCertificate = keywordProperties.getEs().getCaCertificate();
        if (StringUtils.hasText(caCertificate)) {
            try {
                var sslContext = SSLContexts.custom()
                        .loadTrustMaterial(new File(caCertificate), null)
                        .build();
                builder.setHttpClientConfigCallback(http -> http.setSSLContext(sslContext));
            } catch (Exception ex) {
                // 保持应用可启动；后续 TLS 请求必然无法通过健康检查，通道会被熔断。
                log.error("加载 Elasticsearch CA 证书失败，关键词通道将保持不健康", ex);
                backendHealth.configurationError("Elasticsearch CA 证书加载失败");
            }
        }
        RestClient restClient = builder.build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        log.info("Elasticsearch 关键词检索客户端已初始化, uris={}", uris);
        return new ElasticsearchClient(transport);
    }
}

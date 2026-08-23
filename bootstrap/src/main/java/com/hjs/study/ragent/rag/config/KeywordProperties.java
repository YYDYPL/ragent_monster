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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 关键词检索配置
 * <p>
 * type=none（默认）时不注册任何关键词读写实现，与「从未引入关键词检索」运行期等价
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.keyword")
public class KeywordProperties {

    /**
     * 关键词检索后端类型
     * 可选 none（关闭）/ es；none 时不注册任何关键词读写实现
     */
    private String type = "none";

    /**
     * Elasticsearch 配置
     */
    private Es es = new Es();

    /**
     * 检索使用读别名
     * <p>
     * 与 Milvus 共享 collection、PG 共享表同构：单索引承载所有知识库，按 collection_name 字段区分
     */
    public String sharedIndex() {
        return es.getReadAlias();
    }

    public String writeIndex() {
        return es.getWriteAlias();
    }

    @Data
    public static class Es {

        /**
         * ES 连接地址
         */
        private String uris = "http://127.0.0.1:9200";

        /**
         * 共享索引名称：所有知识库的关键词数据都写在此索引，按 collection_name 字段过滤
         */
        private String physicalIndex = "rag_keyword_v2_000001";

        /** 版本化物理索引模板名称。 */
        private String indexTemplate = "rag_keyword_v2";

        /** 模板仅匹配受控的 v2 物理索引。 */
        private String indexPattern = "rag_keyword_v2_*";

        /** 查询只允许走读别名。 */
        private String readAlias = "rag_keyword_read";

        /** 投影写入只允许走写别名。 */
        private String writeAlias = "rag_keyword_write";

        /** 最小权限 Elasticsearch API Key，生产环境必须由密钥注入。 */
        private String apiKey = "";

        /** 私有 CA PEM 路径；生产 TLS 使用自签 CA 时必须配置。 */
        private String caCertificate = "";

        /** 期望的服务端精确版本。 */
        private String requiredVersion = "8.19.19";

        /**
         * 写入分词器
         */
        private String analyzer = "ik_max_word";

        /**
         * 查询分词器
         */
        private String searchAnalyzer = "ik_smart";
    }
}

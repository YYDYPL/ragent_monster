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
 * 知识图谱检索配置
 * <p>
 * type=none（默认）时不注册任何图谱读写实现（检索通道与写入同步装饰器均不织入），
 * 与「从未引入图谱检索」运行期等价
 * <p>
 * 与关键词检索对称：此处管后端类型与连接（对应 rag.keyword），
 * 通道行为（启用/范围/倍数）放在 {@link SearchChannelProperties} 的 channels.graph
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.graph")
public class GraphProperties {

    /**
     * 图谱检索后端类型
     * 可选 none（关闭）/ lightrag；none 时不注册任何图谱读写实现
     */
    private String type = "none";

    /**
     * LightRAG 微服务连接配置
     */
    private LightRag lightrag = new LightRag();

    /**
     * 摄取侧同步配置
     */
    private Ingestion ingestion = new Ingestion();

    /**
     * 图谱侧 embedding 模型标识
     * 独立于各知识库的向量 embedding，首次索引后不可更换
     */
    private String embeddingModel = "";

    /** 图谱侧 embedding 维度，参与 workspace 配置指纹。 */
    private int embeddingDimension;

    /**
     * 是否启用 lightrag 后端
     */
    public boolean isLightrag() {
        return "lightrag".equalsIgnoreCase(type);
    }

    @Data
    public static class LightRag {

        /** 每个知识库独立实例的服务端确定性地址，不允许来自数据库或请求参数。 */
        private String baseUrlTemplate = "http://ragent-lightrag-kb-{kbId}:9621";

        /**
         * API Key（对应 LightRAG 的 X-API-Key 头）
         * 本地部署默认留空、不发送该头；如需对外鉴权再显式配置
         */
        private String apiKey = "";

        /**
         * 查询模式：naive / local / global / hybrid / mix
         */
        private String queryMode = "mix";

        /**
         * 请求超时（毫秒）
         */
        private int timeoutMs = 30000;

        /** 单实例查询超时。 */
        private int queryTimeoutMs = 5000;

        /** 无知识库意图时 fan-out 总超时。 */
        private int fanoutTimeoutMs = 8000;

        private int maxWorkspaces = 20;
        private int fanoutConcurrency = 4;

        /** API 合约版本，参与 workspace 配置指纹。 */
        private String apiVersion = "v1";

        /** 固定镜像 digest，生产配置禁止 latest/空值。 */
        private String imageDigest = "";

        /** 只有确认所用稳定版已修复认证公告后才允许开启。 */
        private boolean securityApproved = false;
    }

    @Data
    public static class Ingestion {

        /**
         * 是否启用写入同步：把向量写链路的新增 / 删除同步进图谱
         * 默认 true——只要图谱后端已启用（type=lightrag）即随向量写自动重建图谱，与历史行为一致
         * 置 false 可在保留可视化 / 检索读取的同时冻结图谱写入，
         * 适用于「已接入后端、当前不做图谱检索、也不想再为每篇文档付出实体抽取成本」的场景
         */
        private boolean enabled = true;

        /**
         * 是否异步同步：标脏入队 + 后台防抖重建，默认 true 不阻塞主写链路
         */
        private boolean async = true;

        /**
         * 是否额外写入全局图 workspace（跨库方案 B）
         * 默认 false：Phase1 用逐库 fan-out 兜底，不双写
         */
        private boolean globalWorkspace = false;
    }
}

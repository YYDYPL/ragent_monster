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

package com.hjs.study.ragent.rag.core.retrieval.channel;

import cn.hutool.core.collection.CollUtil;
import com.hjs.study.ragent.framework.convention.RetrievedChunk;
import com.hjs.study.ragent.rag.config.GraphProperties;
import com.hjs.study.ragent.rag.config.SearchChannelProperties;
import com.hjs.study.ragent.rag.core.graph.LightRagWorkspaceRetriever;
import com.hjs.study.ragent.rag.core.intent.NodeScore;
import com.hjs.study.ragent.rag.core.intent.NodeScoreFilters;
import com.hjs.study.ragent.rag.projection.service.ProjectionFreshnessGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识图谱检索通道
 * <p>
 * 基于 LightRAG 的图谱召回：擅长多跳关系推理与实体为中心的聚合，与向量 / 关键词互补
 * 仅当开启图谱后端（rag.graph.type=lightrag）时才注册，否则整个通道不存在，引擎自动退化为无图谱检索
 * <p>
 * 与其他通道并行执行，结果统一进 RRF 融合，通道间无先后与优先级之分
 * <p>
 * 每个知识库对应一个独立实例；有知识库意图时定向检索，无意图时对健康实例做受控 fan-out。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.graph", name = "type", havingValue = "lightrag")
public class GraphSearchChannel implements SearchChannel {

    private final LightRagWorkspaceRetriever workspaceRetriever;
    private final GraphProperties graphProperties;
    private final SearchChannelProperties properties;
    private final ProjectionFreshnessGate freshnessGate;

    @Override
    public String getName() {
        return "GraphSearch";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.GRAPH;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getGraph().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        freshnessGate.resetStats();
        try {
            // 有 KB 意图只查询对应实例；无意图则对所有健康且启用的 workspace fan-out。
            List<String> collections = extractIntentCollections(context);

            int topK = context.getBudget().recallBudget();
            String queryMode = graphProperties.getLightrag().getQueryMode();

            LightRagWorkspaceRetriever.RetrievalResult retrieval = workspaceRetriever.retrieve(
                    context.getMainQuestion(), queryMode, topK, collections);
            List<RetrievedChunk> chunks = retrieval.chunks();
            ProjectionFreshnessGate.GateStats gateStats = freshnessGate.consumeStats();

            long latency = System.currentTimeMillis() - startTime;
            log.info("图谱检索完成，范围={}，检索到 {} 个证据，耗时 {}ms",
                    CollUtil.isEmpty(collections) ? "全局" : collections, chunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.GRAPH)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .staleDropped(gateStats.staleDropped())
                    .projectionLagMs(gateStats.projectionLagMs())
                    .degradedReason(retrieval.degradedReason())
                    .build();
        } catch (Exception e) {
            ProjectionFreshnessGate.GateStats gateStats = freshnessGate.consumeStats();
            log.error("图谱检索失败", e);
            return emptyResult(startTime, gateStats, "graph-query-failed:" + e.getClass().getSimpleName());
        }
    }

    /**
     * 从意图识别结果提取 KB 意图对应的 collection 名称
     */
    private List<String> extractIntentCollections(SearchContext context) {
        if (CollUtil.isEmpty(context.getIntents())) {
            return List.of();
        }
        List<NodeScore> allScores = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();
        return NodeScoreFilters.kbCollections(allScores);
    }

    private SearchChannelResult emptyResult(long startTime, ProjectionFreshnessGate.GateStats gateStats,
                                            String degradedReason) {
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.GRAPH)
                .channelName(getName())
                .chunks(List.of())
                .latencyMs(System.currentTimeMillis() - startTime)
                .staleDropped(gateStats.staleDropped())
                .projectionLagMs(gateStats.projectionLagMs())
                .degradedReason(degradedReason)
                .build();
    }
}

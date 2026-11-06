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

package com.hjs.study.ragent.rag.projection.monitoring;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hjs.study.ragent.rag.core.keyword.KeywordBackendHealth;
import com.hjs.study.ragent.rag.projection.dao.entity.GraphWorkspaceBindingDO;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.GraphWorkspaceBindingMapper;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionStatus;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectionMetrics {

    private final ProjectionStateMapper stateMapper;
    private final GraphWorkspaceBindingMapper workspaceMapper;
    private final KeywordBackendHealth keywordHealth;
    private final MeterRegistry registry;
    private final ObjectProvider<ElasticsearchClient> elasticsearchClientProvider;
    private final Map<ProjectionTarget, AtomicLong> backlog = new EnumMap<>(ProjectionTarget.class);
    private final Map<ProjectionTarget, AtomicLong> maxLagMs = new EnumMap<>(ProjectionTarget.class);
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong unhealthyWorkspaces = new AtomicLong();
    private final AtomicLong elasticsearchDiskUsagePercent = new AtomicLong();

    @PostConstruct
    public void register() {
        for (ProjectionTarget target : ProjectionTarget.values()) {
            backlog.put(target, new AtomicLong());
            maxLagMs.put(target, new AtomicLong());
            Gauge.builder("rag.projection.backlog", backlog.get(target), AtomicLong::get)
                    .tag("target", target.name()).register(registry);
            Gauge.builder("rag.projection.max.lag.ms", maxLagMs.get(target), AtomicLong::get)
                    .tag("target", target.name()).register(registry);
        }
        Gauge.builder("rag.projection.blocked", blocked, AtomicLong::get).register(registry);
        Gauge.builder("rag.graph.workspace.unhealthy", unhealthyWorkspaces, AtomicLong::get).register(registry);
        Gauge.builder("rag.elasticsearch.healthy", keywordHealth, health -> health.isHealthy() ? 1 : 0)
                .register(registry);
        Gauge.builder("rag.elasticsearch.disk.usage.percent", elasticsearchDiskUsagePercent, AtomicLong::get)
                .register(registry);
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void refresh() {
        try {
            List<ProjectionStateDO> states = stateMapper.selectList(Wrappers.lambdaQuery(ProjectionStateDO.class));
            Date now = new Date();
            blocked.set(states.stream().filter(s -> ProjectionStatus.BLOCKED.name().equals(s.getStatus())).count());
            for (ProjectionTarget target : ProjectionTarget.values()) {
                List<ProjectionStateDO> targetStates = states.stream()
                        .filter(s -> target.name().equals(s.getTarget()))
                        .filter(s -> !ProjectionStatus.SUCCEEDED.name().equals(s.getStatus()))
                        .toList();
                backlog.get(target).set(targetStates.size());
                long lag = targetStates.stream().filter(s -> s.getDesiredTime() != null)
                        .mapToLong(s -> Math.max(0, now.getTime() - s.getDesiredTime().getTime()))
                        .max().orElse(0);
                maxLagMs.get(target).set(lag);
            }
            unhealthyWorkspaces.set(workspaceMapper.selectCount(Wrappers.lambdaQuery(GraphWorkspaceBindingDO.class)
                    .eq(GraphWorkspaceBindingDO::getEnabled, 1)
                    .ne(GraphWorkspaceBindingDO::getHealthStatus, "HEALTHY")));
            refreshElasticsearchDiskUsage();
        } catch (Exception ex) {
            log.warn("刷新投影监控指标失败", ex);
        }
    }

    private void refreshElasticsearchDiskUsage() throws Exception {
        ElasticsearchClient client = elasticsearchClientProvider.getIfAvailable();
        if (client == null || !keywordHealth.isHealthy()) {
            elasticsearchDiskUsagePercent.set(0L);
            return;
        }
        long maximum = client.nodes().stats().nodes().values().stream()
                .filter(node -> node.fs() != null && node.fs().total() != null)
                .mapToLong(node -> {
                    Long total = node.fs().total().totalInBytes();
                    Long available = node.fs().total().availableInBytes();
                    if (total == null || available == null || total <= 0) {
                        return 0L;
                    }
                    return Math.max(0L, Math.min(100L, Math.round((total - available) * 100D / total)));
                })
                .max().orElse(0L);
        elasticsearchDiskUsagePercent.set(maximum);
    }
}

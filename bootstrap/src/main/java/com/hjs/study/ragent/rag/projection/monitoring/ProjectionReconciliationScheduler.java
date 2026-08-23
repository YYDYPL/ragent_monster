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

import com.hjs.study.ragent.rag.projection.controller.request.ProjectionBatchRequest;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import com.hjs.study.ragent.rag.projection.service.ProjectionAdminService;
import com.hjs.study.ragent.rag.projection.service.EsProjectionReconciliationService;
import com.hjs.study.ragent.rag.projection.service.LightRagReconciliationService;
import com.hjs.study.ragent.rag.projection.worker.ProjectionJobExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

/** 定期从 PostgreSQL 真源重放期望状态，修复派生存储缺失/过期并清理额外旧版本。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectionReconciliationScheduler {

    private final ProjectionAdminService adminService;
    private final ProjectionJobExecutor jobExecutor;
    private final ObjectProvider<EsProjectionReconciliationService> esReconciler;
    private final ObjectProvider<LightRagReconciliationService> lightRagReconciler;

    @Scheduled(cron = "0 0 2 * * *")
    public void nightlyElasticsearch() {
        EsProjectionReconciliationService reconciler = esReconciler.getIfAvailable();
        if (reconciler == null) {
            return;
        }
        try {
            reconciler.reconcile();
        } catch (Exception ex) {
            log.error("Elasticsearch composite 对账失败", ex);
        }
    }

    @Scheduled(cron = "0 30 2 * * *")
    public void dailyLightRag() {
        LightRagReconciliationService reconciler = lightRagReconciler.getIfAvailable();
        if (reconciler != null) {
            reconciler.reconcileAll();
        }
        reconcile(ProjectionTarget.LIGHTRAG);
    }

    @Scheduled(cron = "0 0 4 * * MON")
    public void weeklyFullReconciliation() {
        if (jobExecutor.supports(ProjectionTarget.ELASTICSEARCH)
                || jobExecutor.supports(ProjectionTarget.LIGHTRAG)) {
            adminService.reconcile(new ProjectionBatchRequest());
        }
    }

    private void reconcile(ProjectionTarget target) {
        if (!jobExecutor.supports(target)) {
            return;
        }
        try {
            ProjectionBatchRequest request = new ProjectionBatchRequest();
            request.setTarget(target.name());
            adminService.reconcile(request);
        } catch (Exception ex) {
            log.error("定时投影对账失败, target={}", target, ex);
        }
    }
}

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

package com.hjs.study.ragent.rag.projection.worker;

import com.hjs.study.ragent.rag.projection.config.ProjectionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** MQ 丢失或实例重启时的数据库真源补偿扫描器。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectionWorkerScheduler {

    private final ProjectionJobExecutor executor;
    private final ProjectionProperties properties;

    @Scheduled(fixedDelayString = "${rag.projection.scan-delay-ms:5000}", initialDelay = 10_000)
    public void scan() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            executor.dispatchDue();
        } catch (Exception ex) {
            log.error("检索投影补偿扫描失败", ex);
        }
    }
}

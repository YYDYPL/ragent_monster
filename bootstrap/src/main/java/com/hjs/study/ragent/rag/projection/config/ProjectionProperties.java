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

package com.hjs.study.ragent.rag.projection.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 异步检索投影的租约、扫描和重试参数。 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.projection")
public class ProjectionProperties {

    /** 总开关；关闭时业务写入仍然更新 PostgreSQL，但不生成新投影任务。 */
    private boolean enabled = true;

    /** 每次每个目标最多领取的任务数。 */
    private int claimBatchSize = 20;

    /** Worker 租约秒数，执行期间会续租。 */
    private int leaseSeconds = 90;

    /** 补偿扫描间隔。 */
    private long scanDelayMs = 5000L;

    /** 可重试故障退避上限。 */
    private long maxBackoffMs = 21_600_000L;

    /** 执行审计保留天数。 */
    private int attemptRetentionDays = 90;

    /** 批任务审计保留天数。 */
    private int batchRetentionDays = 180;
}

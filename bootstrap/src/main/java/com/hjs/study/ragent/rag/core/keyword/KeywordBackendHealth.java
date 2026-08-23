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

package com.hjs.study.ragent.rag.core.keyword;

import lombok.Getter;
import org.springframework.stereotype.Component;

/** ES 异常只关闭关键词通道，不阻止主应用和向量通道启动。 */
@Getter
@Component
public class KeywordBackendHealth {
    private volatile boolean healthy;
    private volatile String reason = "not-validated";
    private volatile boolean configurationValid = true;

    public void healthy() {
        if (!configurationValid) {
            return;
        }
        this.healthy = true;
        this.reason = null;
    }

    public void unhealthy(String reason) {
        this.healthy = false;
        this.reason = reason;
    }

    public void configurationError(String reason) {
        this.configurationValid = false;
        unhealthy(reason);
    }
}

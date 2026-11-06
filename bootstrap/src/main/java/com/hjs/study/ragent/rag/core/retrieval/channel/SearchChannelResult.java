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

import com.hjs.study.ragent.framework.convention.RetrievedChunk;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索通道结果
 * <p>
 * 封装单个通道的检索结果及元信息
 */
@Data
@Builder
public class SearchChannelResult {

    /**
     * 通道类型
     */
    private SearchChannelType channelType;

    /**
     * 通道名称
     */
    private String channelName;

    /**
     * 检索到的 Chunk 列表
     */
    private List<RetrievedChunk> chunks;

    /**
     * 检索耗时（毫秒）
     */
    private long latencyMs;

    /** 新鲜度门控丢弃的过期/无来源候选数。 */
    private int staleDropped;

    /** 命中投影从期望产生到成功应用的最大延迟。 */
    private long projectionLagMs;

    /** 通道或部分 workspace 降级原因。 */
    private String degradedReason;

    /**
     * 扩展元数据
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}

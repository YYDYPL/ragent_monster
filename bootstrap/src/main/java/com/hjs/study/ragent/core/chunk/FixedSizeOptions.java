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

package com.hjs.study.ragent.core.chunk;

import java.util.Map;

/**
 * {@link com.hjs.study.ragent.core.chunk.strategy.FixedSizeTextChunker} 的配置快照。
 * <p>
 * 当前 record 不在构造阶段校验数值：策略会把普通 {@code chunkSize} 至少收敛到 1，并把
 * overlap 收敛到 {@code [0, chunkSize)}。特殊值 {@code chunkSize=-1} 表示整篇不分块，既会被
 * {@link StructuredChunkingService} 识别，也被 FixedSizeTextChunker 直接支持。
 *
 * @param chunkSize   目标块大小（字符数）
 * @param overlapSize 相邻块重叠大小（字符数）
 */
public record FixedSizeOptions(
        int chunkSize,
        int overlapSize
) implements ChunkingOptions {

    /**
     * 导出数据库/前端使用的稳定键名。
     *
     * @return 不可修改的配置 Map
     */
    @Override
    public Map<String, Integer> toConfigMap() {
        return Map.of("chunkSize", chunkSize, "overlapSize", overlapSize);
    }
}

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
 * 基于文本边界的 legacy 策略配置，当前由
 * {@link com.hjs.study.ragent.core.chunk.strategy.StructureAwareTextChunker} 使用。
 * <p>
 * 四个值都是字符数而非 Token 数。当前 record 本身不校验大小关系；算法以 {@code maxChars}
 * 作为主要打包上限，{@code minChars}/{@code targetChars} 参与小块吸收和末块合并判断。若单个
 * Markdown 原子块已经超过 max，算法会优先保留结构完整性而允许超限。
 * {@code targetChars=-1} 同样是统一入口识别的“整篇不分块”哨兵。
 *
 * @param targetChars  目标块大小（字符数）
 * @param overlapChars 相邻块重叠大小（字符数）
 * @param maxChars     块的硬上限（字符数）
 * @param minChars     块的最小下限（字符数），小于此值会与后续块合并
 */
public record TextBoundaryOptions(
        int targetChars,
        int overlapChars,
        int maxChars,
        int minChars
) implements ChunkingOptions {

    /**
     * 导出数据库/前端使用的稳定键名。
     *
     * @return 不可修改的配置 Map
     */
    @Override
    public Map<String, Integer> toConfigMap() {
        return Map.of("targetChars", targetChars, "overlapChars", overlapChars,
                "maxChars", maxChars, "minChars", minChars);
    }
}

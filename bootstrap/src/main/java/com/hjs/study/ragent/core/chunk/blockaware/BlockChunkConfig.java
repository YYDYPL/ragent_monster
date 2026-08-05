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

package com.hjs.study.ragent.core.chunk.blockaware;

/**
 * block-aware 分块链共享的强类型配置快照。
 * <p>
 * {@link com.hjs.study.ragent.core.chunk.StructuredChunkingService} 从 legacy ChunkingOptions 中
 * 提取通用字符预算，再加上表格和列表默认值构造本 record。各专用 Chunker 只读取自己关心的
 * 字段，避免继续传播弱类型 Map。
 * <p>
 * 所有“大小”均按 Java {@link String#length()} 字符单元计算，不是模型 Token 数。紧凑构造器在
 * 边界处 fail-fast，防止 overlap 导致步长为零或行/项分组死循环。
 *
 * @param maxChars          单个 chunk 最大字符数（ParagraphChunker / CodeChunker 长块切分时用）
 * @param overlapChars      chunk 重叠字符数（ParagraphChunker token 切分时用）
 * @param rowsPerChunk      TableChunker 每个 chunk 包含的数据行数
 * @param maxListItems      ListChunker 短列表 atomic 的阈值
 * @param listItemsPerChunk 长列表每个 chunk 的列表项数
 */
public record BlockChunkConfig(
        int maxChars,
        int overlapChars,
        int rowsPerChunk,
        int maxListItems,
        int listItemsPerChunk
) {

    /**
     * 创建便于测试和独立调用的默认配置。
     * <p>
     * 这里 rowsPerChunk=5 偏向小型测试；统一业务入口的默认值是 50，不应把两者误认为同一套
     * 产品配置。
     */
    public static BlockChunkConfig defaults() {
        return new BlockChunkConfig(512, 64, 5, 15, 10);
    }

    /**
     * 校验所有算法都依赖的数值不变量。
     *
     * @throws IllegalArgumentException 任一预算无法形成有效正向切分
     */
    public BlockChunkConfig {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be > 0, got " + maxChars);
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalArgumentException("overlapChars must be in [0, maxChars), got " + overlapChars);
        }
        if (rowsPerChunk <= 0) {
            throw new IllegalArgumentException("rowsPerChunk must be > 0, got " + rowsPerChunk);
        }
        if (maxListItems <= 0) {
            throw new IllegalArgumentException("maxListItems must be > 0, got " + maxListItems);
        }
        if (listItemsPerChunk <= 0) {
            throw new IllegalArgumentException("listItemsPerChunk must be > 0, got " + listItemsPerChunk);
        }
    }
}

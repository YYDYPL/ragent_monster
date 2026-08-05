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

import java.util.List;

/**
 * 调用单个 {@link BlockChunker} 时使用的不可变上下文快照。
 * <p>
 * 由 {@link BlockAwareChunkerDispatcher} 在遍历 Block 列表时构造并传入，承载：
 * <ul>
 *   <li>{@link #outlinePath}：当前 Block 所在的章节路径（由 HeadingHandler 累积）</li>
 *   <li>{@link #config}：切分参数（chunk 大小、表格 rowsPerChunk 等）</li>
 *   <li>{@link #startIndex}：当前 chunk 序号起点（用于 VectorChunk.index 单调递增）</li>
 * </ul>
 *
 * record 不会防御性复制 outlinePath；当前 Dispatcher 使用 HeadingHandler 返回的不可变列表，
 * 各 Chunker 再复制到 VectorChunk，避免后续标题更新影响已经生成的结果。
 *
 * @param outlinePath 当前 Block 的章节路径（不可变性由调用方保证）
 * @param config      切分配置
 * @param startIndex  本次产出 VectorChunk 的起始 index
 */
public record ChunkContext(
        List<String> outlinePath,
        BlockChunkConfig config,
        int startIndex
) {

    /**
     * 创建从 index=0 开始的上下文，适合单个 Chunker 的测试或独立调用。
     */
    public static ChunkContext of(List<String> outlinePath, BlockChunkConfig config) {
        return new ChunkContext(outlinePath, config, 0);
    }

    /**
     * 创建带全局起始序号的上下文。
     *
     * @param outlinePath 当前章节路径
     * @param config 共享切分配置
     * @param startIndex 当前 Block 产生的首个 Chunk index
     */
    public static ChunkContext of(List<String> outlinePath, BlockChunkConfig config, int startIndex) {
        return new ChunkContext(outlinePath, config, startIndex);
    }
}

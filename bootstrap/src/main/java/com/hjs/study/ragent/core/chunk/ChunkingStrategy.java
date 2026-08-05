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

import java.util.List;

/**
 * legacy 纯文本分块策略扩展接口。
 * <p>
 * 当上游 Parser 没有提供结构化 Block，或调用方明确走纯文本路径时，
 * {@link StructuredChunkingService} 才会通过 {@link ChunkingStrategyFactory} 调用本接口。
 * 已有 Block 的文档由 block-aware 分发器处理，不经过这里。
 * <p>
 * 实现类是 Spring 单例且应保持无状态。接口没有泛型约束 config 的具体 record，当前实现会做
 * 强制类型转换，因此调用方必须使用 {@link ChunkingMode#createOptions(java.util.Map)} 或
 * {@link ChunkingMode#createDefaultOptions(Integer, Integer)} 创建与策略匹配的配置。
 */
public interface ChunkingStrategy {

    /**
     * 获取策略注册键。
     *
     * @return 分块器类型名称
     */
    ChunkingMode getType();

    /**
     * 对完整纯文本进行分块。
     * <p>
     * 返回结果应保持原文顺序，并为每个 Chunk 设置唯一 ID 和从 0 开始的 index。该阶段只生成
     * 文本结构，不调用 Embedding 服务。
     *
     * @param text   待分块的原始文本内容
     * @param config 分块配置参数
     * @return 分块后的有序结果；空白输入通常返回空列表
     */
    List<VectorChunk> chunk(String text, ChunkingOptions config);
}

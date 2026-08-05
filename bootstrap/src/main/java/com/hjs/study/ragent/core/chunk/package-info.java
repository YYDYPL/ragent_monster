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

/**
 * 文档分块（Chunking）核心模块。
 *
 * <p>统一入口是 {@link com.hjs.study.ragent.core.chunk.StructuredChunkingService}，它按以下优先级
 * 决定执行路径：</p>
 *
 * <ol>
 *   <li>配置为整篇不分块：把全文合成一个 DOCUMENT Chunk；</li>
 *   <li>Parser 已产出 Block：进入 {@code blockaware} 强类型分块与打包链；</li>
 *   <li>只有纯文本：通过 {@link com.hjs.study.ragent.core.chunk.ChunkingStrategyFactory} 选择
 *       {@code strategy} 下的 legacy 文本算法。</li>
 * </ol>
 *
 * <p>所有路径最终生成 {@link com.hjs.study.ragent.core.chunk.VectorChunk}。随后
 * {@link com.hjs.study.ragent.core.chunk.ChunkEmbeddingService} 批量填充向量，调用方再写入关系库、
 * 向量库、关键词索引和图谱系统。分块模块本身不负责持久化。</p>
 */
package com.hjs.study.ragent.core.chunk;

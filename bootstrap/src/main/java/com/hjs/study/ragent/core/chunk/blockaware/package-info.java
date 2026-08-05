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
 * 基于 Parser 强类型 Block 的结构化分块实现。
 *
 * <p>{@link com.hjs.study.ragent.core.chunk.blockaware.BlockAwareChunkerDispatcher} 按原文顺序遍历
 * Block：Heading 更新章节路径，其余类型进入专用 Chunker。专用实现只处理单个 Block 内部的拆分，
 * {@link com.hjs.study.ragent.core.chunk.blockaware.ChunkPacker} 再跨相邻 Block 合并小块、保留完整块级
 * 重叠并重排 index。</p>
 *
 * <p>原子块（TABLE、CODE）优先保证结构完整，允许超过字符预算；流动块（PARAGRAPH、LIST、IMAGE）
 * 可以合并到接近预算。整个包只在内存中构造 VectorChunk，不调用模型或存储。</p>
 */
package com.hjs.study.ragent.core.chunk.blockaware;

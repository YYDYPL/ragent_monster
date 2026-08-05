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
 * 没有 Parser Block 时使用的 legacy 纯文本分块策略。
 *
 * <p>{@link com.hjs.study.ragent.core.chunk.strategy.FixedSizeTextChunker} 使用带重叠的固定字符窗口，
 * 并在有限范围内对齐自然边界；
 * {@link com.hjs.study.ragent.core.chunk.strategy.StructureAwareTextChunker} 用轻量 Markdown 扫描器识别
 * 标题、段落、代码和原子链接后再打包。</p>
 *
 * <p>这两种策略都只生成文本 Chunk，不填充 blockType、outlinePath、assets 等 Parser 溯源信息。
 * 上游只要提供非空 Block，统一入口就优先使用 {@code blockaware} 包。</p>
 */
package com.hjs.study.ragent.core.chunk.strategy;

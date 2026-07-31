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
 * 文档解析（Parser）模块。
 *
 * <p>这个包处在“原始文件”与“结构化分块”之间，核心数据流如下：
 *
 * <pre>
 * 文件字节 + MIME + options
 *          │
 *          ▼
 * DocumentParserSelector
 *          │  选择一种 DocumentParser
 *          ▼
 * ParsedDocument
 *          │  有序 Block 列表 + 文档级 metadata
 *          ▼
 * ChunkingStrategy / Block-aware Chunker
 * </pre>
 *
 * <p>阅读时应重点区分三种信息：
 *
 * <ul>
 *   <li>{@code options}：调用方传入的解析上下文，例如源文件名、文档 ID、Excel 表头行数；</li>
 *   <li>{@code Block}：解析阶段的强类型中间表示，保留标题、段落、表格、图片等语义；</li>
 *   <li>{@code metadata}：文档级诊断信息，不参与正文分块，例如解析器类型、页数或批次 ID。</li>
 * </ul>
 *
 * <p>解析器实现只负责“理解文件并产出结构”，不负责向量化、召回或回答生成。图片和 MinerU
 * 解析器因需要持久化图片资产，会调用对象存储；除此之外，分块与索引仍由下游阶段统一完成。
 */
package com.hjs.study.ragent.core.parser;

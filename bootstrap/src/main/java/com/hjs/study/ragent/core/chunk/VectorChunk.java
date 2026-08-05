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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hjs.study.ragent.core.parser.model.AssetRef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块阶段的统一可变结果 DTO，也是 Embedding 与多种索引后端之间的数据载体。
 * <p>
 * 对象按阶段逐步填充：Chunker 先写文本与来源，{@link ChunkEmbeddingService} 再原地写入
 * embedding，最后关系库与向量/关键词/图谱索引读取各自需要的字段。因此这里使用 Lombok 可变
 * Bean，而不是 record。
 * <p>
 * 必须区分三个文本/上下文字段：
 * <ul>
 *   <li>{@link #content}：展示、持久化和 LLM 上下文使用的正文；</li>
 *   <li>{@link #embeddingText}：只用于向量化的优化文本，例如表格 key-value；</li>
 *   <li>{@link #sectionContext}：Sheet、表头等辅助上下文，可同时进入 embeddingText 或检索上下文。</li>
 * </ul>
 * <p>
 * 多模态字段都有空集合默认值，兼容旧数据。Builder 默认值只在 Lombok builder 未显式赋值时
 * 生效；全参构造器、Setter 或反序列化仍可能传入 null，消费者需要保持空值防御。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorChunk {

    /**
     * Chunk 唯一标识符。
     * <p>
     * 算法生成时通常使用雪花 ID；它与 Parser 的 Block.id、文档 docId 是三个不同身份空间。
     */
    private String chunkId;

    /**
     * Chunk 在当前文档中的顺序索引，从 0 开始。
     * <p>
     * 专用 Chunker 先用 ChunkContext.startIndex 编号，ChunkPacker 合并后会统一重排。
     */
    private Integer index;

    /**
     * 对外可见的 Chunk 正文，用于关系库保存、管理端展示与回填 LLM 上下文。
     * 表格为 Markdown，代码保留围栏，图片为描述加 Markdown 图片链接。
     */
    private String content;

    /**
     * 嵌入专用文本，仅用于计算向量，不参与 JSON 序列化。
     * 为空时 {@link ChunkEmbeddingService} 回退到 {@link #content}。
     * 表格 chunk 用 key-value 表示填充此字段（如 "姓名: 张三; 年龄: 25"），
     * 因 markdown 表格行的列名↔值靠位置对齐，embedding 模型读不懂位置，检索效果差
     */
    @JsonIgnore
    private String embeddingText;

    /**
     * 通用 Chunk 元数据扩展区。
     * <p>
     * 强类型的资产、章节和来源字段不要重复塞入此 Map；该字段主要兼容索引后端的附加属性。
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 向量嵌入，按 Embedding 模型维度生成。
     * <p>
     * 通过 {@code @JsonIgnore} 避免普通 API 输出庞大的浮点数组，但向量存储实现会直接读取它。
     */
    @JsonIgnore
    private float[] embedding;

    /**
     * 图片等二进制资产的结构化引用。
     * <p>
     * Block-aware 链路直接从 ImageBlock 复制，避免从 Markdown 字符串反向解析 URL。ChunkPacker
     * 合并图片与正文时会保留这些引用。
     */
    @Builder.Default
    private List<AssetRef> assets = new ArrayList<>();

    /**
     * 内容来源类型，对应 Parser Block 或整篇模式。
     * <p>
     * 当前常见值为 DOCUMENT、PARAGRAPH、TABLE、IMAGE、CODE、LIST。标题只更新章节路径，不直接
     * 产生 HEADING Chunk；ChunkPacker 合并异质可流动块后统一标为 PARAGRAPH。
     */
    private String blockType;

    /**
     * 章节层级路径，如 ["第3章", "3.2 销售分析"]。
     * 由 HeadingHandler 按 Block 顺序累积，打包合并时取所有成员路径的最长公共前缀。
     */
    @Builder.Default
    private List<String> outlinePath = new ArrayList<>();

    /**
     * 来源 Parser Block.id 列表，用于从 Chunk 反查原始结构块。
     * <p>
     * 单块切分通常只有一个 ID；多个小块经 ChunkPacker 合并后会形成去重并集。
     */
    @Builder.Default
    private List<String> sourceBlockIds = new ArrayList<>();

    /**
     * 节级上下文，如 Sheet、caption 和表头摘要。
     * <p>
     * TableChunker 会同时把它放进 embeddingText；图片目前只在有 Sheet 来源时填写。合并多个
     * Chunk 时打包器保留第一个非空值，因此它不是完整的多值集合。
     */
    private String sectionContext;
}

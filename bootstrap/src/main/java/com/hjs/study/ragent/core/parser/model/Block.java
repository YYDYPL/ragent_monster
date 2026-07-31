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

package com.hjs.study.ragent.core.parser.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * 结构化解析产物的统一基类（内存中间表示，IR）。
 * <p>
 * Block 是解析器输出到 ChunkerNode 之间的中间表示。最终进入关系库/向量库的
 * VectorChunk.content 仍是字符串，Markdown 渲染和切分策略由 ChunkerNode 阶段决定。
 * <p>
 * 关键设计：
 * <ul>
 *   <li>sealed interface 保证编译期穷举，新增 Block 类型时所有 switch 必须显式处理</li>
 *   <li>每个子类强类型字段，告别 Map&lt;String,Object&gt; 垃圾桶</li>
 *   <li>id() 提供唯一标识，供 AssetRef.sourceBlockId 与资产 key 规则引用</li>
 *   <li>markdown 不在 Block 上，chunker 渲染时按需生成</li>
 * </ul>
 * <p>
 * Jackson 通过 {@code @type} 字段保存具体子类型，使 Block 能进入任务日志或 JSON 上下文后再
 * 恢复。{@link JsonSubTypes} 与 permits 列表必须同步维护。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HeadingBlock.class, name = "heading"),
        @JsonSubTypes.Type(value = ParagraphBlock.class, name = "paragraph"),
        @JsonSubTypes.Type(value = TableBlock.class, name = "table"),
        @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
        @JsonSubTypes.Type(value = CodeBlock.class, name = "code"),
        @JsonSubTypes.Type(value = ListBlock.class, name = "list")
})
public sealed interface Block permits HeadingBlock, ParagraphBlock, TableBlock, ImageBlock, CodeBlock, ListBlock {

    /**
     * 当前 ParsedDocument 内的 Block 唯一标识。
     * <p>
     * 现有解析器使用 UUID 生成；它用于资产引用和下游追踪，不等于数据库 Chunk ID。
     */
    String id();

    /**
     * 来源信息。当前模型保存源文件和可选 Sheet 名，未来可在不污染正文的前提下扩展页码等位置。
     */
    Provenance provenance();

    /**
     * 章节层级路径，如 ["第3章", "3.2 销售分析"]。
     * <p>
     * 多数解析器创建 Block 时传空列表；ChunkerNode 中的 HeadingHandler 会按标题序列维护
     * sectionContext。字段仍保留在 IR 上，允许能直接识别章节的解析器预先填充。
     */
    List<String> outlinePath();
}

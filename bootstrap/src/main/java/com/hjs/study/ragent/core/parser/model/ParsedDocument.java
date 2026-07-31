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

import java.util.List;
import java.util.Map;

/**
 * 解析器统一输出：有序 Block 列表 + 文档级元数据。
 * <p>
 * 由 DocumentParser.parseStructured() 返回，作为解析阶段 → ChunkerNode 阶段之间的契约。
 * blocks 的顺序具有业务含义；metadata 用于诊断和任务状态，不应存放需要被向量化的正文。
 * <p>
 * 工厂方法只把 null 集合归一化为空集合，不做防御性复制。
 *
 * @param blocks   有序 Block 列表（章节、段落、表格、图片等按文档原始顺序）
 * @param metadata 文档级元数据，如解析器、MIME、外部 batchId、Block 数量等
 */
public record ParsedDocument(List<Block> blocks, Map<String, Object> metadata) {

    /**
     * 创建没有文档级 metadata 的结果。
     *
     * @param blocks 有序 Block，可为 null
     * @return 非 null blocks、空 metadata 的文档
     */
    public static ParsedDocument of(List<Block> blocks) {
        return new ParsedDocument(blocks != null ? blocks : List.of(), Map.of());
    }

    /**
     * 创建同时包含 Block 和 metadata 的结果，并把 null 归一化为空集合。
     */
    public static ParsedDocument of(List<Block> blocks, Map<String, Object> metadata) {
        return new ParsedDocument(blocks != null ? blocks : List.of(), metadata != null ? metadata : Map.of());
    }
}

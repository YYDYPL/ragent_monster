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

/**
 * 段落 Block。
 * <p>
 * ParagraphChunker 可按 Token 切分长段落，并在不跨标题边界的前提下合并相邻短段落。
 * 解析器应尽量让一个 ParagraphBlock 对应原文中的一个自然段。
 *
 * @param id          Block 唯一 ID
 * @param provenance 原始文档来源
 * @param outlinePath 所属章节路径
 * @param text        段落文本；可能包含为兼容保留的链接或 HTML
 */
public record ParagraphBlock(
        String id,
        Provenance provenance,
        List<String> outlinePath,
        String text
) implements Block {
}

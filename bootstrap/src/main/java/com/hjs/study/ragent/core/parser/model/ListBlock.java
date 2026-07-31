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
 * 列表 Block。
 * <p>
 * ListChunker 对短列表整体保留，对长列表按列表项分组。items 只保留直接项的文本，当前
 * Markdown/MinerU Visitor 会把嵌套 inline 内容拍平，不在模型中保存嵌套树。
 *
 * @param id          Block 唯一 ID
 * @param provenance 原始文档来源
 * @param outlinePath 所属章节路径
 * @param ordered     true 表示有序列表，false 表示无序列表
 * @param items       按原文顺序排列的列表项内容
 */
public record ListBlock(
        String id,
        Provenance provenance,
        List<String> outlinePath,
        boolean ordered,
        List<String> items
) implements Block {
}

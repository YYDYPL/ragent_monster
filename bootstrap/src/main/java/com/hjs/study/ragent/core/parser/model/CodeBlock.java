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
 * 代码块 Block。
 * <p>
 * 下游 CodeChunker 把它视为原子 Chunk，避免截断语法结构或把上下文相关的代码片段分离。
 * 这意味着超长代码块可能突破一般目标 Chunk 长度，是完整性优先于长度预算的设计取舍。
 *
 * @param id          Block 唯一 ID
 * @param provenance 原始文档来源
 * @param outlinePath 所属章节路径，通常由下游补充
 * @param language    编程语言标识（如 java、bash），可空
 * @param code        不含外层围栏的代码内容
 */
public record CodeBlock(
        String id,
        Provenance provenance,
        List<String> outlinePath,
        String language,
        String code
) implements Block {
}

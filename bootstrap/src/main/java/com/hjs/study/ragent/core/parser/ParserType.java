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

package com.hjs.study.ragent.core.parser;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档解析器类型枚举。
 * <p>
 * 枚举常量表达代码中的能力类别，{@link #type} 则是注册到
 * {@link DocumentParserSelector} 的稳定字符串。配置或数据库若保存了解析器类型，应保存 type
 * 值而不是依赖 Java 枚举名，避免重命名枚举常量影响外部契约。
 */
@Getter
@RequiredArgsConstructor
public enum ParserType {

    /**
     * Tika 解析器：只作为 text/*、JSON、XML、RTF 等基础文本格式的平文本提取器。
     */
    TIKA("Tika"),

    /**
     * Markdown 解析器：通过 CommonMark AST 保留标题、段落、代码、列表和 GFM 表格结构。
     */
    MARKDOWN("Markdown"),

    /**
     * Apache POI Excel 解析器：面向规整表格，保留合并单元格、多行表头和超链接语义。
     */
    EXCEL_POI("ExcelPoi"),

    /**
     * CSV 解析器：自动探测字符集并按 RFC4180 状态机读取，产出单个表格 Block。
     */
    CSV("Csv"),

    /**
     * MinerU SaaS 解析器：用于 PDF、Word、PPT 等复杂版面；Excel 需由上层显式选择。
     */
    MINERU("MinerU"),

    /**
     * 图片解析器：支持 PNG/JPG/SVG，执行 VLM 图生文并把原图写入资产存储。
     */
    IMAGE("Image");

    /**
     * 暴露给选择器、配置和 metadata 的解析器类型名称。
     */
    private final String type;
}

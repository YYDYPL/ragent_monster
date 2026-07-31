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
 * 基于 Apache POI 的 Excel 解析实现。
 *
 * <p>该实现面向“一张 Sheet 对应一张规整数据表”的场景：
 *
 * <ol>
 *   <li>{@link com.hjs.study.ragent.core.parser.excel.ExcelValueFormatter} 把不同 Cell 类型转为显示文本；</li>
 *   <li>{@link com.hjs.study.ragent.core.parser.excel.ExcelHyperlinkResolver} 恢复单元格元数据中的链接；</li>
 *   <li>{@link com.hjs.study.ragent.core.parser.excel.ExcelTableNormalizer} 展开合并单元格、展平表头并清理空行列；</li>
 *   <li>{@link com.hjs.study.ragent.core.parser.excel.ExcelDocumentParser} 为每个可见 Sheet 产出一个 TableBlock。</li>
 * </ol>
 *
 * <p>复杂排版、多块区域或需要 OCR 的表格不属于这套启发式的目标，应由上层显式选择 MinerU。
 */
package com.hjs.study.ragent.core.parser.excel;

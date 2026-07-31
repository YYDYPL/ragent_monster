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

/**
 * Block 来源信息（溯源用）。
 * <p>
 * 用于检索结果展示和排障时定位原始文档。当前是最小模型，只保存文件与 Excel Sheet；
 * PDF 页码、坐标框、单元格范围等更细粒度信息尚未进入该 record。
 *
 * @param sourceFile 原始文件标识（文件 ID 或文件名）；缺失时部分解析器使用空串
 * @param sheetName  Excel Sheet 名，非 Excel 来源为 null
 */
public record Provenance(String sourceFile, String sheetName) {

    /**
     * 创建仅含文件来源的最小 Provenance。
     */
    public static Provenance ofFile(String sourceFile) {
        return new Provenance(sourceFile, null);
    }

    /**
     * 创建带 Sheet 名的 Excel Provenance。
     */
    public static Provenance ofExcelCell(String sourceFile, String sheetName) {
        return new Provenance(sourceFile, sheetName);
    }
}

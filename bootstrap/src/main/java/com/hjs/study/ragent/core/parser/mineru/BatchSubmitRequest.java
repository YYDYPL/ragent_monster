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

package com.hjs.study.ragent.core.parser.mineru;

/**
 * MinerU 申请上传链接的内部请求模型（精简版、单文件）。
 * <p>
 * 走 MinerU 官方"本地文件批量上传解析":只提交文件元信息,不带 url
 * 该 record 不直接参与 Jackson 序列化。真实请求 JSON 由
 * {@link MinerUClient#requestUpload(BatchSubmitRequest)} 显式构造，以隔离第三方字段命名和响应
 * 版本变化。虽然 MinerU 接口名为 batch，本项目当前一次只提交一个文件。
 * <pre>
 * {
 *   "enable_formula": true,
 *   "enable_table":   true,
 *   "language":       "ch",
 *   "files": [
 *     {
 *       "name":     "xxx.pdf",
 *       "is_ocr":   false,
 *       "data_id":  "doc-uuid"
 *     }
 *   ]
 * }
 * </pre>
 *
 * @param fileName      文件名，必须带正确扩展名，MinerU 依赖它识别格式
 * @param dataId        调用方业务标识，用于在外部结果中关联内部 documentId
 * @param isOcr         是否强制 OCR
 * @param enableTable   是否提取表格
 * @param enableFormula 是否提取公式
 * @param language      语言代码，遵循 MinerU（PaddleOCR）规范，如 ch、en、chinese_cht
 */
public record BatchSubmitRequest(
        String fileName,
        String dataId,
        boolean isOcr,
        boolean enableTable,
        boolean enableFormula,
        String language
) {
}

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

import com.hjs.study.ragent.core.parser.model.ParsedDocument;

import java.util.Map;

/**
 * 文档解析器统一扩展接口。
 * <p>
 * 该接口把不同文件格式的实现统一为同一条契约：
 * {@code byte[] + MIME + options -> ParsedDocument}。调用方只依赖本接口，
 * 具体使用 Tika、POI、CommonMark、VLM 还是 MinerU，由
 * {@link DocumentParserSelector} 决定。
 * <p>
 * 返回值不是最终用于向量化的字符串，而是结构化 {@link ParsedDocument}。
 * 下游分块器会根据 Block 类型分别处理标题、段落、表格、代码和图片，从而避免在解析阶段
 * 过早丢失文档结构。
 * <p>
 * 实现类通常作为 Spring 单例 Bean 使用，因此不应把某次解析的可变状态保存在实例字段中。
 * 与一次请求有关的数据应放在局部变量、{@code options} 或返回的 metadata 中。
 */
public interface DocumentParser {

    /**
     * 获取解析器类型标识。
     * <p>
     * 该值是按类型显式选择解析器时使用的 Map key，同一 Spring 容器内应保持唯一且稳定；
     * 通常直接返回 {@link ParserType#getType()}。
     *
     * @return 解析器类型（如 {@link ParserType#TIKA}、{@link ParserType#MARKDOWN}）
     */
    String getParserType();

    /**
     * 结构化解析：返回有序的 Block 列表（章节、段落、表格、图片等）。
     * <p>
     * Block 的顺序必须与原文阅读顺序一致，因为下游会据此维护标题路径并组合 Chunk。
     * {@code options} 是弱类型扩展区，各实现只能读取自己声明支持的键；未知键应忽略。
     * 文档级诊断信息放入 {@link ParsedDocument#metadata()}，不要伪装成正文 Block。
     * <p>
     * 空输入如何处理由具体解析器决定：纯文本类解析器通常返回空文档，需要外部服务或资产
     * 写入的解析器可能直接抛出业务异常。调用方不应假定所有实现的空输入策略相同。
     *
     * @param content  文档的二进制字节数组
     * @param mimeType 文档的 MIME 类型（可选）
     * @param options  解析选项（可选，可为 null）；常见键包括 sourceFile、documentId
     * @return 结构化解析结果；正常返回值不应为 null
     */
    ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options);

    /**
     * 检查是否支持指定的 MIME 类型。
     * <p>
     * {@link DocumentParserSelector#selectByMimeType(String)} 会按 Spring 注入顺序查找第一个
     * 返回 {@code true} 的实现，因此多个实现的支持范围重叠时，{@code @Order} 会影响最终选择。
     * 默认实现返回 {@code true} 只用于兼容最宽泛的解析器；新增专用解析器应显式覆盖本方法，
     * 避免意外抢占其他格式。
     *
     * @param mimeType MIME 类型
     * @return 当前实现是否愿意处理该 MIME；不表示文件内容一定有效
     */
    default boolean supports(String mimeType) {
        return true;
    }
}

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

import java.util.Map;

/**
 * 旧版纯文本解析结果。
 * <p>
 * 当前 {@link DocumentParser} 的主契约已经升级为
 * {@link com.hjs.study.ragent.core.parser.model.ParsedDocument}，本 record 暂时保留为轻量的
 * “文本 + 元数据”值对象。它在当前生产链路中没有直接调用方，不应在新解析器中替代结构化结果。
 * <p>
 * 工厂方法只对 null metadata 做空 Map 归一化，不会复制调用方传入的 Map。
 *
 * @param text     解析后的文本内容
 * @param metadata 文档元数据（可选）
 */
public record ParseResult(String text, Map<String, Object> metadata) {

    /**
     * 创建只包含文本的解析结果。
     *
     * @param text 解析后的文本；本方法不主动清理或替换 null
     * @return metadata 为空 Map 的结果
     */
    public static ParseResult ofText(String text) {
        return new ParseResult(text, Map.of());
    }

    /**
     * 创建包含文本和元数据的解析结果。
     *
     * @param text     解析后的文本
     * @param metadata 文档元数据；null 会被归一化为空 Map
     * @return 新的结果对象
     */
    public static ParseResult of(String text, Map<String, Object> metadata) {
        return new ParseResult(text, metadata != null ? metadata : Map.of());
    }
}

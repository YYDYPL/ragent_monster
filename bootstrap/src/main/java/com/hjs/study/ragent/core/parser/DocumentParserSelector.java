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

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档解析器选择器（策略模式）。
 * <p>
 * Spring 会把容器内全部 {@link DocumentParser} 实现注入构造器。选择器同时保存：
 * <p>
 * <ul>
 *   <li>有序列表：用于按 MIME 顺序匹配，第一个支持者胜出；</li>
 *   <li>类型 Map：用于调用方明确指定某个解析器。</li>
 * </ul>
 * <p>
 * 列表顺序由 Spring 的 {@code @Order}/{@code Ordered} 规则决定。专用解析器应具有更高优先级，
 * 宽泛兜底解析器应排在最后。找不到解析器时这里返回 null，由业务入口生成包含文件信息的异常，
 * 避免选择器丢失调用上下文。
 */
@Component
public class DocumentParserSelector {

    /**
     * 保留 Spring 注入顺序的策略列表，用于 MIME 的“第一个匹配”语义。
     */
    private final List<DocumentParser> strategies;

    /**
     * parserType 到实现的快速索引，用于显式指定解析器。
     */
    private final Map<String, DocumentParser> strategyMap;

    /**
     * 构建选择器索引。
     * <p>
     * 理论上 parserType 应唯一；若出现重复，合并函数保留先注入的实现。这能保证应用启动，
     * 但重复类型通常意味着配置错误，新增解析器时应主动避免。
     *
     * @param parsers Spring 容器内的全部解析器，顺序已应用 {@code @Order}
     */
    public DocumentParserSelector(List<DocumentParser> parsers) {
        this.strategies = parsers;
        this.strategyMap = parsers.stream()
                .collect(Collectors.toMap(
                        DocumentParser::getParserType,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));
    }

    /**
     * 根据解析器类型精确选择解析策略。
     * <p>
     * 该方法不会做大小写转换、别名解析或兜底，传入值必须与
     * {@link DocumentParser#getParserType()} 完全一致。
     *
     * @param parserType 解析器类型（如 {@link ParserType#TIKA}, {@link ParserType#MARKDOWN}）
     * @return 解析器实例，如果不存在则返回 null
     */
    public DocumentParser select(String parserType) {
        return strategyMap.get(parserType);
    }

    /**
     * 根据 MIME 类型自动选择合适的解析策略。
     * <p>
     * 按 {@link #strategies} 的既定顺序调用 {@link DocumentParser#supports(String)}，
     * 返回第一个匹配项。这里<b>不会静默兜底到 Tika</b>；无匹配时返回 null，
     * 由调用方（如 ParserNode）显式报告“不支持的文件类型”。
     *
     * @param mimeType MIME 类型(如 "application/pdf", "text/markdown")
     * @return 支持该 MIME 类型的解析器;无匹配时返回 null
     */
    public DocumentParser selectByMimeType(String mimeType) {
        return strategies.stream()
                .filter(parser -> parser.supports(mimeType))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取所有可用的解析策略。
     * <p>
     * 返回不可修改的快照视图，避免外部代码破坏选择顺序。
     *
     * @return 解析器列表
     */
    public List<DocumentParser> getAllStrategies() {
        return List.copyOf(strategies);
    }

    /**
     * 获取所有解析器类型，顺序与 MIME 匹配顺序一致。
     *
     * @return 解析器类型列表
     */
    public List<String> getAvailableTypes() {
        return strategies.stream()
                .map(DocumentParser::getParserType)
                .toList();
    }
}

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

package com.hjs.study.ragent.infra.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.NoArgsConstructor;

/**
 * OpenAI 协议风格 SSE（Server-Sent Events）解析器
 * <p>
 * 负责将 SSE 文本行解析为结构化的 {@link ParsedEvent}，支持：
 * <ul>
 *   <li>从 {@code delta} 或 {@code message} 字段提取增量内容（content）</li>
 *   <li>可选提取思考链内容（reasoning_content），通过 {@code reasoningEnabled} 开关控制</li>
 *   <li>识别流结束标记 {@code [DONE]} 和 {@code finish_reason} 字段</li>
 * </ul>
 * <p>
 * 解析策略：优先从 {@code delta} 提取（典型流式 SSE），
 * 若不存在则从 {@code message} 提取（部分厂商的非标准格式）
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class OpenAIStyleSseParser {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_MARKER = "[DONE]";

    /**
     * 解析单行 SSE 文本
     * <p>
     * 处理流程：
     * <ol>
     *   <li>空行跳过，返回空事件</li>
     *   <li>去除 {@code data:} 前缀</li>
     *   <li>匹配 {@code [DONE]} 结束标记 → 返回 completed=true 的事件</li>
     *   <li>JSON 解析后从 choices[0] 提取 content / reasoning_content / finish_reason</li>
     * </ol>
     *
     * @param line             SSE 文本行（可能带 "data:" 前缀）
     * @param gson             JSON 解析器实例
     * @param reasoningEnabled 是否启用思考内容解析（为 true 时才提取 reasoning_content 字段）
     * @return 解析后的事件，不会返回 null
     */
    static ParsedEvent parseLine(String line, Gson gson, boolean reasoningEnabled) {
        if (line == null || line.isBlank()) {
            return ParsedEvent.empty();
        }

        String payload = line.trim();
        if (payload.startsWith(DATA_PREFIX)) {
            payload = payload.substring(DATA_PREFIX.length()).trim();
        }
        if (DONE_MARKER.equalsIgnoreCase(payload)) {
            return ParsedEvent.done();
        }

        JsonObject obj = gson.fromJson(payload, JsonObject.class);
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return ParsedEvent.empty();
        }

        JsonObject choice0 = choices.get(0).getAsJsonObject();
        String content = extractText(choice0, "content");
        String reasoning = reasoningEnabled ? extractText(choice0, "reasoning_content") : null;
        boolean completed = hasFinishReason(choice0);

        return new ParsedEvent(content, reasoning, completed);
    }

    /**
     * 判断 choice 是否包含有效的 finish_reason（表示该 choice 的生成已结束）
     * <p>
     * 同时检查字段存在性和非 null 值，因为部分厂商用 null 表示"尚未结束"
     */
    private static boolean hasFinishReason(JsonObject choice) {
        if (choice == null || !choice.has("finish_reason")) {
            return false;
        }
        JsonElement finishReason = choice.get("finish_reason");
        return finishReason != null && !finishReason.isJsonNull();
    }

    /**
     * 从 choice JSON 中提取指定字段的文本值
     * <p>
     * 兼容两种 SSE 响应结构：
     * <ul>
     *   <li><b>流式增量</b>：从 {@code choice.delta.<fieldName>} 提取（大多数厂商的标准格式）</li>
     *   <li><b>非流式/兜底</b>：从 {@code choice.message.<fieldName>} 提取（部分厂商首次返回用 message）</li>
     * </ul>
     * 优先检查 delta，因为流式 SSE 的标准增量都在 delta 中
     *
     * @param choice    choices[0] JSON 对象
     * @param fieldName 要提取的字段名（如 "content"、"reasoning_content"）
     * @return 字段的字符串值，不存在或为 null 时返回 null
     */
    private static String extractText(JsonObject choice, String fieldName) {
        if (choice == null) {
            return null;
        }
        if (choice.has("delta") && choice.get("delta").isJsonObject()) {
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta.has(fieldName)) {
                JsonElement value = delta.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            JsonObject message = choice.getAsJsonObject("message");
            if (message.has(fieldName)) {
                JsonElement value = message.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        return null;
    }

    /**
     * SSE 单行解析结果（不可变记录）
     *
     * @param content   增量文本内容，null 表示本行无内容
     * @param reasoning 思考链增量内容，null 表示未启用或无思考内容
     * @param completed 是否为流结束事件（收到 [DONE] 标记或 finish_reason 非空）
     */
    record ParsedEvent(String content, String reasoning, boolean completed) {

        /** 空事件：无内容、未完成 */
        static ParsedEvent empty() {
            return new ParsedEvent(null, null, false);
        }

        /** 完成事件：标记流已结束 */
        static ParsedEvent done() {
            return new ParsedEvent(null, null, true);
        }

        /** 是否有非空的增量内容 */
        boolean hasContent() {
            return content != null && !content.isEmpty();
        }

        /** 是否有非空的思考链内容 */
        boolean hasReasoning() {
            return reasoning != null && !reasoning.isEmpty();
        }
    }
}

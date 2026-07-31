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

/**
 * 文本清理工具类。
 * <p>
 * 提供统一的、相对保守的文本规范化，主要供 Tika 平文本输出使用。该工具不会做语言级
 * 分词、标点修复或 HTML 清洗，也不会统一 CRLF/CR 为 LF；调用方若要求跨平台固定换行，
 * 应先单独归一化行结束符。
 * <p>
 * 所有方法都是无状态静态方法，适合并发调用。内部正则会遍历整段文本，不宜在循环中对
 * 同一大文本反复执行。
 */
public final class TextCleanupUtil {

    private TextCleanupUtil() {
    }

    /**
     * 清理文本内容
     * <p>
     * 执行以下清理操作：
     * <ol>
     *   <li>移除 BOM 标记（\uFEFF）；</li>
     *   <li>移除 LF 前多余的空格和制表符；</li>
     *   <li>把连续 3 个以上 LF 压缩为 2 个；</li>
     *   <li>去除整篇首尾空白。</li>
     * </ol>
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    public static String cleanup(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text
                // 移除 BOM 标记
                .replace("\uFEFF", "")
                // 移除行尾的空格和制表符
                .replaceAll("[ \\t]+\\n", "\n")
                // 压缩连续的空行（3个以上压缩为2个）
                .replaceAll("\\n{3,}", "\n\n")
                // 去除首尾空白
                .trim();
    }

    /**
     * 按调用方指定的规则清理文本。
     * <p>
     * {@code maxConsecutiveLines} 统计的是连续换行字符数量，不是“可见空白行”的自然语言数量。
     * 当该值小于等于 0 时，即使 {@code compressEmptyLines=true} 也不会执行压缩。
     *
     * @param text                原始文本
     * @param removeBOM           是否移除 BOM
     * @param trimTrailingSpaces  是否移除行尾空格
     * @param compressEmptyLines  是否压缩空行
     * @param maxConsecutiveLines 最多保留的连续空行数
     * @return 清理后的文本
     */
    public static String cleanup(String text,
                                 boolean removeBOM,
                                 boolean trimTrailingSpaces,
                                 boolean compressEmptyLines,
                                 int maxConsecutiveLines) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String result = text;

        if (removeBOM) {
            result = result.replace("\uFEFF", "");
        }

        if (trimTrailingSpaces) {
            result = result.replaceAll("[ \\t]+\\n", "\n");
        }

        if (compressEmptyLines && maxConsecutiveLines > 0) {
            // 动态构造“超过上限”的正则，并替换为恰好上限个 LF。
            String pattern = "\\n{" + (maxConsecutiveLines + 1) + ",}";
            String replacement = "\n".repeat(maxConsecutiveLines);
            result = result.replaceAll(pattern, replacement);
        }

        return result.trim();
    }
}

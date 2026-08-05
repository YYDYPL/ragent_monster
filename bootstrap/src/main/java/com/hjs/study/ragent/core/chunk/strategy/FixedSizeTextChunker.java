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

package com.hjs.study.ragent.core.chunk.strategy;

import cn.hutool.core.util.IdUtil;
import com.hjs.study.ragent.core.chunk.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * legacy 纯文本固定字符窗口分块器。
 * <p>
 * 算法先做保守文本归一化，再按 chunkSize 建立窗口；窗口末端只在 overlap 大小的回看区间内
 * 尝试对齐换行和句末标点。下一窗口从 {@code end-overlap} 开始，因此重叠既是上下文复制量，
 * 也是允许向前寻找自然边界的最大距离。
 * <p>
 * “固定大小”按 Java 字符单元而非 Token 计数。归一化会去掉 CR、拼接明显断行的 URL，并合并
 * 两个汉字之间的软换行，所以输出不是输入字符串的逐字符无损切片。需要严格保留 Markdown
 * 原貌时应选 {@link StructureAwareTextChunker}，有 Parser Block 时则优先走 block-aware 链路。
 * <p>
 * 实现无共享可变状态，可作为 Spring 单例并发调用。
 */
@Component
public class FixedSizeTextChunker implements ChunkingStrategy {

    /** 返回工厂注册键 FIXED_SIZE。 */
    @Override
    public ChunkingMode getType() {
        return ChunkingMode.FIXED_SIZE;
    }

    /**
     * 归一化并按固定字符窗口生成有序 Chunk。
     * <p>
     * config 必须是与 FIXED_SIZE 对应的 {@link FixedSizeOptions}，否则强制转换会失败。普通非法
     * 数值在本方法内被钳制；chunkSize=-1 直接返回整篇单 Chunk。
     *
     * @param text 原始纯文本；空白文本返回空列表
     * @param config FixedSizeOptions
     * @return index 从 0 递增的 Chunk 列表
     */
    @Override
    public List<VectorChunk> chunk(String text, ChunkingOptions config) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        // 归一化只在明确规则下改写断行；之后所有 start/end 都基于 normalized。
        String normalized = normalizeText(text);

        FixedSizeOptions opts = (FixedSizeOptions) config;
        int configuredChunkSize = opts.chunkSize();
        if (configuredChunkSize == -1) {
            return List.of(VectorChunk.builder()
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .index(0)
                    .content(normalized)
                    .build());
        }

        // 非哨兵的 0/负数至少按 1 字符窗口处理，避免算法停滞。
        int chunkSize = Math.max(1, configuredChunkSize);
        int overlap = Math.max(0, opts.overlapSize());

        if (chunkSize > 1) {
            overlap = Math.min(overlap, chunkSize - 1);
        } else {
            overlap = 0;
        }

        int len = normalized.length();
        List<VectorChunk> chunks = new ArrayList<>();

        int index = 0;
        int start = 0;
        int lastEnd = -1;

        while (start < len) {
            int targetEnd = Math.min(start + chunkSize, len);
            int end = adjustToBoundary(normalized, start, targetEnd, overlap);

            // 边界对齐不得回到当前窗口之前，也不得重复使用上次 end。
            if (end <= start || end <= lastEnd) {
                end = targetEnd;
            }

            String content = normalized.substring(start, end);
            if (StringUtils.hasText(content.strip())) {
                chunks.add(VectorChunk.builder()
                        .chunkId(IdUtil.getSnowflakeNextIdStr())
                        .index(index++)
                        .content(content)
                        .build());
            }

            lastEnd = end;
            if (end >= len) break;

            // 字符级 overlap 从刚落块的尾部开始；最后再防御 step 没有前进。
            int nextStart = Math.max(0, end - overlap);
            if (nextStart <= start) nextStart = end;
            start = nextStart;
        }

        return chunks;
    }

    /**
     * 在目标末端之前的有限窗口内寻找更自然的结束位置：
     * - 优先：换行
     * - 其次：中文句末标点（。！？）
     * - 再次：英文 .!?（仅当后面是空白/换行/结束 才算边界，避免切 URL 域名点号）
     * <p>
     * 回退距离不超过 overlap，避免为了对齐很远的标点造成短块和大面积重复。优先级固定为换行、
     * 中文句末、英文句末；英文点号后必须是空白或文本结束，避免域名中的点被误判。
     */
    private int adjustToBoundary(String text, int start, int targetEnd, int overlap) {
        if (targetEnd <= start) return targetEnd;

        int maxLookback = Math.min(overlap, targetEnd - start);
        if (maxLookback <= 0) return targetEnd;

        // 1) 换行
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) break;
            if (text.charAt(pos) == '\n') return pos + 1;
        }

        // 2) 中文句末标点
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) break;
            char c = text.charAt(pos);
            if (c == '。' || c == '！' || c == '？') return pos + 1;
        }

        // 3) 英文句末标点：后面必须是空白/换行/结束
        for (int i = 0; i <= maxLookback; i++) {
            int pos = targetEnd - i - 1;
            if (pos <= start) break;
            char c = text.charAt(pos);
            if (c == '.' || c == '!' || c == '?') {
                int next = pos + 1;
                if (next >= text.length()) return next;
                if (Character.isWhitespace(text.charAt(next))) return next;
            }
        }

        return targetEnd;
    }

    /**
     * 用单次线性扫描归一化输入：
     * - 去掉 \r
     * - 修复“URL 被换行拆开”的情况（比如 dingtalk.\ncom、/i/nodes\n/...）
     * - 但如果换行后是“2.” 这种列表项开头，绝不合并（避免吞段落）
     * - URL 结束时保留原始空白（包括空行）
     * - 修复中文词中间软换行（商\n保通 -> 商保通）。
     * <p>
     * URL 状态只由 http:// 或 https:// 触发；遇到不应拼接的空白会原样写回并退出状态。两个以上
     * 换行被视为段落边界，绝不跨越。该方法不 trim 全文。
     */
    private String normalizeText(String text) {
        if (text == null || text.isEmpty()) return text;

        String src = text.replace("\r", "");
        StringBuilder out = new StringBuilder(src.length());

        boolean inUrl = false;

        for (int i = 0; i < src.length(); i++) {
            if (!inUrl && looksLikeUrlStart(src, i)) {
                inUrl = true;
            }

            char c = src.charAt(i);

            if (inUrl) {
                if (Character.isWhitespace(c)) {
                    int j = i;
                    int newlineCount = 0;
                    while (j < src.length() && Character.isWhitespace(src.charAt(j))) {
                        if (src.charAt(j) == '\n') newlineCount++;
                        j++;
                    }
                    boolean sawNewline = newlineCount > 0;
                    // 空行(≥2 换行)是段落分隔，URL 软换行只会是单个换行;跨空行绝不合并，
                    // 否则会把 markdown 图片链接 ![](url) 与其后另起的标题/段落粘连(如 ![](url)## 标题)
                    boolean blankLine = newlineCount >= 2;

                    char prev = (i > 0) ? src.charAt(i - 1) : 0;
                    char next = (j < src.length()) ? src.charAt(j) : 0;

                    // 只在“很像 URL 被拆开”的情况下合并空白
                    if (sawNewline && !blankLine && next != 0 && shouldJoinBrokenUrl(prev, next, src, j)) {
                        i = j - 1;
                        continue;
                    }

                    // URL 结束：保留原始空白（包括空行）
                    out.append(src, i, j);
                    inUrl = false;
                    i = j - 1;
                    continue;
                }

                out.append(c);

                // 遇到明显不可能属于 URL 的字符，退出 URL 状态
                if (!isUrlChar(c) && !isCommonUrlPunct(c)) {
                    inUrl = false;
                }
                continue;
            }

            // 非 URL 状态：修复中文词中间软换行（商\n保通 -> 商保通）
            if (c == '\n') {
                char prev = (i > 0) ? src.charAt(i - 1) : 0;
                char next = (i + 1 < src.length()) ? src.charAt(i + 1) : 0;

                if (isCjkWordChar(prev) && isCjkWordChar(next)) {
                    continue;
                }

                out.append('\n');
                continue;
            }

            out.append(c);
        }

        return out.toString();
    }

    /**
     * 判断 URL 内的单次断行是否应删除。
     * <p>
     * 先排除数字列表项，再依据断点前后的 URL 结构字符做保守判断；普通字母之间的换行不会被
     * 无条件拼接。
     */
    private boolean shouldJoinBrokenUrl(char prev, char next, String s, int nextIndex) {
        // 如果下一行像 “2.” “10.” 这种列表项开头 -> 绝不合并
        if (isListItemStart(s, nextIndex)) {
            return false;
        }

        // 典型的 URL 断行场景：在这些字符后面换行，后续大概率还是 URL
        if (prev == '.' && Character.isLetter(next)) return true;                 // dingtalk.\ncom
        if (prev == '/' || prev == '?' || prev == '&' || prev == '='
                || prev == '#' || prev == '%' || prev == '-' || prev == '_'
                || prev == ':') return true;                                       // /i/nodes\n/...  ?\nutm=...

        // 或者下一段本身以 URL 结构符号开头
        if (next == '/' || next == '?' || next == '&' || next == '=' || next == '#') return true;

        // 其他情况更保守：不合并，保留换行
        return false;
    }

    /**
     * 检查新行是否以数字列表标记开头，兼容缩进和 {@code 1.}/{@code 1)}/{@code 1）}。
     */
    private boolean isListItemStart(String s, int i) {
        // 跳过可能存在的空格/制表符（一般是新行后的缩进）
        int p = i;
        while (p < s.length() && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;

        int start = p;
        while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
        if (p == start) return false;

        // 数字后紧跟 '.' 或 '）' / ')' 也常见
        if (p < s.length() && (s.charAt(p) == '.' || s.charAt(p) == '）' || s.charAt(p) == ')')) {
            return true;
        }
        return false;
    }

    /** 只识别显式 HTTP(S) scheme，不把裸域名当作 URL 状态起点。 */
    private boolean looksLikeUrlStart(String s, int i) {
        if (i < 0 || i >= s.length()) return false;
        return s.startsWith("http://", i) || s.startsWith("https://", i);
    }

    /** 判断 RFC 常见 URL 字母、数字和保留字符。 */
    private boolean isUrlChar(char c) {
        if (c >= 'a' && c <= 'z') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (c >= '0' && c <= '9') return true;

        return c == '-' || c == '.' || c == '_' || c == '~'
                || c == ':' || c == '/' || c == '?' || c == '#'
                || c == '[' || c == ']' || c == '@'
                || c == '!' || c == '$' || c == '&' || c == '\''
                || c == '(' || c == ')' || c == '*' || c == '+'
                || c == ',' || c == ';' || c == '=' || c == '%';
    }

    /** URL 状态机额外容忍的常见连接符号。 */
    private boolean isCommonUrlPunct(char c) {
        return c == '.' || c == '/' || c == '?' || c == '&' || c == '=' || c == '-' || c == '_' || c == '%';
    }

    /**
     * 判断字符能否位于中文词内部，用于决定单个 LF 是否可视为排版软换行。
     */
    private boolean isCjkWordChar(char c) {
        if (c == 0) return false;
        if (Character.isWhitespace(c)) return false;
        if (!isCjkOrFullWidthLetterOrDigit(c)) return false;
        return !isCjkPunctuation(c);
    }

    /** 按 UnicodeBlock 识别常用 CJK 表意文字与全角字符范围。 */
    private boolean isCjkOrFullWidthLetterOrDigit(char c) {
        if (c == 0) return false;
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    /** 排除中文及通用标点，防止在句号、括号等位置错误吞掉换行。 */
    private boolean isCjkPunctuation(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.GENERAL_PUNCTUATION
                || c == '。' || c == '，' || c == '、' || c == '；' || c == '：'
                || c == '！' || c == '？' || c == '（' || c == '）' || c == '【' || c == '】'
                || c == '《' || c == '》' || c == '“' || c == '”' || c == '‘' || c == '’';
    }
}

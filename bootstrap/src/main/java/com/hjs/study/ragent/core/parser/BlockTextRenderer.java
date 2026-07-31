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

import com.hjs.study.ragent.core.parser.model.*;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Block 列表 → 兼容性纯文本渲染器。
 * <p>
 * 把 {@link com.hjs.study.ragent.core.parser.model.ParsedDocument} 的 Block 列表渲染为纯文本，
 * 供 PIPELINE 链路的 rawText 兼容字段与非结构化分块路径共用同一份实现。
 * <p>
 * 这里追求“信息不丢失”和“便于阅读”，不是完整 Markdown 序列化器：
 * 标题、代码、列表和图片使用最小 Markdown 形式，表格只用竖线连接，不生成对齐分隔行。
 * Block-aware 路径仍由对应 Chunker 决定最终 Chunk 文本和 embeddingText。
 * <p>
 * 方法不修改传入 Block；未知或 null 元素会被忽略。由于 {@link Block} 是 sealed interface，
 * 新增 Block 子类型后应同步更新本类，否则兼容性文本会缺失该类型内容。
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class BlockTextRenderer {

    /**
     * 把 Block 列表按原有顺序渲染为一段文本。
     *
     * @param blocks 有序 Block 列表，为 null 时返回空串
     * @return 渲染后的纯文本
     */
    public static String render(List<Block> blocks) {
        if (blocks == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (b instanceof HeadingBlock h) {
                // 非法或缺失层级最少按一级标题输出，避免 String.repeat 收到负数。
                sb.append("#".repeat(Math.max(1, h.level())))
                        .append(' ').append(h.text() == null ? "" : h.text()).append("\n\n");
            } else if (b instanceof ParagraphBlock p) {
                sb.append(p.text() == null ? "" : p.text()).append("\n\n");
            } else if (b instanceof TableBlock t) {
                // 表格在兼容文本中保留行列边界；严格的 Markdown 表格由 TableChunker 负责。
                if (t.headers() != null) {
                    sb.append(String.join(" | ", t.headers())).append('\n');
                }
                if (t.rows() != null) {
                    for (List<String> row : t.rows()) {
                        sb.append(String.join(" | ", row)).append('\n');
                    }
                }
                sb.append('\n');
            } else if (b instanceof ImageBlock i) {
                // 描述在前、图片 markdown 在后（与 ImageChunker 一致）：图生文描述是唯一可检索文本，
                // 整篇/legacy 等拍平路径若只渲染 ![](url) 会把描述丢掉，导致永远召回不到
                if (i.description() != null && !i.description().isBlank()) {
                    sb.append(i.description().strip()).append("\n\n");
                }
                sb.append("![")
                        .append(i.caption() == null ? "" : i.caption()).append("](")
                        .append(i.asset() == null ? "" : i.asset().publicUrl()).append(")\n\n");
            } else if (b instanceof CodeBlock c) {
                // 围栏可防止代码中的换行、列表符号被误认为普通 Markdown 结构。
                sb.append("```").append(c.language() == null ? "" : c.language())
                        .append('\n').append(c.code() == null ? "" : c.code()).append("\n```\n\n");
            } else if (b instanceof ListBlock l) {
                if (l.items() != null) {
                    for (int idx = 0; idx < l.items().size(); idx++) {
                        sb.append(l.ordered() ? (idx + 1) + ". " : "- ")
                                .append(l.items().get(idx)).append('\n');
                    }
                    sb.append('\n');
                }
            }
        }
        // 只移除整篇首尾空白，Block 内部文本保持原样。
        return sb.toString().trim();
    }
}

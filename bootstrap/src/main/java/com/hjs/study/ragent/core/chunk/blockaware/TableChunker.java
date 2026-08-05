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

package com.hjs.study.ragent.core.chunk.blockaware;

import cn.hutool.core.util.IdUtil;
import com.hjs.study.ragent.core.chunk.VectorChunk;
import com.hjs.study.ragent.core.parser.model.TableBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * TableBlock 的按行语义切分器，每个结果都携带完整表头。
 * <p>
 * 关键设计：
 * <ul>
 *   <li>headers 从 TableBlock.headers 直接取，不靠正则提取（vs 老路径的字符串 chunker）</li>
 *   <li>按 key-value 行文本长度贪心累加到 maxChars 预算，{@code rowsPerChunk} 作为硬上限，
 *       兼顾宽表不超 embedding 上限、窄表不过度碎片化；单行体量超预算时保持整行原子，自成一块</li>
 *   <li>content 渲染为完整 markdown 表格（展示）；embeddingText 用 key-value（嵌入）</li>
 *   <li>sectionContext 写入 sheet 名 + 表头摘要，便于检索时回填上下文</li>
 *   <li>无数据行的 TableBlock（仅 headers）：产生一个仅含表头的 chunk</li>
 * </ul>
 * <p>
 * maxChars 是按“各行 key-value 长度之和”估算的软预算，不包含 sectionContext、行间换行、Markdown
 * 表头和分隔行。因此最终 content/embeddingText 可能略大于预算；单行超长时也会主动允许超限，
 * 以免拆断一条业务记录。TABLE 被 ChunkPacker 视为原子边界，不与两侧内容合并。
 */
@Component
public class TableChunker implements BlockChunker<TableBlock> {

    /**
     * 把表格按字符预算和行数上限分组。
     *
     * @return 空表返回空列表；仅有表头时仍返回一个 TABLE Chunk
     */
    @Override
    public List<VectorChunk> chunk(TableBlock block, ChunkContext ctx) {
        if (block == null) {
            return List.of();
        }
        List<String> headers = block.headers() == null ? List.of() : block.headers();
        List<List<String>> rows = block.rows() == null ? List.of() : block.rows();

        if (headers.isEmpty() && rows.isEmpty()) {
            return List.of();
        }

        // 预算按嵌入用 key-value 行估算；展示 Markdown 的长度不参与切块判断。
        int budget = Math.max(1, ctx.config().maxChars());
        int maxRows = Math.max(1, ctx.config().rowsPerChunk());
        String sectionContext = buildSectionContext(block);
        List<VectorChunk> result = new ArrayList<>();
        int chunkIndex = ctx.startIndex();

        if (rows.isEmpty()) {
            // 仅表头也是有意义的结构，可用于说明数据字典或空模板。
            result.add(buildChunk(headers, List.of(), block, ctx, chunkIndex, sectionContext));
            return result;
        }

        // 非空 group 加入下一行才检查预算；因此第一行无论多长都会完整保留。
        List<List<String>> group = new ArrayList<>();
        int groupCost = 0;
        for (List<String> row : rows) {
            int rowCost = renderKeyValueRow(headers, row).length();
            boolean overCap = group.size() >= maxRows;
            boolean overBudget = !group.isEmpty() && groupCost + rowCost > budget;
            if (overCap || overBudget) {
                result.add(buildChunk(headers, group, block, ctx, chunkIndex++, sectionContext));
                group = new ArrayList<>();
                groupCost = 0;
            }
            group.add(row);
            groupCost += rowCost;
        }
        result.add(buildChunk(headers, group, block, ctx, chunkIndex, sectionContext));
        return result;
    }

    /**
     * 同时构造展示正文和向量化正文，并复制表级溯源信息。
     */
    private VectorChunk buildChunk(List<String> headers,
                                   List<List<String>> rows,
                                   TableBlock block,
                                   ChunkContext ctx,
                                   int chunkIndex,
                                   String sectionContext) {
        String markdown = renderMarkdownTable(headers, rows);
        String embeddingText = buildEmbeddingText(headers, rows, sectionContext);
        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(chunkIndex)
                .content(markdown)
                .embeddingText(embeddingText)
                .blockType("TABLE")
                .outlinePath(new ArrayList<>(ctx.outlinePath()))
                .sourceBlockIds(List.of(block.id()))
                .sectionContext(sectionContext)
                .build();
    }

    /**
     * 构造嵌入专用文本：sectionContext 作首行，每条数据记录转成 key-value。
     * <p>
     * Markdown 表格的列值关系依赖位置；改用 {@code 列名: 值} 把关系显式写入字面。
     * sectionContext 随每块嵌入，相当于轻量 contextual chunking，使切分后的行仍知道所属 Sheet
     * 和完整表头。
     */
    private String buildEmbeddingText(List<String> headers, List<List<String>> rows, String sectionContext) {
        String kvRows = renderKeyValueRows(headers, rows);
        if (sectionContext == null || sectionContext.isEmpty()) {
            return kvRows;
        }
        if (kvRows.isEmpty()) {
            return sectionContext;
        }
        return sectionContext + "\n" + kvRows;
    }

    /**
     * 把多条数据行渲染成换行分隔的 key-value 文本；全空行不输出。
     */
    private String renderKeyValueRows(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            String line = renderKeyValueRow(headers, row);
            if (line.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 单行渲染成 key-value：{@code 列名: 值} 用分号拼接，跳过空值 Cell。
     * <p>
     * 行长度同时用作贪心预算成本。若 row 比 headers 更宽，额外列只保留值，不伪造列名。
     */
    private String renderKeyValueRow(List<String> headers, List<String> row) {
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < row.size(); c++) {
            String value = row.get(c);
            if (value == null || value.isEmpty()) {
                continue;
            }
            String key = c < headers.size() ? headers.get(c) : "";
            if (!line.isEmpty()) {
                line.append("; ");
            }
            if (!key.isEmpty()) {
                line.append(oneLine(key)).append(": ");
            }
            line.append(oneLine(value));
        }
        return line.toString();
    }

    /**
     * 把 Cell 内换行压成空格，保证一条业务记录在 embeddingText 中占一行。
     */
    private static String oneLine(String text) {
        return text.replaceAll("\\r\\n|\\r|\\n", " ");
    }

    /**
     * 渲染展示用标准 Markdown 表格：表头、分隔行和数据行。
     */
    private String renderMarkdownTable(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        appendSeparator(sb, headers.size());
        for (List<String> row : rows) {
            appendRow(sb, row);
        }
        // 去掉末尾换行
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    /** 追加一行并对每个 Cell 做 Markdown 语法清洗。 */
    private void appendRow(StringBuilder sb, List<String> cells) {
        sb.append('|');
        for (String cell : cells) {
            sb.append(' ').append(sanitizeCell(cell)).append(" |");
        }
        sb.append('\n');
    }

    /**
     * 清洗 cell 以适配 markdown 表格语法
     * <p>
     * 单元格内换行（Excel Alt+Enter）转 {@code <br>}：裸 \n 会从中间截断表格行，使整块退化为普通段落；
     * 竖线转义为 {@code \|}：cell 内的字面 |（如多行表头展平拼接的「财务|收入」）会被误判为列分隔
     */
    private String sanitizeCell(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        return cell.replace("|", "\\|")
                .replaceAll("\\r\\n|\\r|\\n", "<br>");
    }

    /** 按列数追加 Markdown 表头分隔行。 */
    private void appendSeparator(StringBuilder sb, int colCount) {
        sb.append('|');
        sb.append("---|".repeat(Math.max(0, colCount)));
        sb.append('\n');
    }

    /**
     * 构造表级上下文：可选 Sheet、caption 与完整表头。
     * <p>
     * 该字符串既进入 embeddingText，也保存在 VectorChunk.sectionContext，便于检索后处理再次
     * 注入 LLM 上下文。所有部分都空时返回 null。
     */
    private String buildSectionContext(TableBlock block) {
        StringBuilder ctx = new StringBuilder();
        if (block.provenance() != null && block.provenance().sheetName() != null) {
            ctx.append("sheet=").append(block.provenance().sheetName());
        }
        if (block.captionText() != null && !block.captionText().isEmpty()) {
            if (!ctx.isEmpty()) {
                ctx.append("; ");
            }
            ctx.append("caption=").append(block.captionText());
        }
        if (block.headers() != null && !block.headers().isEmpty()) {
            if (!ctx.isEmpty()) {
                ctx.append("; ");
            }
            // 用 ", " 连接 headers,避免与多行表头内部分隔符 "|" 视觉冲突
            ctx.append("headers=").append(String.join(", ", block.headers()));
        }
        return ctx.isEmpty() ? null : ctx.toString();
    }
}

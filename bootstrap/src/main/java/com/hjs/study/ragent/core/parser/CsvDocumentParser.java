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
import com.hjs.study.ragent.core.parser.model.Provenance;
import com.hjs.study.ragent.core.parser.model.TableBlock;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.detect.AutoDetectReader;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * CSV 文档解析器。
 * <p>
 * 把 CSV 当作一张规整的 key-val 表：首行为表头，其余为数据行，产出单个 {@link TableBlock}，
 * 下游与 Excel 共用 TableChunker 做行级切分和 key-value 文本构造。
 * <ul>
 *   <li>字符集：用 Tika {@link AutoDetectReader} 自动探测（兼容 UTF-8 / GBK / UTF-16 等），并剥离 BOM</li>
 *   <li>结构：RFC4180 解析，支持引号包裹字段、字段内逗号 / 换行、{@code ""} 转义</li>
 *   <li>对齐：数据行短于表头时右侧补空，保证列对齐</li>
 *   <li>全空行跳过</li>
 * </ul>
 * <p>
 * 优先级高于 Tika（{@code text/csv} 已从 Tika 排除），避免 CSV 被当平文本切碎。
 * <p>
 * 当前实现固定使用逗号作为分隔符，不自动识别分号、Tab 或其他方言；首行始终视为表头，
 * 也不会推断列类型。解析器只恢复二维结构，类型解释交给检索与 LLM。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CsvDocumentParser implements DocumentParser {

    /**
     * options 中的源文件名键，用于生成 Block 的 Provenance。
     */
    public static final String OPT_SOURCE_FILE = "sourceFile";

    /**
     * Unicode BOM 字符。字符集解码完成后再剥离，避免它污染第一列表头。
     */
    private static final char BOM = '\uFEFF';

    @Override
    public String getParserType() {
        return ParserType.CSV.getType();
    }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase(Locale.ROOT);
        return lower.equals("text/csv")
                || lower.equals("application/csv")
                || lower.equals("text/comma-separated-values");
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }

        // 先完成字符集层面的解码，再让 CSV 状态机只处理 Java 字符。
        String text = decode(content);
        List<List<String>> grid = parseCsv(text);
        // 全空记录没有检索价值，也会影响首行表头判定，因此在取表头前清理。
        grid.removeIf(CsvDocumentParser::isBlankRow);
        if (grid.isEmpty()) {
            return ParsedDocument.of(List.of());
        }

        // 第一条有效记录是表头；后续短行补齐，维持“表头索引 = 单元格索引”。
        List<String> headers = grid.get(0);
        int width = headers.size();
        List<List<String>> rows = new ArrayList<>(grid.size() - 1);
        for (int i = 1; i < grid.size(); i++) {
            rows.add(padRow(grid.get(i), width));
        }

        Provenance prov = Provenance.ofFile(extractSourceFile(options));
        TableBlock block = new TableBlock(UUID.randomUUID().toString(), prov, List.of(), headers, rows, null);
        return ParsedDocument.of(List.of(block), Map.of(
                "parser", getParserType(),
                "mimeType", mimeType == null ? "" : mimeType,
                "rows", rows.size(),
                "columns", width
        ));
    }

    /**
     * 自动探测字符集并解码为文本，失败时回退 UTF-8。
     * <p>
     * AutoDetectReader 和回退路径都只负责“尽量得到字符串”，不会判断乱码是否业务可接受。
     * 两条路径最终都会剥离开头 BOM。
     *
     * @param content CSV 原始字节
     * @return 已解码、无开头 BOM 的文本
     */
    private String decode(byte[] content) {
        try (Reader reader = new AutoDetectReader(new ByteArrayInputStream(content))) {
            StringBuilder sb = new StringBuilder(content.length);
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return stripBom(sb.toString());
        } catch (Exception e) {
            log.warn("CSV 字符集探测失败，回退 UTF-8", e);
            return stripBom(new String(content, StandardCharsets.UTF_8));
        }
    }

    /**
     * 只移除第一个字符位置的 BOM；正文中的同码点保持不变。
     */
    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }

    /**
     * 用单次线性扫描解析 CSV。
     * <p>
     * 状态机只有“引号内/引号外”两种状态：引号内的逗号与换行属于字段正文，连续两个引号
     * 还原为一个字面量引号；引号外的逗号提交字段，CR、LF 或 CRLF 提交记录。该实现不抛出
     * 严格语法错误，未闭合引号会把剩余文本当作最后一个字段，体现的是尽量摄取策略。
     *
     * @param text 已完成字符集解码的 CSV 文本
     * @return 行优先二维网格，字段值不额外 trim
     */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    // 引号内的双引号转义：消费两个输入字符，只输出一个引号。
                    if (i + 1 < len && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                field.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                // 进入引用字段。实现宽松接受字段中途出现的引号。
                inQuotes = true;
                i++;
            } else if (c == ',') {
                // 字段结束；即便 field 为空也必须保存，以保留空列位置。
                current.add(field.toString());
                field.setLength(0);
                i++;
            } else if (c == '\r' || c == '\n') {
                // 记录结束；先提交当前字段，再创建下一行容器。
                current.add(field.toString());
                field.setLength(0);
                rows.add(current);
                current = new ArrayList<>();
                // 吞掉 CRLF 的第二个字符
                i += (c == '\r' && i + 1 < len && text.charAt(i + 1) == '\n') ? 2 : 1;
            } else {
                field.append(c);
                i++;
            }
        }
        // 末尾未以换行结束的残留记录
        if (!field.isEmpty() || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }

    /**
     * 数据行短于表头时在右侧补空串。
     * <p>
     * 超过表头宽度的行保持原样，不截断额外字段；这能避免静默丢数据，但意味着异常 CSV
     * 的某些 row 长度可能大于 headers 长度。
     */
    private static List<String> padRow(List<String> row, int width) {
        if (row.size() >= width) {
            return row;
        }
        List<String> padded = new ArrayList<>(width);
        padded.addAll(row);
        while (padded.size() < width) {
            padded.add("");
        }
        return padded;
    }

    /**
     * 判断一行是否所有字段都为 null 或 Unicode 空白。
     */
    private static boolean isBlankRow(List<String> row) {
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 读取源文件标识；缺失时用空串而不是 null。
     */
    private String extractSourceFile(Map<String, Object> options) {
        if (options == null) {
            return "";
        }
        Object v = options.get(OPT_SOURCE_FILE);
        return v == null ? "" : v.toString();
    }
}

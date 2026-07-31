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

import com.hjs.study.ragent.core.parser.model.Block;
import com.hjs.study.ragent.core.parser.model.ParagraphBlock;
import com.hjs.study.ragent.core.parser.model.ParsedDocument;
import com.hjs.study.ragent.core.parser.model.Provenance;
import com.hjs.study.ragent.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Apache Tika 平文本解析器。
 * <p>
 * Tika 本身能识别很多格式，但本项目有意把该实现收窄为“基础文本兜底”：Markdown、CSV、
 * Excel、图片以及复杂办公文档都有更能保留结构的专用解析器。这里把 Tika 提取出的平文本按
 * 空行拆成 {@link ParagraphBlock}，不会恢复标题、表格、页码或图片。
 * <p>
 * {@link Order} 设为最低优先级，确保 MIME 范围发生重叠时专用解析器先被选择。解析异常统一
 * 转换为 {@link ServiceException}，上层可以按文档任务记录失败状态。
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TikaDocumentParser implements DocumentParser {

    private static final Tika TIKA = new Tika();

    static {
        // 注意：当前配置对象没有注入上面的 TIKA 实例，因此实际解析仍使用 Tika 默认 PDF 配置。
        // 保留这段现状说明，避免学习者误以为下面两个 setter 已经改变 parseToString 的行为。
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setExtractUniqueInlineImagesOnly(true);
    }

    @Override
    public String getParserType() {
        return ParserType.TIKA.getType();
    }

    /**
     * 结构化解析：按两个及以上 LF 分段，输出 ParagraphBlock 列表。
     * <p>
     * 处理步骤是“字节流提取 → 文本清理 → 空行分段 → 生成来源信息”。每个非空段落使用独立
     * UUID，Block 顺序保持 Tika 输出顺序。复杂版面文档应路由到 MinerU，不走本方法。
     *
     * @param content  待解析的文件字节；null 或空数组返回空文档
     * @param mimeType 调用方识别出的 MIME，仅用于日志和 metadata
     * @param options  可选解析上下文；读取 {@code sourceFile}
     * @return 仅包含 ParagraphBlock 的结构化文档
     */
    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }

        String text;
        try (ByteArrayInputStream is = new ByteArrayInputStream(content)) {
            text = TIKA.parseToString(is);
            text = TextCleanupUtil.cleanup(text);
        } catch (Exception e) {
            log.error("Tika 结构化解析失败，MIME 类型: {}", mimeType, e);
            throw new ServiceException("文档解析失败: " + e.getMessage());
        }

        // Provenance 复用于本次解析产生的所有段落，表示它们来自同一个源文件。
        Provenance prov = Provenance.ofFile(extractSourceFile(options));
        List<Block> blocks = new ArrayList<>();
        for (String segment : text.split("\\n{2,}")) {
            String trimmed = segment.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            blocks.add(new ParagraphBlock(UUID.randomUUID().toString(), prov, List.of(), trimmed));
        }
        return ParsedDocument.of(blocks, Map.of("parser", getParserType(), "mimeType", mimeType == null ? "" : mimeType));
    }

    /**
     * 从弱类型 options 中读取源文件标识。
     *
     * @return 未提供时返回空串，保证 Provenance 字段可安全序列化
     */
    private String extractSourceFile(Map<String, Object> options) {
        if (options == null) {
            return "";
        }
        Object v = options.get("sourceFile");
        return v == null ? "" : v.toString();
    }

    @Override
    public boolean supports(String mimeType) {
        // 支持边界收紧：Tika 只用于 text/* 基础格式
        // PDF/Word/PPT → MinerU, Excel → POI, Markdown → Markdown
        // image / octet-stream / 未知 MIME → 返回 false 让 ParserNode 显式报错
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("text/markdown") || lower.startsWith("text/x-markdown")) {
            return false;
        }
        // CSV 交给 CsvDocumentParser 产 key-val 表格，不走 Tika 平文本
        if (lower.equals("text/csv") || lower.equals("application/csv")
                || lower.equals("text/comma-separated-values")) {
            return false;
        }
        // 仅接受 text/* 与少数本质上仍是文本的 application/* 类型。
        if (lower.startsWith("text/")) {
            return true;
        }
        return lower.equals("application/json")
                || lower.equals("application/xml")
                || lower.equals("application/xhtml+xml")
                || lower.equals("application/rtf");
    }
}

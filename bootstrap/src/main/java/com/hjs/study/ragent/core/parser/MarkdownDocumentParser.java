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
import com.hjs.study.ragent.core.parser.model.Block;
import com.hjs.study.ragent.core.parser.model.ListBlock;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Markdown 文档解析器。
 * <p>
 * 使用 commonmark-java 把 UTF-8 文本解析为 AST，再把顶层块节点转换为项目自己的 Block IR：
 * <ul>
 *   <li>{@code # 标题} → {@link com.hjs.study.ragent.core.parser.model.HeadingBlock}</li>
 *   <li>普通段落 → {@link ParagraphBlock}</li>
 *   <li>{@code ```...```} → {@link CodeBlock}</li>
 *   <li>{@code - / 1.} 列表 → {@link ListBlock}</li>
 *   <li>GFM 表格 → 自定义 TableBlock</li>
 * </ul>
 * <p>
 * inline 语法会做有意简化：普通链接保留 Markdown 目标，强调标记只保留文字；图片没有资产
 * 上传上下文，因此只会通过递归留下 alt 文本，不生成 ImageBlock。需要图片资产的复杂文档由
 * MinerU 或独立图片解析器处理。
 * <p>
 * 该解析器也声明支持 {@code text/plain}，且优先级高于 Tika，因此纯文本会被当作“只有段落
 * 语法的 Markdown”处理。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MarkdownDocumentParser implements DocumentParser {

    /**
     * CommonMark Parser 构建成本较高且解析过程不保存文档状态，因此作为静态实例复用。
     * GFM Tables 扩展使管道表格进入 CustomBlock 分支。
     */
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    @Override
    public String getParserType() {
        return ParserType.MARKDOWN.getType();
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParsedDocument.of(List.of());
        }

        // Markdown 契约固定按 UTF-8 解码，不进行字符集自动探测。
        String text = new String(content, StandardCharsets.UTF_8);
        Provenance prov = Provenance.ofFile(extractSourceFile(options));

        // Visitor 只产生项目 IR，不在这里执行分块或 Markdown 再渲染。
        Document doc = (Document) PARSER.parse(text);
        BlockExtractingVisitor visitor = new BlockExtractingVisitor(prov);
        doc.accept(visitor);

        return ParsedDocument.of(visitor.getBlocks(), Map.of(
                "parser", getParserType(),
                "mimeType", mimeType == null ? "" : mimeType,
                "blocks", visitor.getBlocks().size()
        ));
    }

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && (
                mimeType.equals("text/markdown") ||
                        mimeType.equals("text/x-markdown") ||
                        mimeType.equals("text/plain")
        );
    }

    /**
     * 从 options 读取源文件标识，供所有 Block 共享同一 Provenance。
     */
    private static String extractSourceFile(Map<String, Object> options) {
        if (options == null) {
            return "";
        }
        Object v = options.get("sourceFile");
        return v == null ? "" : v.toString();
    }

    // ===================== AST Visitor =====================

    /**
     * AST 访问器：把 CommonMark 节点转换为 Ragent Block 列表。
     * <p>
     * 标题、段落、代码和列表的 visit 方法有意不调用 {@code super.visit(...)}，表示当前节点
     * 已被整体消费，避免其子节点再次生成重复 Block。列表项内部的段落被聚合进 ListBlock，
     * 不单独生成 ParagraphBlock。
     */
    private static final class BlockExtractingVisitor extends AbstractVisitor {

        private final Provenance provenance;
        private final List<Block> blocks = new ArrayList<>();

        /**
         * @param provenance 本文档所有 Block 共享的来源信息
         */
        BlockExtractingVisitor(Provenance provenance) {
            this.provenance = provenance;
        }

        List<Block> getBlocks() {
            return blocks;
        }

        @Override
        public void visit(Heading heading) {
            // outlinePath 此时为空；章节路径由下游 HeadingHandler 按 Block 顺序累积。
            blocks.add(new HeadingBlock(
                    UUID.randomUUID().toString(),
                    provenance,
                    Collections.emptyList(),
                    heading.getLevel(),
                    extractInlineText(heading)
            ));
            // 不向下递归(标题内的 inline 已合并)
        }

        @Override
        public void visit(Paragraph paragraph) {
            // 段落可能是顶层段落，也可能是列表项内的；后者由 buildListBlock 统一消费。
            if (paragraph.getParent() instanceof ListItem) {
                return;
            }
            String text = extractInlineText(paragraph);
            if (!text.isEmpty()) {
                blocks.add(new ParagraphBlock(
                        UUID.randomUUID().toString(),
                        provenance,
                        Collections.emptyList(),
                        text
                ));
            }
        }

        @Override
        public void visit(FencedCodeBlock codeBlock) {
            blocks.add(new CodeBlock(
                    UUID.randomUUID().toString(),
                    provenance,
                    Collections.emptyList(),
                    codeBlock.getInfo(),
                    stripTrailingNewline(codeBlock.getLiteral())
            ));
        }

        @Override
        public void visit(IndentedCodeBlock codeBlock) {
            blocks.add(new CodeBlock(
                    UUID.randomUUID().toString(),
                    provenance,
                    Collections.emptyList(),
                    null,
                    stripTrailingNewline(codeBlock.getLiteral())
            ));
        }

        @Override
        public void visit(BulletList bulletList) {
            blocks.add(buildListBlock(bulletList, false));
            // 不向下递归
        }

        @Override
        public void visit(OrderedList orderedList) {
            blocks.add(buildListBlock(orderedList, true));
            // 不向下递归
        }

        @Override
        public void visit(org.commonmark.node.CustomBlock customBlock) {
            // GFM TableBlock 是 CustomBlock 子类；其他扩展节点继续交给默认 Visitor 遍历。
            if (customBlock instanceof TableBlock tableBlock) {
                handleTable(tableBlock);
                return;
            }
            super.visit(customBlock);
        }

        /**
         * 把一个列表节点整体折叠为 ListBlock。
         * <p>
         * 每个直接 ListItem 对应一个字符串；嵌套结构通过 inline 文本递归拍平，不保留子列表层级。
         */
        private ListBlock buildListBlock(Node listNode, boolean ordered) {
            List<String> items = new ArrayList<>();
            Node child = listNode.getFirstChild();
            while (child != null) {
                if (child instanceof ListItem) {
                    items.add(extractInlineText(child).trim());
                }
                child = child.getNext();
            }
            return new ListBlock(
                    UUID.randomUUID().toString(),
                    provenance,
                    Collections.emptyList(),
                    ordered,
                    items
            );
        }

        /**
         * 把 GFM 表头和表体分别提取为 headers/rows。
         * <p>
         * 对齐方式等展示属性不进入 IR；下游只关心单元格文本和二维位置。
         */
        private void handleTable(TableBlock tableBlock) {
            List<String> headers = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();

            Node child = tableBlock.getFirstChild();
            while (child != null) {
                if (child instanceof TableHead head) {
                    Node hr = head.getFirstChild();
                    if (hr instanceof TableRow tr) {
                        headers.addAll(extractCellTexts(tr));
                    }
                } else if (child instanceof TableBody body) {
                    Node tr = body.getFirstChild();
                    while (tr != null) {
                        if (tr instanceof TableRow row) {
                            rows.add(extractCellTexts(row));
                        }
                        tr = tr.getNext();
                    }
                }
                child = child.getNext();
            }

            blocks.add(new com.hjs.study.ragent.core.parser.model.TableBlock(
                    UUID.randomUUID().toString(),
                    provenance,
                    Collections.emptyList(),
                    headers,
                    rows,
                    null
            ));
        }

        /**
         * 按原顺序提取一行内的所有 TableCell 文本。
         */
        private List<String> extractCellTexts(TableRow row) {
            List<String> cells = new ArrayList<>();
            Node cell = row.getFirstChild();
            while (cell != null) {
                if (cell instanceof TableCell tc) {
                    cells.add(extractInlineText(tc).trim());
                }
                cell = cell.getNext();
            }
            return cells;
        }
    }

    /**
     * 提取节点内所有 inline 文本（Text、Code、Link、Emphasis 等）。
     * <p>
     * 链接保留为 {@code [text](url)}；强调只保留内部文本；软/硬换行统一为 LF。其他拥有子节点的
     * inline 类型递归展开。当前没有 Image 专用分支，因此 Markdown 图片会退化为其 alt 文本。
     */
    private static String extractInlineText(Node parent) {
        StringBuilder sb = new StringBuilder();
        Node child = parent.getFirstChild();
        while (child != null) {
            appendInline(sb, child);
            child = child.getNext();
        }
        return sb.toString();
    }

    /**
     * 深度优先追加单个 inline 节点；方法通过类型模式匹配决定保留哪些 Markdown 语义。
     */
    private static void appendInline(StringBuilder sb, Node node) {
        if (node instanceof Text t) {
            sb.append(t.getLiteral());
        } else if (node instanceof Code code) {
            sb.append('`').append(code.getLiteral()).append('`');
        } else if (node instanceof Link link) {
            String inner = extractInlineText(link);
            String dest = link.getDestination();
            sb.append('[').append(inner).append("](").append(dest).append(')');
        } else if (node instanceof Emphasis || node instanceof StrongEmphasis) {
            // 保留 inline 文本但不保留 markdown 标记（简化）
            sb.append(extractInlineText(node));
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            sb.append('\n');
        } else if (node.getFirstChild() != null) {
            Node child = node.getFirstChild();
            while (child != null) {
                appendInline(sb, child);
                child = child.getNext();
            }
        }
    }

    /**
     * CommonMark 代码块 literal 通常自带一个结束 LF；只剥离一个，保留代码内部空行。
     */
    private static String stripTrailingNewline(String s) {
        if (s == null) {
            return "";
        }
        if (s.endsWith("\n")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}

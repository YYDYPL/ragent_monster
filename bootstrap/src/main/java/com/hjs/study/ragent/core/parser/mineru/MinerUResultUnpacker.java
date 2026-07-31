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

package com.hjs.study.ragent.core.parser.mineru;

import com.hjs.study.ragent.core.parser.model.*;
import com.hjs.study.ragent.core.parser.model.Block;
import com.hjs.study.ragent.core.parser.model.ListBlock;
import com.hjs.study.ragent.framework.exception.ServiceException;
import com.hjs.study.ragent.rag.dto.StoredFileDTO;
import com.hjs.study.ragent.rag.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU 结果解包器：ZIP 字节流 → ParsedDocument。
 * <p>
 * 流程:
 * <ol>
 *   <li>解 zip 收集所有 entry(markdown + 图片)</li>
 *   <li>对每个图片字节:上传到 RustFS asset-bucket 拿到 URL</li>
 *   <li>记录 {zip 内路径 → RustFS URL} 映射</li>
 *   <li>commonmark 解析 markdown AST,遍历:
 *     <ul>
 *       <li>"段首图片" → 提升为 {@link ImageBlock},asset 关联 RustFS URL</li>
 *       <li>其他 block → 转 ragent Block</li>
 *     </ul>
 *   </li>
 * </ol>
 * <p>
 * ZIP 内容不会落到本地文件系统，因此不存在传统 Zip Slip 的路径写入问题；但 Markdown 与图片
 * 会完整读入内存，调用方仍需通过上传大小和 HTTP 响应限制控制压缩包规模。图片先全部上传，
 * 再解析 Markdown；中途失败时已上传资产不会在本类内回滚。
 */
@Slf4j
@Component
public class MinerUResultUnpacker {

    /**
     * 共享的 CommonMark Parser，启用 GFM 表格扩展。单次 AST 状态保存在 Document/Visitor 中。
     */
    private static final Parser MARKDOWN_PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    /** 用于把 ZIP 内图片迁移到应用自己的长期资产存储。 */
    private final FileStorageService fileStorageService;

    /**
     * @param fileStorageService 图片资产存储服务
     */
    public MinerUResultUnpacker(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * 解包 MinerU ZIP 并输出 ParsedDocument。
     * <p>
     * Block 顺序由 Markdown AST 决定，图片 Map 的遍历顺序不影响正文顺序。返回前，所有能识别的
     * ZIP 图片都已尝试上传，不仅限于最终 Markdown 引用到的图片。
     *
     * @param zipBytes   MinerU 返回的 zip 字节流
     * @param sourceFile 文档来源标识,写入 Provenance.sourceFile
     * @param documentId 文档 ID,用于资产 key 命名 {@code assets/{documentId}/{uuid}.{ext}}
     * @return 含 Block 列表的 ParsedDocument,ImageBlock 已携带 RustFS AssetRef
     */
    public ParsedDocument unpack(byte[] zipBytes, String sourceFile, String documentId) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new ServiceException("MinerU zip 字节为空");
        }

        ZipContents contents = readZip(zipBytes);
        if (contents.markdown == null) {
            throw new ServiceException("MinerU zip 中未找到 markdown 文件");
        }

        // 上传所有图片到对象存储，得到 {ZIP 内路径 → 公开 URL} 映射。
        Map<String, String> imageUrlMap = uploadImages(contents.images, documentId);

        // 解析 Markdown AST，并在 Visitor 中把图片目标地址替换为公开 URL。
        Provenance prov = Provenance.ofFile(sourceFile);
        Document doc = (Document) MARKDOWN_PARSER.parse(contents.markdown);
        UnpackVisitor visitor = new UnpackVisitor(prov, imageUrlMap);
        doc.accept(visitor);

        return ParsedDocument.of(visitor.getBlocks(), Map.of(
                "parser", "MinerU",
                "imagesUploaded", imageUrlMap.size(),
                "blocks", visitor.getBlocks().size()
        ));
    }

    /**
     * 单文件 ZIP 内容快照。
     *
     * @param markdown 找到的第一个 .md 文件文本
     * @param images   ZIP entry 原始路径到图片字节的映射
     */
    private record ZipContents(String markdown, Map<String, byte[]> images) {
    }

    /**
     * 单遍扫描 ZIP entry，收集第一个 Markdown 和所有受支持图片。
     * <p>
     * 其他 JSON、字体、布局文件会被忽略。entry 名只作为 Map key 使用，不会拼接成本地路径。
     */
    private ZipContents readZip(byte[] zipBytes) {
        String markdown = null;
        Map<String, byte[]> images = new HashMap<>();

        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                byte[] data = readAll(zin);

                if (name.toLowerCase(Locale.ROOT).endsWith(".md") && markdown == null) {
                    // MinerU 包可能含辅助 Markdown；当前以遍历到的第一份为主文档。
                    markdown = new String(data, StandardCharsets.UTF_8);
                } else if (isImage(name)) {
                    images.put(name, data);
                }
            }
        } catch (IOException e) {
            throw new ServiceException("MinerU zip 解压失败: " + e.getMessage());
        }
        return new ZipContents(markdown, images);
    }

    /**
     * 读取当前 ZIP entry 到内存；返回后由外层继续调用 getNextEntry。
     */
    private static byte[] readAll(ZipInputStream zin) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zin.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 依据 entry 扩展名判断是否为可上传图片。
     */
    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    /**
     * 上传所有图片到资产桶，返回 {ZIP 路径 → 公开访问 URL}。
     * <p>
     * 资产 key 使用业务 documentId 分目录、随机 UUID 防重名。任何一张图片上传失败都会终止
     * 整份文档解包，防止产生部分链接仍指向 ZIP 内临时路径的混合结果。
     */
    private Map<String, String> uploadImages(Map<String, byte[]> images, String documentId) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, byte[]> e : images.entrySet()) {
            String zipPath = e.getKey();
            byte[] data = e.getValue();
            String ext = extractExt(zipPath);
            String filename = "assets/" + documentId + "/" + UUID.randomUUID() + "." + ext;
            String mime = inferMime(ext);
            try {
                StoredFileDTO stored = fileStorageService.uploadAsset(data, filename, mime);
                // 转为浏览器可直连的公开 URL(资产桶已开公共读)，供 markdown 图片链接固化入库
                String publicUrl = fileStorageService.getPublicUrl(stored.getUrl());
                result.put(zipPath, publicUrl);
            } catch (Exception ex) {
                log.error("MinerU 图片上传失败 zipPath={}", zipPath, ex);
                throw new ServiceException("MinerU 图片上传失败 " + zipPath + ": " + ex.getMessage());
            }
        }
        return result;
    }

    /**
     * 从 ZIP entry 名提取小写扩展名；无扩展名时返回 bin。
     */
    private static String extractExt(String path) {
        int idx = path.lastIndexOf('.');
        return idx >= 0 ? path.substring(idx + 1).toLowerCase(Locale.ROOT) : "bin";
    }

    /**
     * 把图片扩展名映射为上传所需 MIME。
     */
    private static String inferMime(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }

    // ===================== AST Visitor =====================

    /**
     * 遍历 Markdown AST 输出 Block。
     * <p>
     * 与 MarkdownDocumentParser 的 Visitor 类似,但额外:
     * <ul>
     *   <li>剥离“段首 Image”，提升为 {@link ImageBlock} + AssetRef，剩余内容另起 ParagraphBlock；</li>
     *   <li>行内 Image 保留在 ParagraphBlock 中，链接替换为资产 URL。</li>
     * </ul>
     * <p>
     * 与独立图片解析器不同，MinerU 图片当前没有 VLM description，因此主要依赖相邻正文、标题
     * 和 caption 被召回。
     */
    private static final class UnpackVisitor extends AbstractVisitor {

        /** 当前文档所有 Block 共享的来源。 */
        private final Provenance provenance;

        /** ZIP 图片路径到应用资产 URL 的映射。 */
        private final Map<String, String> imageUrlMap;

        /** 按 Markdown 阅读顺序累积的输出。 */
        private final List<Block> blocks = new ArrayList<>();

        /**
         * @param provenance 文档来源
         * @param imageUrlMap 已完成上传的图片地址映射
         */
        UnpackVisitor(Provenance provenance, Map<String, String> imageUrlMap) {
            this.provenance = provenance;
            this.imageUrlMap = imageUrlMap;
        }

        List<Block> getBlocks() {
            return blocks;
        }

        @Override
        public void visit(Heading heading) {
            // outlinePath 留空，由下游 HeadingHandler 结合标题序列生成。
            blocks.add(new HeadingBlock(
                    UUID.randomUUID().toString(),
                    provenance,
                    List.of(),
                    heading.getLevel(),
                    extractInlineText(heading)
            ));
        }

        @Override
        public void visit(Paragraph paragraph) {
            // ListItem 内段落由列表整体消费，避免同时产生 ParagraphBlock 和 ListBlock。
            if (paragraph.getParent() instanceof ListItem) {
                return;
            }

            // 连续扫描段首的“图片或空白”；图片提升为独立 Block，遇到首个正文节点停止。
            Node rest = paragraph.getFirstChild();
            while (rest != null) {
                if (rest instanceof Image img) {
                    handleStandaloneImage(img);
                } else if (!isBlank(rest)) {
                    break;
                }
                rest = rest.getNext();
            }

            String text = extractInlineTextFrom(rest);
            if (!text.isEmpty()) {
                blocks.add(new ParagraphBlock(
                        UUID.randomUUID().toString(),
                        provenance,
                        List.of(),
                        text
                ));
            }
        }

        @Override
        public void visit(FencedCodeBlock codeBlock) {
            blocks.add(new CodeBlock(
                    UUID.randomUUID().toString(),
                    provenance,
                    List.of(),
                    codeBlock.getInfo(),
                    stripTrailingNewline(codeBlock.getLiteral())
            ));
        }

        @Override
        public void visit(IndentedCodeBlock codeBlock) {
            blocks.add(new CodeBlock(
                    UUID.randomUUID().toString(),
                    provenance,
                    List.of(),
                    null,
                    stripTrailingNewline(codeBlock.getLiteral())
            ));
        }

        @Override
        public void visit(BulletList bulletList) {
            blocks.add(buildListBlock(bulletList, false));
        }

        @Override
        public void visit(OrderedList orderedList) {
            blocks.add(buildListBlock(orderedList, true));
        }

        @Override
        public void visit(CustomBlock customBlock) {
            if (customBlock instanceof TableBlock tableBlock) {
                handleTable(tableBlock);
                return;
            }
            super.visit(customBlock);
        }

        /**
         * 处理 HTML 块：MinerU 的表格等可能以原始 HTML（如 {@code <table>}）嵌在 Markdown 中。
         * CommonMark 将其解析为 HtmlBlock；这里作为 ParagraphBlock 原样保留，避免结构内容丢失。
         * 本方法不清洗 HTML，后续展示端仍必须使用安全的 HTML 渲染策略。
         */
        @Override
        public void visit(HtmlBlock htmlBlock) {
            String html = htmlBlock.getLiteral() == null ? "" : htmlBlock.getLiteral().strip();
            if (html.isEmpty()) {
                return;
            }
            blocks.add(new ParagraphBlock(
                    UUID.randomUUID().toString(),
                    provenance,
                    List.of(),
                    html
            ));
        }

        /**
         * 判断节点是否为可跳过的空白(换行或纯空白文本)
         */
        private static boolean isBlank(Node node) {
            return node instanceof SoftLineBreak
                    || node instanceof HardLineBreak
                    || (node instanceof Text t && t.getLiteral().trim().isEmpty());
        }

        /**
         * 把段首图片提升为独立 ImageBlock。
         * <p>
         * AssetRef.sourceBlockId 与新 Block ID 保持一致，便于下游来源追踪；caption 和 altText 都
         * 取 Markdown 图片的 alt 子节点文本。
         */
        private void handleStandaloneImage(Image image) {
            String rawDest = image.getDestination();
            String resolved = resolveImageUrl(rawDest);
            String caption = extractInlineText(image);
            String blockId = UUID.randomUUID().toString();

            AssetRef asset = new AssetRef(
                    resolved,
                    inferMimeFromUrl(resolved),
                    blockId
            );
            blocks.add(new ImageBlock(
                    blockId, provenance, List.of(),
                    asset, caption, caption
            ));
        }

        /**
         * 把 Markdown 中的相对图片目标解析为已上传 URL。
         * <p>
         * 匹配顺序为原路径、去掉 {@code ./} 的规范路径、仅文件名。都失败时保留原目标，避免
         * 静默删除引用，同时让后续排障能看到 MinerU 原始路径。
         */
        private String resolveImageUrl(String rawDest) {
            if (rawDest == null) {
                return "";
            }
            // 优先精确匹配
            String url = imageUrlMap.get(rawDest);
            if (url != null) {
                return url;
            }
            // 尝试模糊匹配(MinerU markdown 里可能用 ./images/xxx 或 images/xxx)
            String norm = rawDest.replaceFirst("^\\./", "");
            url = imageUrlMap.get(norm);
            if (url != null) {
                return url;
            }
            // 用文件名匹配兜底
            int idx = norm.lastIndexOf('/');
            String fileName = idx >= 0 ? norm.substring(idx + 1) : norm;
            for (Map.Entry<String, String> e : imageUrlMap.entrySet()) {
                if (e.getKey().endsWith("/" + fileName) || e.getKey().equals(fileName)) {
                    return e.getValue();
                }
            }
            return rawDest;
        }

        /**
         * 根据最终 URL 后缀推断图片 MIME；带查询参数或未知扩展名时会回退二进制类型。
         */
        private static String inferMimeFromUrl(String url) {
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".gif")) return "image/gif";
            return "application/octet-stream";
        }

        /**
         * 把列表的每个直接 ListItem 拍平为一个字符串，不保留嵌套层级。
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
                    List.of(),
                    ordered,
                    items
            );
        }

        /**
         * 把 GFM 表格转换为项目 TableBlock；HTML 表格走 {@link #visit(HtmlBlock)}。
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
                    List.of(),
                    headers,
                    rows,
                    null
            ));
        }

        /**
         * 按顺序提取 GFM TableRow 的单元格文本。
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

        /**
         * 提取指定父节点的全部 inline 子节点文本。
         */
        private String extractInlineText(Node parent) {
            return extractInlineTextFrom(parent.getFirstChild());
        }

        /**
         * 从指定兄弟节点起拼接 inline 文本，供段首剥离图片后渲染剩余内容。
         */
        private String extractInlineTextFrom(Node start) {
            StringBuilder sb = new StringBuilder();
            Node node = start;
            while (node != null) {
                appendInline(sb, node);
                node = node.getNext();
            }
            return sb.toString();
        }

        /**
         * 递归渲染一个 inline 节点。
         * <p>
         * 链接和图片保留 Markdown 语法，强调标记只保留内容，软/硬换行统一输出 LF。
         */
        private void appendInline(StringBuilder sb, Node node) {
            if (node instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (node instanceof Code code) {
                sb.append('`').append(code.getLiteral()).append('`');
            } else if (node instanceof Link link) {
                String inner = extractInlineText(link);
                sb.append('[').append(inner).append("](").append(link.getDestination()).append(')');
            } else if (node instanceof Image img) {
                // inline 图片(非 standalone)保留 [alt](rustfsUrl) 形式
                String alt = extractInlineText(img);
                String resolved = resolveImageUrl(img.getDestination());
                sb.append("![").append(alt).append("](").append(resolved).append(')');
            } else if (node instanceof Emphasis || node instanceof StrongEmphasis) {
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
         * 只移除代码块 literal 自带的一个末尾 LF，不改动内部格式。
         */
        private static String stripTrailingNewline(String s) {
            if (s == null) {
                return "";
            }
            return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        }
    }
}

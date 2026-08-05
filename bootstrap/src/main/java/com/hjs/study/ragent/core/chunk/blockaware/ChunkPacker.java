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
import com.hjs.study.ragent.core.parser.model.AssetRef;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * block-aware 第一阶段后的贪心 Chunk 打包器。
 * <p>
 * 各专用 Chunker 只负责单个 Block 内的“转换/拆分”，短段落和短列表天然各自成块。如果直接索引，
 * 字符预算只成为上限而不是目标，会产生大量缺少上下文的小碎片。本类在保持顺序的前提下合并
 * 相邻可流动块，使结果接近 maxChars。
 * <p>
 * PARAGRAPH、LIST、IMAGE 是可流动块；TABLE、CODE 以及自身长度不小于预算的 Chunk 是原子边界。
 * 预算按 content 字符数计算，不按 embeddingText 或模型 Token 计算。原子块可能天然超过预算，本类
 * 会原样保留。
 * <p>
 * 正常调用时打包发生在 Embedding 之前。若传入已经带向量或 metadata 的 Chunk，多块合并产生的
 * 新对象不会继承 embedding/metadata；调用顺序不应颠倒。
 */
@Component
public class ChunkPacker {

    /**
     * 可合并类型集合。使用字符串是因为 VectorChunk 是跨索引边界 DTO，而不是密封类型层次。
     */
    private static final Set<String> MERGEABLE_TYPES = Set.of("PARAGRAPH", "LIST", "IMAGE");
    /**
     * 合并时使用双换行保留段落、列表和图片之间的 Markdown 边界。
     */
    private static final String SEPARATOR = "\n\n";

    /**
     * 贪心打包相邻 Chunk，并在可流动块边界建立完整块级重叠。
     * <p>
     * 断块时从上一 buffer 尾部选择若干完整 Chunk 作为下一组起点，不截取半个段落。重叠和分隔符
     * 都计入 maxChars；若当前 Chunk 已经太大，没有剩余预算则不携带重叠。原子块会清空合并链，
     * 两侧不互相重叠。
     *
     * @param chunks       dispatch 产出的有序 chunk
     * @param maxChars     单块体量预算(合并上限)
     * @param overlapChars 块级重叠预算(尾部完整块的累计字符上限, 0 表示不重叠)
     * @return 打包后的有序 Chunk；通常 index 从 0 重排，输入不足两个时原样返回
     */
    public List<VectorChunk> pack(List<VectorChunk> chunks, int maxChars, int overlapChars) {
        if (chunks == null || chunks.size() <= 1) {
            return chunks == null ? List.of() : chunks;
        }

        List<VectorChunk> result = new ArrayList<>();
        List<VectorChunk> buffer = new ArrayList<>();
        int bufferLen = 0;

        for (VectorChunk c : chunks) {
            // 原子块先冲刷前方文本，再原样落盘；它同时截断重叠传播。
            if (!isMergeable(c, maxChars)) {
                flush(buffer, result);
                buffer.clear();
                bufferLen = 0;
                result.add(c);
                continue;
            }

            int addLen = contentLength(c);
            int sepLen = buffer.isEmpty() ? 0 : SEPARATOR.length();
            // 为当前块预留正文和一个分隔符空间后，剩余预算才可用于上一组尾部重叠。
            if (!buffer.isEmpty() && bufferLen + sepLen + addLen > maxChars) {
                flush(buffer, result);
                // 重叠预算需同时扣除当前块与其前面的分隔符长度，否则合并结果可能超出 maxChars
                buffer = overlapTail(buffer, Math.min(overlapChars, maxChars - addLen - SEPARATOR.length()));
                bufferLen = bufferedLength(buffer);
            }
            bufferLen += (buffer.isEmpty() ? 0 : SEPARATOR.length()) + addLen;
            buffer.add(c);
        }
        flush(buffer, result);

        for (int i = 0; i < result.size(); i++) {
            result.get(i).setIndex(i);
        }
        return result;
    }

    /**
     * 从缓冲区尾部选择预算内的连续完整 Chunk，并恢复原顺序。
     * <p>
     * 若最末 Chunk 已超过 overlap 预算，直接停止，不跳过它去选择更早内容，因为那会破坏“尾部上下文”
     * 语义。
     *
     * @return 可变新列表(可能为空); 元素为原 chunk 引用(内容在下一块中被复现)
     */
    private static List<VectorChunk> overlapTail(List<VectorChunk> buffer, int budget) {
        List<VectorChunk> carry = new ArrayList<>();
        if (budget <= 0) {
            return carry;
        }
        int len = 0;
        for (int i = buffer.size() - 1; i >= 0; i--) {
            int sep = carry.isEmpty() ? 0 : SEPARATOR.length();
            int next = len + sep + contentLength(buffer.get(i));
            if (next > budget) {
                break;
            }
            carry.add(0, buffer.get(i));
            len = next;
        }
        return carry;
    }

    /**
     * 计算缓冲区按最终分隔符拼接后的 content 长度。
     */
    private static int bufferedLength(List<VectorChunk> buffer) {
        int len = 0;
        for (int i = 0; i < buffer.size(); i++) {
            len += (i == 0 ? 0 : SEPARATOR.length()) + contentLength(buffer.get(i));
        }
        return len;
    }

    /**
     * 判断 Chunk 是否能进入流动缓冲区。
     * <p>
     * 图片无论是否有 VLM 描述都可与邻近文字合并，使检索命中说明文字时同时携带 AssetRef。
     */
    private static boolean isMergeable(VectorChunk c, int maxChars) {
        return MERGEABLE_TYPES.contains(c.getBlockType()) && contentLength(c) < maxChars;
    }

    /**
     * 将当前缓冲区物化到结果：空则跳过，单元素复用原对象，多元素创建合并对象。
     */
    private static void flush(List<VectorChunk> buffer, List<VectorChunk> result) {
        if (buffer.isEmpty()) {
            return;
        }
        if (buffer.size() == 1) {
            result.add(buffer.get(0));
            return;
        }
        result.add(merge(buffer));
    }

    /**
     * 合并多块并传播结构化字段。
     * <p>
     * content 按双换行拼接；outlinePath 取最长公共前缀；sourceBlockIds 保序去重；assets 按成员
     * 顺序连接（当前不去重）；blockType 同质时保留，异质时归为 PARAGRAPH。sectionContext 只取
     * 第一个非空值。新对象不继承成员 metadata、embedding 和原 chunkId。
     */
    private static VectorChunk merge(List<VectorChunk> buffer) {
        StringBuilder content = new StringBuilder();
        StringBuilder embeddingText = new StringBuilder();
        boolean hasExplicitEmbeddingText = false;
        String sectionContext = null;
        Set<String> sourceBlockIds = new LinkedHashSet<>();
        List<AssetRef> assets = new ArrayList<>();
        String blockType = buffer.get(0).getBlockType();
        boolean homogeneous = true;
        for (VectorChunk c : buffer) {
            if (!content.isEmpty()) {
                content.append(SEPARATOR);
            }
            content.append(c.getContent() == null ? "" : c.getContent());
            // 只要任一成员显式优化过向量文本，合并结果就逐块使用“显式值优先、否则 content”。
            String effectiveEmbedding = c.getEmbeddingText() != null && !c.getEmbeddingText().isBlank()
                    ? c.getEmbeddingText()
                    : c.getContent();
            if (c.getEmbeddingText() != null && !c.getEmbeddingText().isBlank()) {
                hasExplicitEmbeddingText = true;
            }
            if (effectiveEmbedding != null && !effectiveEmbedding.isBlank()) {
                if (!embeddingText.isEmpty()) {
                    embeddingText.append(SEPARATOR);
                }
                embeddingText.append(effectiveEmbedding);
            }
            // sectionContext 不是集合；当前采用“首个非空值”策略，避免机械拼出矛盾上下文。
            if (sectionContext == null && c.getSectionContext() != null && !c.getSectionContext().isBlank()) {
                sectionContext = c.getSectionContext();
            }
            if (c.getSourceBlockIds() != null) {
                sourceBlockIds.addAll(c.getSourceBlockIds());
            }
            if (c.getAssets() != null) {
                assets.addAll(c.getAssets());
            }
            if (!java.util.Objects.equals(blockType, c.getBlockType())) {
                homogeneous = false;
            }
        }
        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .content(content.toString())
                .embeddingText(hasExplicitEmbeddingText ? embeddingText.toString() : null)
                .sectionContext(sectionContext)
                .blockType(homogeneous ? blockType : "PARAGRAPH")
                .outlinePath(commonPrefix(buffer))
                .sourceBlockIds(new ArrayList<>(sourceBlockIds))
                .assets(assets)
                .build();
    }

    /**
     * 计算所有成员章节路径的最长公共前缀。
     * <p>
     * 若合并横跨兄弟小节，结果退回共同父章节；完全无共同路径时返回空列表。
     */
    private static List<String> commonPrefix(List<VectorChunk> buffer) {
        List<String> prefix = new ArrayList<>(safePath(buffer.get(0)));
        for (int i = 1; i < buffer.size() && !prefix.isEmpty(); i++) {
            List<String> path = safePath(buffer.get(i));
            int keep = 0;
            while (keep < prefix.size() && keep < path.size()
                    && prefix.get(keep).equals(path.get(keep))) {
                keep++;
            }
            prefix.subList(keep, prefix.size()).clear();
        }
        return prefix;
    }

    /** 把旧数据中的 null outlinePath 规范化为空列表。 */
    private static List<String> safePath(VectorChunk c) {
        return c.getOutlinePath() == null ? List.of() : c.getOutlinePath();
    }

    /** 预算只统计非空 content 的 Java 字符长度。 */
    private static int contentLength(VectorChunk c) {
        return StringUtils.hasText(c.getContent()) ? c.getContent().length() : 0;
    }
}

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

package com.hjs.study.ragent.core.chunk;

import cn.hutool.core.util.IdUtil;
import com.hjs.study.ragent.core.chunk.blockaware.BlockAwareChunkerDispatcher;
import com.hjs.study.ragent.core.chunk.blockaware.BlockChunkConfig;
import com.hjs.study.ragent.core.parser.BlockTextRenderer;
import com.hjs.study.ragent.core.parser.model.Block;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 结构化与纯文本分块的统一决策入口。
 * <p>
 * 路径优先级为“整篇模式 → block-aware → legacy text”。把判断收口在这里，可以保证 Pipeline
 * 与简单分块模式使用同一语义，避免表格等结构在某条入口被提前拍平成普通字符串。
 * <p>
 * 本服务只生成未嵌入的 {@link VectorChunk}，Embedding 由 {@link ChunkEmbeddingService} 完成，
 * 持久化由知识服务和索引装饰器完成。
 * <p>
 * 封装“<b>blocks 非空 → block-aware 分发；否则 → 纯文本 legacy 策略</b>”的唯一判断，
 * 供两条分块入口共用：
 * <ul>
 *   <li>{@code ingestion} 流水线的 ChunkerNode（Pipeline 模式）</li>
 *   <li>{@code knowledge} 文档的 KnowledgeDocumentServiceImpl（简单分块模式）</li>
 * </ul>
 * 两处曾各写各的，导致简单分块模式漏接 block-aware（表格被拍平成文本后随意切碎）；
 * 收口到此服务后形成分块路径的单一真相源。
 */
@Service
@RequiredArgsConstructor
public class StructuredChunkingService {

    /** 强类型 Block 分发、专用切分和最终打包链。 */
    private final BlockAwareChunkerDispatcher blockAwareChunkerDispatcher;

    /** 仅供没有 Block 时使用的 legacy 纯文本策略注册表。 */
    private final ChunkingStrategyFactory chunkingStrategyFactory;

    /**
     * 不分块哨兵：chunkSize 或 targetChars 取该值时整篇文档合成单个 Chunk。
     */
    public static final int WHOLE_DOCUMENT_SENTINEL = -1;
    /**
     * 从 legacy 配置无法取得正数体量键时，block-aware 使用的默认字符预算。
     */
    private static final int DEFAULT_MAX_CHARS = 512;
    /**
     * 从 legacy 配置无法取得非负重叠键时，block-aware 使用的默认重叠预算。
     */
    private static final int DEFAULT_OVERLAP = 64;
    /**
     * 表格每 Chunk 最大数据行数；这是硬上限，实际分组还受 key-value 字符预算约束。
     */
    private static final int DEFAULT_ROWS_PER_CHUNK = 50;
    /**
     * 列表保持原子结构的默认最大项目数。
     */
    private static final int DEFAULT_MAX_LIST_ITEMS = 15;
    /**
     * 超过原子阈值后，每个列表 Chunk 的默认项目数。
     */
    private static final int DEFAULT_LIST_ITEMS_PER_CHUNK = 10;

    /**
     * 根据输入结构与配置选择唯一分块路径。
     * <p>
     * 整篇模式优先于 blocks 判断；因此即使已有 TableBlock/ImageBlock，也会被统一渲染到一个
     * DOCUMENT Chunk，丢失专用 embeddingText 和 assets 等分块级结构信息，这是用户明确选择
     * “不分块”后的设计结果。
     *
     * @param blocks       解析产出的结构化 Block，可空
     * @param fallbackText blocks 为空时的纯文本兜底
     * @param mode         legacy 文本策略类型，仅 blocks 为空且非整篇模式时使用
     * @param options      legacy 文本策略参数，同时用于派生 block-aware 体量预算
     * @param rowsPerChunk block-aware 表格行上限，可空取默认
     * @return VectorChunk 列表（未嵌入）；blocks 与 fallbackText 都空时返回空列表
     */
    public List<VectorChunk> chunk(List<Block> blocks, String fallbackText,
                                   ChunkingMode mode, ChunkingOptions options, Integer rowsPerChunk) {
        // 不分块（chunkSize=-1）：整篇合成单个 chunk，优先于 block-aware / legacy 切分
        if (isWholeDocument(options)) {
            return wholeDocumentChunk(blocks, fallbackText);
        }
        if (blocks != null && !blocks.isEmpty()) {
            return blockAwareChunkerDispatcher.dispatch(blocks, toBlockChunkConfig(options, rowsPerChunk));
        }
        if (!StringUtils.hasText(fallbackText)) {
            return List.of();
        }
        return chunkingStrategyFactory.requireStrategy(mode).chunk(fallbackText, options);
    }

    /**
     * 判断是否为整篇模式。
     * <p>
     * 通过 toConfigMap 读取不同 record 的稳定外部键；其他负数不会被当作哨兵。
     */
    private static boolean isWholeDocument(ChunkingOptions options) {
        if (options == null) {
            return false;
        }
        Map<String, Integer> cfg = options.toConfigMap();
        for (String key : new String[]{"chunkSize", "targetChars"}) {
            Integer v = cfg.get(key);
            if (v != null && v == WHOLE_DOCUMENT_SENTINEL) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把整篇文档物化为单个 DOCUMENT Chunk。
     * <p>
     * 优先使用 fallbackText，因为它可能是增强后的全文；缺失时才用 BlockTextRenderer。所有 Block
     * ID 都保留在 sourceBlockIds 中，但不会搬运 ImageBlock.assets。
     *
     * @return 单元素列表；全文为空白时返回空列表
     */
    private List<VectorChunk> wholeDocumentChunk(List<Block> blocks, String fallbackText) {
        String whole = StringUtils.hasText(fallbackText)
                ? fallbackText
                : (blocks != null && !blocks.isEmpty() ? BlockTextRenderer.render(blocks) : "");
        if (!StringUtils.hasText(whole)) {
            return List.of();
        }
        List<String> sourceBlockIds = blocks == null ? List.of()
                : blocks.stream().map(Block::id).filter(Objects::nonNull).toList();
        VectorChunk chunk = VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(0)
                .content(whole)
                .embeddingText(whole)
                .blockType("DOCUMENT")
                .sourceBlockIds(sourceBlockIds)
                .build();
        return List.of(chunk);
    }

    /**
     * 从 legacy ChunkingOptions 派生 block-aware 强类型配置。
     * <p>
     * size 优先级是 chunkSize → targetChars → maxChars；重叠优先级是 overlapSize →
     * overlapChars。若 overlap 不小于 maxChars，会钳制为 maxChars-1，以满足 BlockChunkConfig
     * 构造约束。rowsPerChunk 由调用方透传，非正值回退默认。
     */
    private BlockChunkConfig toBlockChunkConfig(ChunkingOptions options, Integer rowsPerChunk) {
        Map<String, Integer> cfg = options == null ? Map.of() : options.toConfigMap();
        int maxChars = firstPositive(cfg);
        int overlap = firstNonNegative(cfg);
        // 防御：overlap 必须 < maxChars，否则 BlockChunkConfig 校验会抛错
        if (overlap >= maxChars) {
            overlap = Math.max(0, maxChars - 1);
        }
        int rows = (rowsPerChunk != null && rowsPerChunk > 0) ? rowsPerChunk : DEFAULT_ROWS_PER_CHUNK;
        return new BlockChunkConfig(maxChars, overlap, rows, DEFAULT_MAX_LIST_ITEMS, DEFAULT_LIST_ITEMS_PER_CHUNK);
    }

    /**
     * 按兼容键优先级取第一个正数体量值。
     * <p>
     * 这里忽略 -1，因为整篇哨兵已在入口提前处理。
     */
    private static int firstPositive(Map<String, Integer> cfg) {
        for (String key : new String[]{"chunkSize", "targetChars", "maxChars"}) {
            Integer v = cfg.get(key);
            if (v != null && v > 0) {
                return v;
            }
        }
        return StructuredChunkingService.DEFAULT_MAX_CHARS;
    }

    /**
     * 按兼容键优先级取第一个非负重叠值；0 表示禁用重叠。
     */
    private static int firstNonNegative(Map<String, Integer> cfg) {
        for (String key : new String[]{"overlapSize", "overlapChars"}) {
            Integer v = cfg.get(key);
            if (v != null && v >= 0) {
                return v;
            }
        }
        return StructuredChunkingService.DEFAULT_OVERLAP;
    }
}

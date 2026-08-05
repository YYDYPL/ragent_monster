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
import com.hjs.study.ragent.core.parser.model.ParagraphBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ParagraphBlock 的字符窗口切分器。
 * <p>
 * 长度不超过 maxChars 的自然段直接形成一个临时 Chunk；超长段落按固定字符窗口切分，相邻窗口
 * 重叠 overlapChars。这里不尝试句号/换行边界对齐，因为 Parser 已先按自然段建立 Block。
 * <p>
 * 本类只负责“拆”，相邻短段落是否合并由 ChunkPacker 决定。章节边界由 Dispatcher 在每次调用
 * 时注入 outlinePath；一个 ParagraphBlock 不会跨标题。
 */
@Component
public class ParagraphChunker implements BlockChunker<ParagraphBlock> {

    /**
     * 切分一个自然段并复制章节与来源信息。
     *
     * @return 空段落返回空列表；否则至少一个 PARAGRAPH Chunk
     */
    @Override
    public List<VectorChunk> chunk(ParagraphBlock block, ChunkContext ctx) {
        if (block == null) {
            return List.of();
        }
        String text = block.text() == null ? "" : block.text();
        if (text.isEmpty()) {
            return List.of();
        }

        int maxChars = ctx.config().maxChars();
        int overlap = ctx.config().overlapChars();
        List<String> pieces = splitByChars(text, maxChars, overlap);

        List<VectorChunk> result = new ArrayList<>(pieces.size());
        int chunkIndex = ctx.startIndex();
        for (String piece : pieces) {
            VectorChunk chunk = VectorChunk.builder()
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .index(chunkIndex++)
                    .content(piece)
                    .blockType("PARAGRAPH")
                    .outlinePath(new ArrayList<>(ctx.outlinePath()))
                    .sourceBlockIds(List.of(block.id()))
                    .build();
            result.add(chunk);
        }
        return result;
    }

    /**
     * 按 Java 字符索引切分，相邻片段重叠 overlap 字符。
     * <ul>
     *   <li>text.length() ≤ maxChars：返回单元素列表</li>
     *   <li>否则按 {@code step=maxChars-overlap} 正向推进；</li>
     *   <li>BlockChunkConfig 已保证 step 大于 0，不会死循环。</li>
     * </ul>
     */
    private static List<String> splitByChars(String text, int maxChars, int overlap) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        int step = maxChars - overlap;
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            pieces.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
            start += step;
        }
        return pieces;
    }
}

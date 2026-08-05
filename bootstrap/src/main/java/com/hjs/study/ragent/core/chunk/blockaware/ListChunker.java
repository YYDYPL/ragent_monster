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
import com.hjs.study.ragent.core.parser.model.ListBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ListBlock 的按项目分组切分器：
 * <ul>
 *   <li>短列表（items.size() ≤ maxListItems）：atomic，整列表一个 chunk</li>
 *   <li>长列表：按 listItemsPerChunk 分组，每组一个 chunk</li>
 * </ul>
 * 渲染为标准 Markdown 列表（{@code -} 或 {@code 1.}）。阈值按项目数而不是字符数判断，因此
 * 单个超长列表项可能突破 maxChars；这是保持列表项原子性的取舍。第一阶段产出的 LIST Chunk
 * 仍属于可流动块，ChunkPacker 可以把它与相邻段落或图片继续合并。
 */
@Component
public class ListChunker implements BlockChunker<ListBlock> {

    /**
     * 保留项目顺序切分列表；长有序列表的后续分组会延续原始全局编号。
     */
    @Override
    public List<VectorChunk> chunk(ListBlock block, ChunkContext ctx) {
        if (block == null || block.items() == null || block.items().isEmpty()) {
            return List.of();
        }
        List<String> items = block.items();
        int max = ctx.config().maxListItems();

        if (items.size() <= max) {
            // startNumber=1；有序短列表从 1 开始，无序列表忽略该参数。
            return List.of(buildChunk(items, 1, block, ctx, ctx.startIndex()));
        }

        // i 是原列表的零基偏移，因此 startNumber=i+1 能维持有序列表连续编号。
        int per = ctx.config().listItemsPerChunk();
        List<VectorChunk> result = new ArrayList<>();
        int chunkIndex = ctx.startIndex();
        for (int i = 0; i < items.size(); i += per) {
            int end = Math.min(i + per, items.size());
            List<String> group = items.subList(i, end);
            result.add(buildChunk(group, i + 1, block, ctx, chunkIndex++));
        }
        return result;
    }

    /**
     * 构造一组列表项对应的临时 Chunk。
     *
     * @param startNumber 有序列表在本组中的起始显示编号；无序列表忽略
     */
    private VectorChunk buildChunk(List<String> items, int startNumber, ListBlock block,
                                   ChunkContext ctx, int chunkIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (block.ordered()) {
                sb.append(startNumber + i).append(". ");
            } else {
                sb.append("- ");
            }
            sb.append(items.get(i)).append('\n');
        }
        // 去掉末尾的换行
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }

        return VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(chunkIndex)
                .content(sb.toString())
                .blockType("LIST")
                .outlinePath(new ArrayList<>(ctx.outlinePath()))
                .sourceBlockIds(List.of(block.id()))
                .build();
    }
}

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
import com.hjs.study.ragent.core.parser.model.CodeBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CodeBlock 到原子 VectorChunk 的转换器。
 * <p>
 * 代码永不按 maxChars 切分：语法完整性优先于体量预算，因此超长代码可能产生超过预算的 Chunk。
 * content 恢复标准 Markdown 围栏，embeddingText 留空，由 ChunkEmbeddingService 使用同一正文。
 * ChunkPacker 把 CODE 视为原子边界，不与两侧段落合并，也不跨它做重叠。
 */
@Component
public class CodeChunker implements BlockChunker<CodeBlock> {

    /**
     * 将单个代码块完整渲染为一个 CODE Chunk。
     */
    @Override
    public List<VectorChunk> chunk(CodeBlock block, ChunkContext ctx) {
        if (block == null) {
            return List.of();
        }
        String language = block.language() == null ? "" : block.language();
        String code = block.code() == null ? "" : block.code();
        // CodeBlock.code 不含外层围栏；在展示文本中统一补回。
        String markdown = "```" + language + "\n" + code + "\n```";

        VectorChunk chunk = VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(ctx.startIndex())
                .content(markdown)
                .blockType("CODE")
                .outlinePath(new ArrayList<>(ctx.outlinePath()))
                .sourceBlockIds(List.of(block.id()))
                .build();

        return List.of(chunk);
    }
}

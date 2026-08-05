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
import com.hjs.study.ragent.core.parser.model.ImageBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ImageBlock 到原子 VectorChunk 的多模态转换器。
 * <p>
 * 展示正文包含 Markdown 图片链接；若 Parser 已通过 VLM 生成 description，则把描述放在链接前，
 * 并单独作为 embeddingText，防止长 URL 和 Markdown 符号污染向量。AssetRef 还会结构化挂载到
 * VectorChunk.assets，供索引和展示链路直接读取。
 * <p>
 * IMAGE 对专用 Chunker 是原子块，不会在本类内切碎；但它属于 ChunkPacker 的可流动类型，会与
 * 邻近说明段落合并，让“前导文字 + 图片”一起被召回。
 */
@Component
public class ImageChunker implements BlockChunker<ImageBlock> {

    /**
     * 将有效图片转换成单个 IMAGE Chunk。
     *
     * @return block 或 asset 为空时返回空列表
     */
    @Override
    public List<VectorChunk> chunk(ImageBlock block, ChunkContext ctx) {
        if (block == null || block.asset() == null) {
            return List.of();
        }
        AssetRef asset = block.asset();

        // caption 优先于 altText；两者都空时仍生成合法的空 alt Markdown。
        String visible = pickCaption(block);
        String markdown = "![" + visible + "](" + asset.publicUrl() + ")";

        // content 服务展示与回答；独立图片有 VLM 描述，MinerU 抽图目前通常只有链接/caption。
        String description = block.description();
        boolean hasDescription = description != null && !description.isBlank();
        String content = hasDescription
                ? description.strip() + "\n\n" + markdown
                : markdown;

        // embeddingText 只保留语义描述；无描述时置 null，Embedding 服务会兼容回退 content。
        String embeddingText = hasDescription ? description.strip() : null;

        VectorChunk chunk = VectorChunk.builder()
                .chunkId(IdUtil.getSnowflakeNextIdStr())
                .index(ctx.startIndex())
                .content(content)
                .embeddingText(embeddingText)
                .blockType("IMAGE")
                .outlinePath(new ArrayList<>(ctx.outlinePath()))
                .sourceBlockIds(List.of(block.id()))
                .assets(List.of(asset))
                .sectionContext(buildSectionContext(block))
                .build();

        return List.of(chunk);
    }

    /** 按 caption → altText → 空串的顺序选择 Markdown 可见文本。 */
    private String pickCaption(ImageBlock block) {
        if (block.caption() != null && !block.caption().isEmpty()) {
            return block.caption();
        }
        if (block.altText() != null && !block.altText().isEmpty()) {
            return block.altText();
        }
        return "";
    }

    /**
     * 当前只把 Excel Sheet 来源转换为节级上下文；普通文档图片返回 null。
     */
    private String buildSectionContext(ImageBlock block) {
        if (block.provenance() == null || block.provenance().sheetName() == null) {
            return null;
        }
        return "sheet=" + block.provenance().sheetName();
    }
}

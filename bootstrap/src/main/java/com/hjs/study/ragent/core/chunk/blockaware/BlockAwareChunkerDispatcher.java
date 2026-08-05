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

import com.hjs.study.ragent.core.chunk.VectorChunk;
import com.hjs.study.ragent.core.parser.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 有序 Block 流的状态化调度器。
 * <p>
 * 单次 {@link #dispatch} 在局部变量中维护当前 outlinePath 和临时 chunkIndex：HeadingBlock 只
 * 改变章节状态，其他 Block 交给强类型 Chunker。第一阶段产出后，再由 {@link ChunkPacker} 把
 * 相邻小型流动块打包到字符预算附近。
 * <p>
 * 该组件本身是 Spring 单例，但所有遍历状态都是方法局部变量，因此可并发处理不同文档。
 * <p>
 * 当前项目的 Java 版本没有使用 sealed switch pattern，故以 instanceof 模式链做穷举。新增
 * Block 子类型时必须同步扩展 {@link #chunkOne}，否则会显式抛错而不是静默丢内容。
 */
@Component
@RequiredArgsConstructor
public class BlockAwareChunkerDispatcher {

    /** 标题状态机，不生成 Chunk。 */
    private final HeadingHandler headingHandler;
    /** 自然段字符窗口切分器。 */
    private final ParagraphChunker paragraphChunker;
    /** 表格行预算切分器。 */
    private final TableChunker tableChunker;
    /** 图片原子 Chunk 转换器。 */
    private final ImageChunker imageChunker;
    /** 代码原子 Chunk 转换器。 */
    private final CodeChunker codeChunker;
    /** 列表按项分组器。 */
    private final ListChunker listChunker;
    /** 第一阶段完成后的相邻小块打包器。 */
    private final ChunkPacker chunkPacker;

    /**
     * 按原文顺序分发全部 Block，并执行最终打包。
     * <p>
     * 第一阶段 index 用于维持局部顺序；ChunkPacker 合并后会从 0 重排。Heading 不占 index。
     *
     * @param blocks 解析器产出的 Block 列表
     * @param config 切分配置
     * @return VectorChunk 列表，index 单调递增
     */
    public List<VectorChunk> dispatch(List<Block> blocks, BlockChunkConfig config) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }

        // 章节路径是遍历状态，只影响标题之后的内容，不回写 Parser Block。
        List<String> outlinePath = List.of();
        List<VectorChunk> result = new ArrayList<>();
        int chunkIndex = 0;

        for (Block b : blocks) {
            if (b instanceof HeadingBlock h) {
                outlinePath = headingHandler.update(outlinePath, h);
                continue;
            }

            ChunkContext ctx = ChunkContext.of(outlinePath, config, chunkIndex);
            List<VectorChunk> chunks = chunkOne(b, ctx);
            result.addAll(chunks);
            chunkIndex += chunks.size();
        }
        // 专用 Chunker 只处理单个 Block；最终打包负责跨 Block 合并与完整块级重叠。
        return chunkPacker.pack(result, config.maxChars(), config.overlapChars());
    }

    /**
     * 将一个非标题 Block 路由到唯一专用实现。
     *
     * @throws IllegalStateException Block 密封层次新增类型但调度器尚未适配
     */
    private List<VectorChunk> chunkOne(Block b, ChunkContext ctx) {
        if (b instanceof ParagraphBlock p) {
            return paragraphChunker.chunk(p, ctx);
        }
        if (b instanceof TableBlock t) {
            return tableChunker.chunk(t, ctx);
        }
        if (b instanceof ImageBlock i) {
            return imageChunker.chunk(i, ctx);
        }
        if (b instanceof CodeBlock c) {
            return codeChunker.chunk(c, ctx);
        }
        if (b instanceof ListBlock l) {
            return listChunker.chunk(l, ctx);
        }
        throw new IllegalStateException("Unsupported Block type: " + b.getClass().getName());
    }
}

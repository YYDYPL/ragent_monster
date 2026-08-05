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
import com.hjs.study.ragent.core.parser.model.Block;

import java.util.List;

/**
 * 单一 Parser Block 类型到 VectorChunk 的转换/切分策略接口。
 * <p>
 * 与 legacy {@link com.hjs.study.ragent.core.chunk.ChunkingStrategy} 的“整篇 String 输入”不同，
 * 本接口一次只处理一个强类型 Block，因此能保留表头、资产和代码等结构语义。实现类均为无状态
 * Spring 单例。
 * <p>
 * 当前分工：
 * <ul>
 *   <li>HeadingHandler：累积 outlinePath，不产 chunk</li>
 *   <li>ParagraphChunker：按 token 切，不跨 heading</li>
 *   <li>TableChunker：按 rowsPerChunk + 表头重复</li>
 *   <li>ImageChunker：atomic，渲染 ![caption](http://...)</li>
 *   <li>CodeChunker：atomic（代码切碎危害大）</li>
 *   <li>ListChunker:短列表 atomic,长列表按项分组</li>
 * </ul>
 *
 * @param <B> 该 chunker 处理的 Block 子类型
 */
public interface BlockChunker<B extends Block> {

    /**
     * 把单个 Block 转换为零到多个 VectorChunk。
     * <p>
     * 结果必须保持 Block 内部顺序，首个 index 使用 ctx.startIndex，后续单调递增。Dispatcher 会
     * 累加数量，并在最后交给 ChunkPacker 统一合并和重排。
     *
     * @param block 待切分的 Block
     * @param ctx   切分上下文（outlinePath + 配置 + 起始 index）
     * @return 切分结果；空/无效内容可返回空列表
     */
    List<VectorChunk> chunk(B block, ChunkContext ctx);
}

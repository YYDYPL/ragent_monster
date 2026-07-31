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

package com.hjs.study.ragent.core.parser.model;

import java.util.List;

/**
 * 图片 Block。
 * <p>
 * ImageChunker 将其作为原子 Chunk，并渲染为图片 Markdown。原子保护可避免链接被字符切分器
 * 截断。独立图片解析器会填写 description；MinerU 提取的文档内图片当前通常没有 description。
 *
 * @param id          Block 唯一 ID，应与 asset.sourceBlockId 对齐
 * @param provenance 原始文档来源
 * @param outlinePath 所属章节路径
 * @param asset       图片资产引用
 * @param caption     图片标题，如“图3-1：系统架构图”
 * @param altText     无障碍替代文本
 * @param description VLM 图生文结果：用于 embedding 检索和 LLM 答题；未执行图生文时为 null
 */
public record ImageBlock(
        String id,
        Provenance provenance,
        List<String> outlinePath,
        AssetRef asset,
        String caption,
        String altText,
        String description
) implements Block {

    /**
     * 向后兼容构造器：不产图生文的来源继续使用六参数形式，description 置空。
     *
     * @param id Block ID
     * @param provenance 来源
     * @param outlinePath 章节路径
     * @param asset 资产引用
     * @param caption 标题
     * @param altText 替代文本
     */
    public ImageBlock(String id, Provenance provenance, List<String> outlinePath,
                      AssetRef asset, String caption, String altText) {
        this(id, provenance, outlinePath, asset, caption, altText, null);
    }
}

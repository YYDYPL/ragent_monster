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

package com.hjs.study.ragent.core.parser.image;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图片解析配置（图生文）。
 * <p>
 * 绑定 {@code application.yaml} 中的 {@code rag.image-parse.*}。该对象由 Spring 在启动期创建，
 * 解析器把配置值直接传给 VLM；修改 Prompt 会改变入库文本和向量语义，因此属于数据行为变更，
 * 已入库图片不会自动重算。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.image-parse")
public class ImageParseProperties {

    /**
     * 图生文引导提示词：要求 VLM 输出中文描述和图中文字 OCR。
     * <p>
     * 解析器不依赖固定分隔符，而是把整个模型输出作为 description，所以 Prompt 可以调整组织
     * 形式；但仍应要求输出自包含文本，避免检索命中后缺少上下文。
     */
    private String descriptionPrompt = "请用中文详细描述这张图片的内容；若图中包含文字，请逐字识别并完整列出（OCR）。"
            + "先给出整体内容描述，再用\"图中文字：\"另起一段列出识别到的所有文字。";

    /**
     * 描述输出 token 上限，用于控制模型成本和 embedding 文本体量；小于等于 0 表示不限制。
     */
    private Integer maxOutputTokens = 1024;
}

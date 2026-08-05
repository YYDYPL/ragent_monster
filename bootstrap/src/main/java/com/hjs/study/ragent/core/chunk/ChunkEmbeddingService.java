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

import com.hjs.study.ragent.framework.exception.ClientException;
import com.hjs.study.ragent.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Chunk 到 Embedding 的批量适配服务。
 * <p>
 * 本类位于“切分”和“索引”之间，只负责选择每个 Chunk 的向量化文本、调用
 * {@link EmbeddingService}，并把返回的 {@code List<Float>} 转成向量存储使用的 {@code float[]}。
 * 它不创建 Chunk、不持久化，也不处理模型路由细节。
 * <p>
 * 方法会原地修改传入的 VectorChunk。若所有 Chunk 已有非空 embedding，则直接跳过；只要其中
 * 一个缺失，就会整批重新计算并覆盖全部 embedding，以保持输入顺序与返回顺序一一对应。
 */
@Service
@RequiredArgsConstructor
public class ChunkEmbeddingService {

    /** 支持默认模型和显式模型 ID 的 Embedding 基础设施门面。 */
    private final EmbeddingService embeddingService;

    /**
     * 为分块列表批量计算嵌入向量。
     * <p>
     * 批量请求保持 chunks 顺序。服务返回行数必须完全相等，否则无法安全判断某个向量属于哪个
     * Chunk，因此直接抛出 ClientException，不进行部分写入。
     *
     * @param chunks         已切分的文本块（embedding 字段将被原地填充）
     * @param embeddingModel 嵌入模型 ID，null 或空白时使用系统默认模型
     */
    public void embed(List<VectorChunk> chunks, String embeddingModel) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        if (chunks.stream().allMatch(c -> c.getEmbedding() != null && c.getEmbedding().length > 0)) {
            return;
        }
        List<String> texts = chunks.stream()
                .map(ChunkEmbeddingService::embedTextOf)
                .toList();
        List<List<Float>> vectors = StringUtils.hasText(embeddingModel)
                ? embeddingService.embedBatch(texts, embeddingModel)
                : embeddingService.embedBatch(texts);
        applyEmbeddings(chunks, vectors);
    }

    /**
     * 选择单个 Chunk 的向量化输入。
     * <p>
     * embeddingText 能去掉图片 URL 噪声、显式表达表格列值关系；只有它为空白时才使用展示正文。
     * content 为 null 时返回空串，最终是否接受空输入由 Embedding Provider 决定。
     */
    private static String embedTextOf(VectorChunk c) {
        if (StringUtils.hasText(c.getEmbeddingText())) {
            return c.getEmbeddingText();
        }
        return c.getContent() == null ? "" : c.getContent();
    }

    /**
     * 校验批量响应并按索引原地回填基本类型数组。
     * <p>
     * 先验证总行数，再逐行转换，避免结果错位；维度一致性由模型服务或向量存储进一步校验。
     */
    private void applyEmbeddings(List<VectorChunk> chunks, List<List<Float>> vectors) {
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new ClientException("Embedding result size mismatch");
        }
        for (int i = 0; i < chunks.size(); i++) {
            List<Float> row = vectors.get(i);
            if (row == null) {
                throw new ClientException("Embedding result missing, index: " + i);
            }
            float[] vec = new float[row.size()];
            for (int j = 0; j < row.size(); j++) {
                vec[j] = row.get(j);
            }
            chunks.get(i).setEmbedding(vec);
        }
    }
}

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

package com.hjs.study.ragent.rag.core.keyword;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EsKeywordRetrieverServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeProjectionFreshnessFieldsFromElasticsearchSource() throws Exception {
        String source = """
                {
                  "content": "SLA 服务等级",
                  "kb_id": "kb-1",
                  "doc_id": "doc-1",
                  "chunk_id": "chunk-1",
                  "chunk_index": 2,
                  "content_version": 7
                }
                """;

        EsKeywordRetrieverService.KeywordHitDocument hit = objectMapper.readValue(
                source, EsKeywordRetrieverService.KeywordHitDocument.class);

        assertThat(hit.getContent()).isEqualTo("SLA 服务等级");
        assertThat(hit.getKbId()).isEqualTo("kb-1");
        assertThat(hit.getDocId()).isEqualTo("doc-1");
        assertThat(hit.getChunkId()).isEqualTo("chunk-1");
        assertThat(hit.getChunkIndex()).isEqualTo(2);
        assertThat(hit.getContentVersion()).isEqualTo(7L);
    }
}

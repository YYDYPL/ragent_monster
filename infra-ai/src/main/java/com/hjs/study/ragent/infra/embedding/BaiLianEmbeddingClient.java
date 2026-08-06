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

package com.hjs.study.ragent.infra.embedding;

import com.hjs.study.ragent.infra.enums.ModelProvider;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

/**
* 阿里云百炼（BaiLian）Embedding 客户端
* <p>
* 百炼 OpenAI 兼容接口的 text-embedding-v4 支持 1536 维输出，
* 与数据库默认向量维度一致，无需迁移存量向量数据。
*
* @see AbstractOpenAIStyleEmbeddingClient 父类提供 OpenAI 协议通用逻辑
*/
@Service
public class BaiLianEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

   public BaiLianEmbeddingClient(OkHttpClient syncHttpClient) {
       super(syncHttpClient);
   }

   @Override
   public String provider() {
       return ModelProvider.BAI_LIAN.getId();
   }

   /**
    * 百炼 text-embedding-v4 单次请求最多 10 条文本，超出由父类自动分批
    */
   @Override
   protected int maxBatchSize() {
       return 10;
   }
}

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

package com.hjs.study.ragent.infra.chat;

import com.google.gson.JsonObject;
import com.hjs.study.ragent.framework.convention.ChatRequest;
import com.hjs.study.ragent.framework.trace.RagTraceNode;
import com.hjs.study.ragent.infra.enums.ModelProvider;
import com.hjs.study.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
* DeepSeek（深度求索）Chat 客户端
* <p>
* DeepSeek 官方 API 兼容 OpenAI 协议，本客户端继承 {@link AbstractOpenAIStyleChatClient}
* 复用其通用逻辑，仅需声明 provider 标识为 {@link ModelProvider#DEEPSEEK}。
*
* @see AbstractOpenAIStyleChatClient 父类提供 OpenAI 协议通用逻辑
*/
@Slf4j
@Service
public class DeepSeekChatClient extends AbstractOpenAIStyleChatClient {

   @Override
   public String provider() {
       return ModelProvider.DEEPSEEK.getId();
   }

   /**
    * DeepSeek 官方 API 无 enable_thinking 参数：deepseek-chat 不支持思考模式，
    * deepseek-reasoner 原生返回思考链，因此覆盖为空实现避免发送无效字段
    */
   @Override
   protected void customizeRequestBody(JsonObject body, ChatRequest request) {
       // DeepSeek 官方 API 无 enable_thinking 参数
   }

   /**
    * 同步聊天调用，通过 {@link RagTraceNode} 生成 "deepseek-chat" trace 节点
    */
   @Override
   @RagTraceNode(name = "deepseek-chat", type = "LLM_PROVIDER")
   public String chat(ChatRequest request, ModelTarget target) {
       return doChat(request, target);
   }

   /**
    * 流式聊天调用，委托父类模板方法处理 SSE 流式解析和回调
    */
   @Override
   public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
       return doStreamChat(request, callback, target);
   }
}

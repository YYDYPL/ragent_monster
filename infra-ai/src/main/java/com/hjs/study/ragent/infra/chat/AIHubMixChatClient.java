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

import com.hjs.study.ragent.framework.convention.ChatRequest;
import com.hjs.study.ragent.framework.trace.RagTraceNode;
import com.hjs.study.ragent.infra.enums.ModelProvider;
import com.hjs.study.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AIHubMix Chat 客户端
 * <p>
 * AIHubMix 是一个 OpenAI 兼容协议的模型聚合平台，支持多种模型统一接入。
 * 本客户端继承 {@link AbstractOpenAIStyleChatClient}，复用其 OpenAI 协议风格的
 * 同步/流式调用模板，仅需声明 provider 标识和 trace 节点名
 *
 * @see AbstractOpenAIStyleChatClient 父类提供 OpenAI 协议通用逻辑
 */
@Slf4j
@Service
public class AIHubMixChatClient extends AbstractOpenAIStyleChatClient {

    @Override
    public String provider() {
        return ModelProvider.AI_HUB_MIX.getId();
    }

    /**
     * 同步聊天调用，通过 {@link RagTraceNode} 生成 "aihubmix-chat" trace 节点
     */
    @Override
    @RagTraceNode(name = "aihubmix-chat", type = "LLM_PROVIDER")
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

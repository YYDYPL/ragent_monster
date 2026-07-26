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
 * Ollama Chat 客户端
 * <p>
 * Ollama 是本地大模型推理引擎，兼容 OpenAI 协议但无需 API Key。
 * 本客户端通过覆写 {@link #requiresApiKey()} 返回 false 跳过鉴权，
 * 其余同步/流式逻辑复用 {@link AbstractOpenAIStyleChatClient}
 *
 * @see AbstractOpenAIStyleChatClient 父类提供 OpenAI 协议通用逻辑
 */
@Slf4j
@Service
public class OllamaChatClient extends AbstractOpenAIStyleChatClient {

    @Override
    public String provider() {
        return ModelProvider.OLLAMA.getId();
    }

    /**
     * Ollama 本地部署通常无需 API Key，覆写跳过鉴权
     */
    @Override
    protected boolean requiresApiKey() {
        return false;
    }

    /**
     * 同步聊天调用，通过 {@link RagTraceNode} 生成 "ollama-chat" trace 节点
     */
    @Override
    @RagTraceNode(name = "ollama-chat", type = "LLM_PROVIDER")
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

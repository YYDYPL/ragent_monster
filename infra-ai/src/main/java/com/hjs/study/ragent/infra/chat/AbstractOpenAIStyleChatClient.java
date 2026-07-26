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

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hjs.study.ragent.framework.convention.ChatMessage;
import com.hjs.study.ragent.framework.convention.ChatRequest;
import com.hjs.study.ragent.framework.trace.RagStreamTraceSupport;
import com.hjs.study.ragent.framework.trace.RagStreamTraceSupport.StreamSpan;
import com.hjs.study.ragent.infra.config.AIModelProperties;
import com.hjs.study.ragent.infra.enums.ModelCapability;
import com.hjs.study.ragent.infra.http.*;
import com.hjs.study.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容协议 ChatClient 抽象基类 —— 模板方法模式
 * <p>
 * 为所有遵循 OpenAI API 协议的模型提供商提供统一的同步/流式调用模板，
 * 子类只需声明 provider 标识和 trace 节点名即可接入。
 * <p>
 * <b>模板方法：</b>
 * <ul>
 *   <li>{@link #doChat(ChatRequest, ModelTarget)} —— 同步调用模板</li>
 *   <li>{@link #doStreamChat(ChatRequest, StreamCallback, ModelTarget)} —— 流式调用模板</li>
 * </ul>
 * <p>
 * <b>子类可覆写的钩子方法：</b>
 * <ul>
 *   <li>{@link #requiresApiKey()} —— 是否需要 API Key 鉴权（默认 true，Ollama 等本地引擎覆写为 false）</li>
 *   <li>{@link #customizeRequestBody(JsonObject, ChatRequest)} —— 添加提供商特有的请求体字段</li>
 *   <li>{@link #isReasoningEnabledForStream(ChatRequest)} —— 流式时是否启用思考链解析</li>
 * </ul>
 * <p>
 * <b>超时机制：</b>
 * 同步调用时按档位超时预算派生 OkHttpClient（仅覆盖 readTimeout/callTimeout），
 * 派生客户端通过 {@link #syncClientByTimeout} 缓存复用，避免每次重建。
 * 流式调用不覆盖超时（首包探测的超时由 {@link ProbeStreamBridge} 在上层控制）
 *
 * @see ChatClient 聊天客户端接口
 * @see OpenAIStyleSseParser OpenAI 协议 SSE 解析器
 */
@Slf4j
public abstract class AbstractOpenAIStyleChatClient implements ChatClient {

    @Autowired
    private OkHttpClient syncHttpClient;
    @Autowired
    private OkHttpClient streamingHttpClient;
    @Autowired
    private Executor modelStreamExecutor;
    @Autowired
    private RagStreamTraceSupport streamTraceSupport;

    protected Gson gson = new Gson();

    /**
     * 按档位超时预算派生的同步客户端缓存（key=timeoutMs）
     * 档位超时值仅少数几种，派生客户端经 newBuilder 复用连接池/线程池，缓存后避免每次调用重建
     */
    private final Map<Long, OkHttpClient> syncClientByTimeout = new ConcurrentHashMap<>();

    // ==================== 子类钩子方法 ====================

    /**
     * 流式调用时是否启用 reasoning_content 解析，默认根据请求中的 thinking 标志决定
     */
    protected boolean isReasoningEnabledForStream(ChatRequest request) {
        return Boolean.TRUE.equals(request.getThinking());
    }

    /**
     * 子类可覆写此方法添加提供商特有的请求体字段
     * 默认实现：当请求开启 thinking 时添加 enable_thinking 字段
     */
    protected void customizeRequestBody(JsonObject body, ChatRequest request) {
        if (Boolean.TRUE.equals(request.getThinking())) {
            body.addProperty("enable_thinking", true);
        }
    }

    /**
     * 是否要求提供商配置 API Key
     */
    protected boolean requiresApiKey() {
        return true;
    }

    // ==================== 模板方法：同步调用 ====================

    /**
     * 同步聊天模板方法
     * <p>
     * 执行流程：
     * <ol>
     *   <li>校验 provider 配置和 API Key</li>
     *   <li>构建 OpenAI 协议请求体（JSON）</li>
     *   <li>通过档位超时预算选择合适的 OkHttpClient</li>
     *   <li>发起 HTTP POST 同步调用</li>
     *   <li>校验 HTTP 状态码 → 解析 JSON 响应 → 提取 content</li>
     * </ol>
     *
     * @param request 聊天请求
     * @param target  模型目标（含候选配置、provider 配置、超时预算）
     * @return 模型返回的完整文本
     * @throws ModelClientException HTTP 错误、网络异常或响应格式异常时抛出
     */
    protected String doChat(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        JsonObject reqBody = buildRequestBody(request, target, false);
        Request requestHttp = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .build();

        Call httpCall = resolveSyncClient(target.timeoutMs()).newCall(requestHttp);

        JsonObject respJson;
        try (Response response = httpCall.execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} 同步请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " 同步请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(
                    provider() + " 同步请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        return extractChatContent(respJson);
    }

    /**
     * 取按档位超时预算派生的同步客户端；timeoutMs 为空时用基础客户端（走 HttpClientConfig 默认超时）
     * <p>
     * connect/write 沿用基础客户端（请求体小、连接建立无需占用整段预算），仅覆盖 read/call
     */
    private OkHttpClient resolveSyncClient(Long timeoutMs) {
        if (timeoutMs == null) {
            return syncHttpClient;
        }
        return syncClientByTimeout.computeIfAbsent(timeoutMs, ms -> syncHttpClient.newBuilder()
                .readTimeout(ms, TimeUnit.MILLISECONDS)
                .callTimeout(ms, TimeUnit.MILLISECONDS)
                .build());
    }

    // ==================== 模板方法：流式调用 ====================

    /**
     * 流式聊天模板方法
     * <p>
     * 执行流程：
     * <ol>
     *   <li>校验 provider 配置和 API Key</li>
     *   <li>构建 OpenAI 协议请求体（含 stream=true）</li>
     *   <li>创建 OkHttp Call，提交到 {@code modelStreamExecutor} 异步执行</li>
     *   <li>在调用线程开启 stream trace span，SSE 终态时收尾记录端到端耗时</li>
     *   <li>用 {@link StreamSpanCallback} 包装下游 callback，实现 trace 自动收尾</li>
     *   <li>返回 {@link StreamCancellationHandle} 供调用方取消</li>
     * </ol>
     * <p>
     * <b>取消语义：</b>先取消 OkHttp Call（底层连接释放），再触发 StreamSpan 的取消收尾
     *
     * @param request  聊天请求
     * @param callback 下游流式回调
     * @param target   模型目标
     * @return 取消句柄
     */
    protected StreamCancellationHandle doStreamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        JsonObject reqBody = buildRequestBody(request, target, true);
        Request streamRequest = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Accept", "text/event-stream")
                .build();

        Call call = streamingHttpClient.newCall(streamRequest);
        boolean reasoningEnabled = isReasoningEnabledForStream(request);

        // 在调用线程开 stream span，使后续 first-packet 子节点能正确归属父节点；
        // 该 span 由 SSE 终态（onComplete / onError）或 cancel 时收尾，记录真实端到端耗时
        StreamSpan span = streamTraceSupport.beginStreamNode(provider() + "-stream-chat", "LLM_PROVIDER");
        StreamSpanCallback wrappedCallback;
        try {
            wrappedCallback = new StreamSpanCallback(callback, span);
            StreamCancellationHandle inner = StreamAsyncExecutor.submit(
                    modelStreamExecutor,
                    call,
                    wrappedCallback,
                    cancelled -> doStream(call, wrappedCallback, cancelled, reasoningEnabled)
            );
            return () -> {
                try {
                    inner.cancel();
                } finally {
                    wrappedCallback.onCancel();
                }
            };
        } finally {
            // 同步部分结束：把节点从当前线程的 NODE_STACK 弹出，避免污染兄弟节点的父节点链
            span.detach();
        }
    }

    /**
     * 流式 SSE 读取循环（在异步线程中执行）
     * <p>
     * 核心循环：逐行读取 SSE → 解析 → 回调 → 检测完成/取消
     * <ol>
     *   <li>执行 HTTP 请求，校验状态码</li>
     *   <li>获取响应体 BufferedSource，逐行读取 UTF-8 文本</li>
     *   <li>每行经 {@link OpenAIStyleSseParser#parseLine} 解析为 {@link OpenAIStyleSseParser.ParsedEvent}</li>
     *   <li>有思考内容 → {@code callback.onThinking}</li>
     *   <li>有文本内容 → {@code callback.onContent}</li>
     *   <li>检测到完成标记 → {@code callback.onComplete}，退出循环</li>
     *   <li>检测到取消信号 → 静默退出</li>
     *   <li>异常结束（未完成且未取消）→ {@code callback.onError}</li>
     * </ol>
     *
     * @param call             OkHttp Call 实例
     * @param callback         下游回调（通常是 {@link StreamSpanCallback} 包装后的）
     * @param cancelled        取消信号标志（由 {@link StreamCancellationHandle#cancel} 设置）
     * @param reasoningEnabled 是否解析思考链内容
     */
    private void doStream(Call call, StreamCallback callback, AtomicBoolean cancelled, boolean reasoningEnabled) {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                throw new ModelClientException(
                        provider() + " 流式请求失败: HTTP " + response.code() + " - " + body,
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModelClientException(provider() + " 流式响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            BufferedSource source = body.source();
            boolean completed = false;
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, reasoningEnabled);
                    if (event.hasReasoning()) {
                        callback.onThinking(event.reasoning());
                    }
                    if (event.hasContent()) {
                        callback.onContent(event.content());
                    }
                    if (event.completed()) {
                        callback.onComplete();
                        completed = true;
                        break;
                    }
                } catch (Exception parseEx) {
                    log.warn("{} 流式响应解析失败: line={}", provider(), line, parseEx);
                }
            }
            if (cancelled.get()) {
                log.info("{} 流式响应已被取消", provider());
                return;
            }
            if (!completed) {
                throw new ModelClientException(provider() + " 流式响应异常结束", ModelClientErrorType.INVALID_RESPONSE, null);
            }
        } catch (Exception e) {
            if (!cancelled.get()) {
                callback.onError(e);
            } else {
                log.info("{} 流式响应取消期间产生异常（可忽略）: {}", provider(), e.getMessage());
            }
        }
    }

    // ==================== 公共构建方法 ====================

    /**
     * 构建 OpenAI 协议请求体
     * <p>
     * 包含标准字段：model、stream、messages、temperature、top_p、top_k、max_tokens。
     * 最后调用 {@link #customizeRequestBody} 允许子类添加提供商特有字段（如 enable_thinking）
     *
     * @param request 聊天请求
     * @param target  模型目标
     * @param stream  是否为流式请求
     * @return JSON 请求体
     */
    protected JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        if (stream) {
            body.addProperty("stream", true);
        }

        body.add("messages", buildMessages(request));

        if (request.getTemperature() != null) {
            body.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.addProperty("top_p", request.getTopP());
        }
        if (request.getTopK() != null) {
            body.addProperty("top_k", request.getTopK());
        }
        if (request.getMaxTokens() != null) {
            body.addProperty("max_tokens", request.getMaxTokens());
        }

        customizeRequestBody(body, request);
        return body;
    }

    /**
     * 将 {@link ChatRequest} 中的消息列表转换为 OpenAI 协议的 messages JSON 数组
     */
    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();
        List<ChatMessage> messages = request.getMessages();
        if (CollUtil.isNotEmpty(messages)) {
            for (ChatMessage m : messages) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", toOpenAiRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }
        return arr;
    }

    /**
     * 将内部 Role 枚举转换为 OpenAI 协议的 role 字符串
     */
    private String toOpenAiRole(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    /**
     * 构建带鉴权的 OkHttp Request Builder
     * <p>
     * URL 通过 {@link ModelUrlResolver} 根据 provider 配置和候选模型动态解析。
     * 若子类覆写 {@link #requiresApiKey()} 返回 true，则自动添加 Bearer Token 鉴权头
     */
    private Request.Builder newAuthorizedRequest(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        Request.Builder builder = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT));
        if (requiresApiKey()) {
            builder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        return builder;
    }

    /**
     * 从同步响应的 JSON 中提取 choices[0].message.content
     * <p>
     * 严格校验响应结构完整性：choices 存在 → 非空 → message 存在 → content 存在且非 null。
     * 任一环节缺失均抛出 {@link ModelClientException}
     */
    private String extractChatContent(JsonObject respJson) {
        if (respJson == null || !respJson.has("choices")) {
            throw new ModelClientException(provider() + " 响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException(provider() + " 响应 choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException(provider() + " 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = choice0.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException(provider() + " 响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return message.get("content").getAsString();
    }
}

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
import com.hjs.study.ragent.framework.convention.ChatRequest;
import com.hjs.study.ragent.framework.errorcode.BaseErrorCode;
import com.hjs.study.ragent.framework.exception.RemoteException;
import com.hjs.study.ragent.framework.trace.RagTraceNode;
import com.hjs.study.ragent.infra.enums.ModelCapability;
import com.hjs.study.ragent.infra.enums.Tier;
import com.hjs.study.ragent.infra.model.ModelHealthStore;
import com.hjs.study.ragent.infra.model.ModelRoutingExecutor;
import com.hjs.study.ragent.infra.model.ModelSelector;
import com.hjs.study.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 LLM 服务实现类 —— 基于模型选择器 + 故障转移的统一 LLM 调用入口
 * <p>
 * 核心职责：
 * <ol>
 *   <li><b>同步调用</b>：委托 {@link ModelRoutingExecutor} 按档位候选列表逐个尝试，
 *       任一成功即返回，全部失败则抛异常</li>
 *   <li><b>流式调用</b>：自行实现首包探测 + 故障转移循环，
 *       通过 {@link ProbeStreamBridge} 缓存首包前的内容，
 *       首包成功→提交缓存并继续流式推送；首包失败/超时→切换下一个候选模型</li>
 * </ol>
 * <p>
 * 流式故障转移流程：
 * <ol>
 *   <li>通过 {@link ModelSelector} 获取 chat 候选列表</li>
 *   <li>遍历候选：解析客户端 → 断路器检查 → 发起流式请求</li>
 *   <li>用 {@link ProbeStreamBridge} 包装下游 callback，阻塞等待首包</li>
 *   <li>首包 SUCCESS → commit bridge（回放缓存内容），标记健康，返回取消句柄</li>
 *   <li>首包 失败/超时/无内容 → 标记故障，取消当前流式请求，尝试下一个候选</li>
 *   <li>全部候选失败 → 通过 callback.onError 通知客户端</li>
 * </ol>
 *
 * @see ModelSelector 模型选择器
 * @see ModelRoutingExecutor 同步调用故障转移执行器
 * @see ProbeStreamBridge 首包探测桥接器
 */
@Slf4j
@Service
@Primary
public class RoutingLLMService implements LLMService {

    private static final String STREAM_INTERRUPTED_MESSAGE = "流式请求被中断";
    private static final String STREAM_NO_PROVIDER_MESSAGE = "无可用大模型提供者";
    private static final String STREAM_START_FAILED_MESSAGE = "流式请求启动失败";
    private static final String STREAM_TIMEOUT_MESSAGE = "流式首包超时";
    private static final String STREAM_NO_CONTENT_MESSAGE = "流式请求未返回内容";
    private static final String STREAM_ALL_FAILED_MESSAGE = "大模型调用失败，请稍后再试...";

    private final ModelSelector selector;
    private final ModelHealthStore healthStore;
    private final ModelRoutingExecutor executor;
    private final LlmFirstPacketProbe firstPacketProbe;
    private final Map<String, ChatClient> clientsByProvider;

    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            LlmFirstPacketProbe firstPacketProbe,
            List<ChatClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.firstPacketProbe = firstPacketProbe;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request) {
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking())),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request, Tier tier) {
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking()), tier),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request, Tier tier, String preferredModelId) {
        if (!StringUtils.hasText(preferredModelId)) {
            return chat(request, tier);
        }
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking()), tier, preferredModelId),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    /**
     * 流式调用（默认档位）—— 带首包探测的故障转移
     * <p>
     * 与同步调用的 {@link ModelRoutingExecutor#executeWithFallback} 不同，
     * 流式场景无法直接复用该执行器（因为流式需要边收边推，不能等全部返回后再判断），
     * 因此自行实现等价的故障转移循环：
     * <ol>
     *   <li>获取候选列表，遍历每个候选</li>
     *   <li>解析客户端 → 断路器准入检查 → 发起流式请求到 {@link ProbeStreamBridge}</li>
     *   <li>阻塞等待首包探测结果（超时预算 = 档位的 timeoutMs）</li>
     *   <li>SUCCESS → 标记健康，返回取消句柄（bridge 已 commit，后续增量实时推送）</li>
     *   <li>其他 → 标记故障，取消当前流，尝试下一个候选</li>
     *   <li>全部失败 → 通过 {@code callback.onError} 通知客户端</li>
     * </ol>
     */
    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        List<ModelTarget> targets = selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking()));
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException(STREAM_NO_PROVIDER_MESSAGE);
        }

        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;

        for (ModelTarget target : targets) {
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }
            if (!healthStore.allowCall(target.id())) {
                continue;
            }

            ProbeStreamBridge bridge = new ProbeStreamBridge(callback);

            StreamCancellationHandle handle;
            try {
                handle = client.streamChat(request, bridge, target);
            } catch (Exception e) {
                healthStore.markFailure(target.id());
                lastError = e;
                log.warn("{} 流式请求启动失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), e);
                continue;
            }
            if (handle == null) {
                healthStore.markFailure(target.id());
                lastError = new RemoteException(STREAM_START_FAILED_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 流式请求未返回取消句柄，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider());
                continue;
            }

            long firstPacketBudgetMs = target.timeoutMs();
            ProbeStreamBridge.ProbeResult result = awaitFirstPacket(bridge, handle, callback, firstPacketBudgetMs);

            if (result.isSuccess()) {
                healthStore.markSuccess(target.id());
                return handle;
            }

            // 失败处理
            healthStore.markFailure(target.id());
            handle.cancel();

            lastError = buildLastErrorAndLog(result, target, label);
        }

        // 所有模型都失败了，通知客户端错误
        throw notifyAllFailed(callback, lastError);
    }

    /**
     * 根据 ModelTarget 的 provider 从注册表中解析对应的 ChatClient 实例
     *
     * @return 匹配的 ChatClient，未注册时返回 null 并告警
     */
    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    /**
     * 阻塞等待首包探测结果，超时预算来自档位配置
     * <p>
     * 若等待过程中线程被中断（InterruptedException），取消流式请求并通知客户端错误，
     * 然后抛出 RemoteException 终止故障转移循环（中断是全局信号，不应继续尝试下一个候选）
     */
    private ProbeStreamBridge.ProbeResult awaitFirstPacket(ProbeStreamBridge bridge,
                                                           StreamCancellationHandle handle,
                                                           StreamCallback callback,
                                                           long firstPacketBudgetMs) {
        try {
            return firstPacketProbe.awaitFirstPacket(bridge, firstPacketBudgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancel();
            RemoteException interruptedException = new RemoteException(STREAM_INTERRUPTED_MESSAGE, e, BaseErrorCode.REMOTE_ERROR);
            callback.onError(interruptedException);
            throw interruptedException;
        }
    }

    /**
     * 根据探测结果类型构造对应的异常并记日志，用于故障转移时记录"为什么跳过了这个候选"
     *
     * @return 匹配探测结果类型的异常实例，用于最终全部失败时的 lastError 汇总
     */
    private Throwable buildLastErrorAndLog(ProbeStreamBridge.ProbeResult result, ModelTarget target, String label) {
        switch (result.getType()) {
            case ERROR -> {
                Throwable error = result.getError() != null
                        ? result.getError()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败，切换下一个模型",
                        label, target.id(), target.candidate().getProvider(), error);
                return error;
            }
            case TIMEOUT -> {
                RemoteException timeout = new RemoteException(STREAM_TIMEOUT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求超时，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return timeout;
            }
            case NO_CONTENT -> {
                RemoteException noContent = new RemoteException(STREAM_NO_CONTENT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求无内容完成，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return noContent;
            }
            default -> {
                RemoteException unknown = new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败（未知类型），切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return unknown;
            }
        }
    }

    /**
     * 全部候选失败：通过 callback.onError 通知客户端，并抛出异常终止路由循环
     * <p>
     * 注意：该方法同时调用 callback.onError 和抛出异常。
     * onError 确保客户端感知到失败（如前端停止 loading），
     * throw 则终止当前的故障转移循环并让上层感知
     */
    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError) {
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }
}

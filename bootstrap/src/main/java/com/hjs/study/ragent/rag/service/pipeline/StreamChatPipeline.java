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

package com.hjs.study.ragent.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.hjs.study.ragent.framework.convention.ChatMessage;
import com.hjs.study.ragent.framework.convention.ChatRequest;
import com.hjs.study.ragent.framework.convention.SourceRef;
import com.hjs.study.ragent.infra.chat.LLMService;
import com.hjs.study.ragent.infra.chat.StreamCallback;
import com.hjs.study.ragent.infra.chat.StreamCancellationHandle;
import com.hjs.study.ragent.rag.core.guidance.GuidanceDecision;
import com.hjs.study.ragent.rag.core.guidance.IntentGuidanceService;
import com.hjs.study.ragent.rag.core.intent.IntentResolver;
import com.hjs.study.ragent.rag.core.memory.ConversationMemoryService;
import com.hjs.study.ragent.rag.core.prompt.PromptContext;
import com.hjs.study.ragent.rag.core.prompt.PromptTemplateLoader;
import com.hjs.study.ragent.rag.core.prompt.RAGPromptService;
import com.hjs.study.ragent.rag.core.retrieval.RetrievalEngine;
import com.hjs.study.ragent.rag.core.rewrite.QueryRewriteService;
import com.hjs.study.ragent.rag.core.rewrite.RewriteResult;
import com.hjs.study.ragent.rag.core.source.GroundingChunksAssembler;
import com.hjs.study.ragent.rag.core.source.SourcesAssembler;
import com.hjs.study.ragent.rag.dto.IntentGroup;
import com.hjs.study.ragent.rag.dto.RetrievalContext;
import com.hjs.study.ragent.rag.dto.SubQuestionIntent;
import com.hjs.study.ragent.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.hjs.study.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;

/**
 * 流式对话流水线
 * <p>
 * 承载从 RAGChatServiceImpl 提取的业务编排逻辑：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 系统响应 / 检索 -> Prompt 组装 -> 流式输出
 * <p>
 * 流水线模式：通过私有方法 + boolean 返回值（handleXxx 返回 true 表示已处理并短路）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    private final ConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final StreamTaskManager taskManager;
    private final SourcesAssembler sourcesAssembler;
    private final GroundingChunksAssembler groundingChunksAssembler;

    /**
     * 执行流式对话管道
     */
    public void execute(StreamChatContext ctx) {
        loadMemory(ctx);
        rewriteQuery(ctx);
        resolveIntents(ctx);

        if (handleGuidance(ctx)) {
            return;
        }
        if (handleSystemOnly(ctx)) {
            return;
        }

        RetrievalContext retrievalCtx = retrieve(ctx);
        if (handleEmptyRetrieval(ctx, retrievalCtx)) {
            return;
        }

        streamRagResponse(ctx, retrievalCtx);
    }

    // ==================== 流水线阶段 ====================

    /**
     * 加载会话记忆，并记录当前用户问题到会话中
     *
     * @param ctx 流式对话上下文
     */
    private void loadMemory(StreamChatContext ctx) {
        // 1. 根据会话ID和用户ID加载历史消息列表
        List<ChatMessage> history = memoryService.load(ctx.getConversationId(), ctx.getUserId());

        // 2. 将当前用户问题作为用户消息追加到会话中，并返回该消息的消息ID
        String questionMessageId = memoryService.append(
                ctx.getConversationId(), ctx.getUserId(), ChatMessage.user(ctx.getQuestion()));

        // 3. 通知前端当前回复所对应的消息ID（用于消息对答定位）
        ctx.getCallback().onReplyToMessageId(questionMessageId);

        // 4. 将加载到的历史消息保存到上下文中，供后续改写/检索等阶段使用
        ctx.setHistory(history);
    }

    /**
     * 改写查询：对用户原始问题进行改写与拆分，生成适合检索的查询条件。
     * 改写结果会保存到上下文中，供后续意图解析使用。
     */
    private void rewriteQuery(StreamChatContext ctx) {
        // 调用查询改写服务：结合当前问题与会话历史进行语义改写，并拆分成多个子查询
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(ctx.getQuestion(), ctx.getHistory());
        // 将改写结果暂存到上下文对象中，供后续流水线阶段读取
        ctx.setRewriteResult(rewriteResult);
    }

    /**
     * 解析意图：将改写后的查询结果解析为子问题意图列表，并保存到上下文中。
     * 后续流程（歧义引导、检索等）会基于这些意图进行决策。
     *
     * @param ctx 流式对话上下文
     */
    private void resolveIntents(StreamChatContext ctx) {
        // 调用意图解析器，基于改写结果（含重写后的问题、子问题拆分等）识别每个子问题的意图
        List<SubQuestionIntent> subIntents = intentResolver.resolve(ctx.getRewriteResult());
        // 将解析得到的子问题意图列表暂存到上下文中，供后续阶段（如歧义检测、检索、Prompt组装）使用
        ctx.setSubIntents(subIntents);
    }

    /**
     * 处理歧义引导阶段。
     * <p>
     * 基于改写后的查询和子问题意图，检测当前用户问题是否存在歧义；
     * 若存在歧义且需要引导用户澄清，则直接通过回调输出引导提示并结束本次响应（短路）。
     *
     * @param ctx 流式对话上下文
     * @return true 表示已处理歧义引导并短路；false 表示无需引导，继续后续流水线
     */
    private boolean handleGuidance(StreamChatContext ctx) {
        // 调用意图引导服务，结合改写后的问题与子问题意图判断是否需要歧义引导
        GuidanceDecision decision = guidanceService.detectAmbiguity(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getSubIntents()
        );

        // 如果不需要提示用户澄清，则返回 false，让流水线继续执行后续阶段
        if (!decision.isPrompt()) {
            return false;
        }

        // 需要引导：获取流式回调，输出引导提示内容，并完成本次流式响应（短路返回）
        StreamCallback callback = ctx.getCallback();
        callback.onContent(decision.getPrompt());
        callback.onComplete();
        return true;
    }

    /**
     * 处理纯系统指令场景（短路分支）。
     * <p>
     * 当所有子问题意图均为“仅系统”类型时，不需要执行检索，
     * 直接以系统提示词（可能使用自定义模板）流式返回 LLM 响应。
     *
     * @param ctx 流式对话上下文
     * @return true 表示已处理并短路；false 表示继续后续流水线
     */
    private boolean handleSystemOnly(StreamChatContext ctx) {
        // 1. 获取全部子问题意图
        List<SubQuestionIntent> subIntents = ctx.getSubIntents();

        // 2. 判断是否所有意图都标记为“仅系统”（无需检索）
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        // 2.1 只要存在非“仅系统”的意图，则不短路，继续后续检索流程
        if (!allSystemOnly) {
            return false;
        }

        // 3. 从所有意图的节点评分中提取第一个非空的自定义提示词模板，作为系统提示词
        String customPrompt = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .map(ns -> ns.getNode().getPromptTemplate())
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);

        // 4. 调用 LLM 流式响应（仅系统提示 + 用户问题，不携带检索上下文）
        StreamCancellationHandle handle = streamSystemResponse(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getHistory(),
                customPrompt,
                ctx.getCallback()
        );

        // 5. 将取消句柄绑定到当前任务，便于客户端取消或管理流式任务
        taskManager.bindHandle(ctx.getTaskId(), handle);

        // 6. 已处理并短路，返回 true 终止后续流水线
        return true;
    }

    private RetrievalContext retrieve(StreamChatContext ctx) {
        return retrievalEngine.retrieve(ctx.getSubIntents());
    }

    private boolean handleEmptyRetrieval(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        if (!retrievalCtx.isEmpty()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent("未检索到与问题相关的文档内容。");
        callback.onComplete();
        return true;
    }

    private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        // 聚合所有意图用于 prompt 规划
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(ctx.getSubIntents());

        // 检索完成后先下发文档级来源（面板/预览用，不参与 prompt）
        List<SourceRef> sources = sourcesAssembler.assemble(retrievalCtx.getIntentChunks());
        ctx.getCallback().onSources(sources);

        // 装配 grounding 片段随消息落库 供答案后推荐追问生成 grounding（不参与 prompt）
        ctx.getCallback().onGroundingChunks(groundingChunksAssembler.assemble(retrievalCtx.getIntentChunks()));

        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                retrievalCtx,
                mergedGroup,
                ctx.getHistory(),
                ctx.isDeepThinking(),
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
    }

    // ==================== LLM 响应 ====================

    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }
}

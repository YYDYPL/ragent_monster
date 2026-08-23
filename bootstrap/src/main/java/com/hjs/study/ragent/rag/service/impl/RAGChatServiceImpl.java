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

package com.hjs.study.ragent.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.hjs.study.ragent.framework.context.UserContext;
import com.hjs.study.ragent.infra.chat.StreamCallback;
import com.hjs.study.ragent.rag.service.RAGChatService;
import com.hjs.study.ragent.rag.service.handler.StreamCallbackFactory;
import com.hjs.study.ragent.rag.service.handler.StreamTaskManager;
import com.hjs.study.ragent.rag.service.pipeline.StreamChatContext;
import com.hjs.study.ragent.rag.service.pipeline.StreamChatPipeline;
import com.hjs.study.ragent.rag.service.ratelimit.ChatQueueLimiter;
import com.hjs.study.ragent.rag.trace.StreamChatTraceRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话服务默认实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final StreamChatPipeline chatPipeline;
    private final ChatQueueLimiter chatQueueLimiter;
    private final StreamCallbackFactory callbackFactory;
    private final StreamChatTraceRunner traceRunner;
    private final StreamTaskManager taskManager;

    /**
     * 流式对话入口：将用户问题排队执行，并以 SSE 方式向前端推送回答。
     *
     * <p>执行链路由三层包装组成：队列限流（{@link ChatQueueLimiter}）→
     * 链路追踪（{@link StreamChatTraceRunner}）→ RAG 流水线（{@link StreamChatPipeline}）。
     * 本方法只负责编排，真正的流式输出在队列回调的异步线程中完成。</p>
     *
     * @param question       用户问题
     * @param conversationId 会话 ID，为空时表示新会话（内部生成）
     * @param deepThinking   是否开启深度思考
     * @param emitter        SSE 连接，用于向客户端推送事件
     */
    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        // 前端未携带会话 ID 时视为新会话，用雪花 ID 兜底，保证后续链路始终有会话上下文
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        // 每次提问生成独立任务 ID，供取消（stopTask）与链路追踪（trace）定位本次执行
        String taskId = IdUtil.getSnowflakeNextIdStr();
        // 创建事件回调：把 pipeline 产出的内容 / 来源 / 完成等事件转换为 SSE 事件推给前端
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        // 排队获取执行权：并发过高时等待全局限流窗口，超时 / 拒绝则直接向前端回 REJECT 与 DONE
        chatQueueLimiter.enqueue(question, actualConversationId, emitter,
                // 拿到执行权后（异步线程）执行：先包一层链路追踪，再运行 RAG 流水线
                () -> traceRunner.run(question, actualConversationId, taskId, callback, traceAware -> {
                    // traceAware 是 trace 增强后的回调（首包时记录 TTFT、结束 / 异常时收尾 trace）
                    StreamChatContext ctx = StreamChatContext.builder()
                            .question(question)
                            .conversationId(actualConversationId)
                            .taskId(taskId)
                            // 兜底 null 值，避免 NPE
                            .deepThinking(Boolean.TRUE.equals(deepThinking))
                            // 当前登录用户，用于记忆加载与权限隔离
                            .userId(UserContext.getUserId())
                            .callback(traceAware)
                            .build();
                    // 执行 RAG 流水线：加载记忆 → 查询改写 → 意图解析 → 检索 → LLM 流式回答
                    chatPipeline.execute(ctx);
                }));
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}

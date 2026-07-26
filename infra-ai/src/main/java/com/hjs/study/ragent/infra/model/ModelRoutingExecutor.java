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

package com.hjs.study.ragent.infra.model;

import com.hjs.study.ragent.framework.errorcode.BaseErrorCode;
import com.hjs.study.ragent.framework.exception.RemoteException;
import com.hjs.study.ragent.infra.enums.ModelCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 模型路由执行器 —— 带故障转移（Fallback）的模型调用编排
 * <p>
 * 核心职责：在多个模型候选者之间按优先级顺序尝试调用，任一成功即返回，
 * 全部失败则抛出异常。调用过程中与 {@link ModelHealthStore} 协作，
 * 自动跳过不健康的节点并记录调用结果以更新健康状态。
 * <p>
 * 典型协作流程（与 {@link ModelSelector} 配合）：
 * <ol>
 *   <li>{@link ModelSelector} 按档位/优先级产出有序候选列表 {@code List<ModelTarget>}</li>
 *   <li>本执行器遍历候选列表，逐个尝试调用</li>
 *   <li>每次调用前后通知 {@link ModelHealthStore} 更新断路器状态</li>
 *   <li>首个成功的候选结果直接返回，后续候选不再尝试</li>
 *   <li>全部候选失败则抛出 {@link RemoteException}</li>
 * </ol>
 *
 * @see ModelSelector 模型选择器（产出候选列表）
 * @see ModelHealthStore 健康状态存储器（断路器，调用前后更新）
 * @see ModelCaller 模型调用器函数式接口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRoutingExecutor {

    private final ModelHealthStore healthStore;

    /**
     * 带故障转移的模型调用执行
     * <p>
     * 按 {@code targets} 列表顺序依次尝试调用，执行流程：
     * <ol>
     *   <li>遍历候选列表中的每个 {@link ModelTarget}</li>
     *   <li>通过 {@code clientResolver} 解析出对应的客户端实例，若为 null 则跳过</li>
     *   <li>调用 {@link ModelHealthStore#allowCall} 检查断路器是否放行，熔断中则跳过</li>
     *   <li>通过 {@code caller} 执行实际模型调用</li>
     *   <li>调用成功 → 通知 {@link ModelHealthStore#markSuccess} 并返回结果</li>
     *   <li>调用失败 → 通知 {@link ModelHealthStore#markFailure}，记录异常，继续尝试下一个候选</li>
     *   <li>全部候选失败 → 抛出 {@link RemoteException}</li>
     * </ol>
     *
     * @param capability     模型能力类型（chat / embedding / rerank / vlm），用于日志和异常消息标识
     * @param targets        按优先级排序的候选目标列表（通常由 {@link ModelSelector} 产出）
     * @param clientResolver 客户端解析函数：根据 ModelTarget 解析出具体的 SDK 客户端实例
     * @param caller         模型调用器：封装实际的 API 调用逻辑
     * @param <C>            客户端类型（如 DashScope 客户端、OpenAI 客户端等）
     * @param <T>            返回值类型
     * @return 首个成功候选的调用结果
     * @throws RemoteException 当所有候选均调用失败时抛出
     */
    public <C, T> T executeWithFallback(
            ModelCapability capability,
            List<ModelTarget> targets,
            Function<ModelTarget, C> clientResolver,
            ModelCaller<C, T> caller) {
        String label = capability.getDisplayName();
        // 候选列表为空：配置缺失或所有候选均被过滤（未启用/不健康/不支持思考）
        if (targets == null || targets.isEmpty()) {
            throw new RemoteException("No " + label + " model candidates available");
        }

        Throwable last = null; // 记录最后一次失败异常，用于最终异常消息
        for (ModelTarget target : targets) {
            // 步骤1：解析客户端实例
            C client = clientResolver.apply(target);
            if (client == null) {
                log.warn("{} provider client missing: provider={}, modelId={}", label, target.candidate().getProvider(), target.id());
                continue; // 客户端缺失 → 跳过，尝试下一个候选
            }
            // 步骤2：断路器准入检查（熔断/探测中则跳过）
            if (!healthStore.allowCall(target.id())) {
                continue;
            }

            try {
                // 步骤3：执行实际调用
                T response = caller.call(client, target);
                // 步骤4：成功 → 标记健康并返回
                healthStore.markSuccess(target.id());
                return response;
            } catch (Exception e) {
                // 步骤5：失败 → 标记故障，记录异常，继续尝试下一个
                last = e;
                healthStore.markFailure(target.id());
                log.warn("{} model failed, fallback to next. modelId={}, provider={}", label, target.id(), target.candidate().getProvider(), e);
            }
        }

        // 全部候选失败：抛出统一异常
        throw new RemoteException(
                "All " + label + " model candidates failed: " + (last == null ? "unknown" : last.getMessage()),
                last,
                BaseErrorCode.REMOTE_ERROR
        );
    }
}

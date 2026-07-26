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

import cn.hutool.core.util.StrUtil;
import com.hjs.study.ragent.infra.config.AIModelProperties;
import com.hjs.study.ragent.infra.enums.ModelProvider;
import com.hjs.study.ragent.infra.enums.Tier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 模型选择器
 * 负责根据配置和当前需求选择合适的模型候选列表
 * <p>
 * chat 组走档位机制：任务 → 档位（tier）→ 档位内有序候选；
 * embedding/rerank/vlm 组走 defaultModel + priority 的传统排序
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSelector {

    private final AIModelProperties properties;
    private final ModelHealthStore healthStore;

    /**
     * 选择 chat 候选（默认档位）
     * <p>
     * 档位解析：深度思考走 deepThinkingTier，否则兜底 defaultTier
     */
    public List<ModelTarget> selectChatCandidates(boolean thinking) {
        return selectChatCandidates(thinking, null, null);
    }

    /**
     * 选择 chat 候选，并按显式档位覆盖
     * <p>
     * override 语义：非空时使用该档位（想要更快/更强模型时由调用点传入），为空走默认解析
     */
    public List<ModelTarget> selectChatCandidates(boolean thinking, Tier override) {
        return selectChatCandidates(thinking, override, null);
    }

    /**
     * 选择 chat 候选，按显式档位覆盖，并将 preferred 模型置于队首
     * <p>
     * preferred 语义：优先该模型，失败后回退到解析出的档位的其余候选
     *
     * @param override         档位覆盖，为空走默认解析（深度思考→deepThinkingTier，否则 defaultTier）
     * @param preferredModelId 优先模型 id，为空时等同于无 preferred
     */
    public List<ModelTarget> selectChatCandidates(boolean thinking, Tier override, String preferredModelId) {
        AIModelProperties.ModelGroup group = properties.getChat();
        if (group == null) {
            return List.of();
        }
        String tierName = resolveTierName(group, thinking, override);
        // 用户请求思考时，路由与 preferred 都必须过滤掉不支持思考的模型
        return buildTierTargets(group, tierName, preferredModelId, thinking);
    }

    public List<ModelTarget> selectEmbeddingCandidates() {
        return selectCandidates(properties.getEmbedding());
    }

    public List<ModelTarget> selectRerankCandidates() {
        return selectCandidates(properties.getRerank());
    }

    public List<ModelTarget> selectVlmCandidates() {
        return selectCandidates(properties.getVlm());
    }

    // ==================== chat：档位机制 ====================

    /**
     * 解析档位名称（决定本次请求使用哪个档位的候选列表）
     * <p>
     * 优先级：思考请求的 deepThinkingTier > 显式覆盖 > 默认档位
     * <ul>
     *   <li>思考请求（thinking=true）且配置了 deepThinkingTier → 使用深度思考专用档位</li>
     *   <li>调用方显式传入 override Tier → 使用该枚举对应的档位 key</li>
     *   <li>以上都不满足 → 使用 defaultTier</li>
     * </ul>
     *
     * @param group    模型组配置
     * @param thinking 是否为深度思考请求
     * @param override 显式档位覆盖，可为 null
     * @return 档位名称（配置 key）
     */
    private String resolveTierName(AIModelProperties.ModelGroup group, boolean thinking, Tier override) {
        if (thinking && StrUtil.isNotBlank(group.getDeepThinkingTier())) {
            return group.getDeepThinkingTier();
        }
        if (override != null) {
            return override.getKey();
        }
        return group.getDefaultTier();
    }

    /**
     * 按档位构造有序候选：preferred 置队首，随后拼接档位候选（去重），逐个过滤未启用/不健康/未登记
     * <p>
     * requireThinking 为 true 时额外剔除 supportsThinking!=true 的候选（含 preferred），
     * 避免把思考请求路由到无法思考的模型；命中的档位超时预算随每个 target 下沉
     *
     * @param requireThinking 是否要求候选支持思考链
     */
    private List<ModelTarget> buildTierTargets(AIModelProperties.ModelGroup group, String tierName,
                                                  String preferredModelId, boolean requireThinking) {
        Map<String, AIModelProperties.ModelCandidate> registry = buildRegistry(group.getCandidates());

        List<String> orderedIds = new ArrayList<>();
        if (StrUtil.isNotBlank(preferredModelId)) {
            AIModelProperties.ModelCandidate preferred = registry.get(preferredModelId);
            if (preferred == null) {
                log.warn("Chat preferred 模型未在注册表登记，忽略并回退档位候选: preferredModelId={}", preferredModelId);
            } else if (requireThinking && !supportsThinking(preferred)) {
                log.warn("Chat preferred 模型不支持思考，思考请求下忽略: preferredModelId={}", preferredModelId);
            } else {
                orderedIds.add(preferredModelId);
            }
        }

        AIModelProperties.TierConfig tier = group.getTiers() == null ? null : group.getTiers().get(tierName);
        Long timeoutMs = tier == null ? null : tier.getTimeoutMs();
        if (tier == null) {
            log.warn("Chat 档位配置缺失: tier={}", tierName);
        } else {
            for (String id : tier.getCandidates()) {
                if (!orderedIds.contains(id)) {
                    orderedIds.add(id);
                }
            }
        }

        Map<String, AIModelProperties.ProviderConfig> providers = properties.getProviders();
        List<ModelTarget> targets = new ArrayList<>();
        for (String id : orderedIds) {
            AIModelProperties.ModelCandidate candidate = registry.get(id);
            if (candidate == null) {
                log.warn("Chat 档位候选 id 未在注册表登记: id={}, tier={}", id, tierName);
                continue;
            }
            if (Boolean.FALSE.equals(candidate.getEnabled())) {
                continue;
            }
            if (requireThinking && !supportsThinking(candidate)) {
                continue;
            }
            ModelTarget target = buildModelTarget(candidate, providers, timeoutMs);
            if (target != null) {
                targets.add(target);
            }
        }
        return targets;
    }

    /**
     * 判断候选模型是否启用了思考链（Chain-of-Thought）能力
     * <p>
     * 仅当配置中 {@code supportsThinking} 明确为 {@code true} 时才返回 true，
     * null 或 false 均视为不支持，确保思考请求不会被错误路由到普通模型
     */
    private boolean supportsThinking(AIModelProperties.ModelCandidate candidate) {
        return Boolean.TRUE.equals(candidate.getSupportsThinking());
    }

    /**
     * 构建候选模型注册表（id → 候选配置）
     * <p>
     * 以配置中的显式 id 或 "provider::model" 拼接值作为 key，
     * 使得档位配置中的 {@code candidates} 列表可以通过 id 引用到具体候选。
     * 使用 LinkedHashMap 保持插入顺序
     */
    private Map<String, AIModelProperties.ModelCandidate> buildRegistry(List<AIModelProperties.ModelCandidate> candidates) {
        Map<String, AIModelProperties.ModelCandidate> registry = new LinkedHashMap<>();
        if (candidates == null) {
            return registry;
        }
        for (AIModelProperties.ModelCandidate candidate : candidates) {
            if (candidate != null) {
                registry.put(resolveId(candidate), candidate);
            }
        }
        return registry;
    }

    // ==================== embedding/rerank/vlm：defaultModel + priority ====================

    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group) {
        if (group == null || group.getCandidates() == null) {
            return List.of();
        }
        List<AIModelProperties.ModelCandidate> orderedCandidates =
                filterAndSortCandidates(group.getCandidates(), group.getDefaultModel());
        return buildAvailableTargets(orderedCandidates);
    }

    /**
     * 过滤并排序候选模型列表（用于 embedding/rerank/vlm 等非 chat 场景）
     * <p>
     * 排序规则（多级排序）：
     * <ol>
     *   <li><b>首选模型置顶</b>：id 匹配 {@code firstChoiceModelId} 的候选排在最前面</li>
     *   <li><b>priority 升序</b>：数值越小优先级越高，null 值排在最后</li>
     *   <li><b>id 字典序</b>：同 priority 时按 id 排序，null 值排在最后</li>
     * </ol>
     * <p>
     * 过滤规则：排除 null 候选和 {@code enabled=false} 的禁用候选
     *
     * @param candidates        原始候选列表（来自配置）
     * @param firstChoiceModelId 首选模型 id（如 defaultModel），匹配者置顶
     * @return 过滤并排序后的候选列表
     */
    private List<AIModelProperties.ModelCandidate> filterAndSortCandidates(List<AIModelProperties.ModelCandidate> candidates,
                                                                           String firstChoiceModelId) {
        return candidates.stream()
                .filter(c -> c != null && !Boolean.FALSE.equals(c.getEnabled()))
                .sorted(Comparator
                        .comparing((AIModelProperties.ModelCandidate c) ->
                                !Objects.equals(resolveId(c), firstChoiceModelId))
                        .thenComparing(AIModelProperties.ModelCandidate::getPriority,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(AIModelProperties.ModelCandidate::getId,
                                Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
    }

    /**
     * 将候选模型列表转换为 {@link ModelTarget} 列表（用于 embedding/rerank/vlm 场景）
     * <p>
     * 与 chat 档位机制不同，这些场景无档位预算概念，超时走 HTTP 客户端默认配置。
     * 转换过程中会调用 {@link #buildModelTarget}，自动跳过不健康（断路器 OPEN）和 provider 缺失的候选
     *
     * @param candidates 过滤排序后的候选列表
     * @return 可用的 ModelTarget 列表（已剔除不健康节点）
     */
    private List<ModelTarget> buildAvailableTargets(List<AIModelProperties.ModelCandidate> candidates) {
        Map<String, AIModelProperties.ProviderConfig> providers = properties.getProviders();

        // embedding/rerank/vlm 无档位预算，超时走 HTTP 客户端默认
        return candidates.stream()
                .map(candidate -> buildModelTarget(candidate, providers, null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ==================== 通用 ====================

    /**
     * 构造单个 {@link ModelTarget}
     * <p>
     * 执行以下校验，任一不通过则返回 null（由调用方过滤）：
     * <ul>
     *   <li>断路器检查：通过 {@link ModelHealthStore#isUnavailable} 判断，熔断中则跳过</li>
     *   <li>Provider 校验：非 NOOP provider 时必须在 providers 配置中存在，否则跳过并告警</li>
     * </ul>
     *
     * @param candidate 候选模型配置
     * @param providers 全局 provider 配置表
     * @param timeoutMs 档位超时预算（chat 场景传入，embedding/rerank/vlm 传 null）
     * @return 构造好的 ModelTarget，校验不通过返回 null
     */
    private ModelTarget buildModelTarget(AIModelProperties.ModelCandidate candidate,
                                         Map<String, AIModelProperties.ProviderConfig> providers,
                                         Long timeoutMs) {
        String modelId = resolveId(candidate);

        if (healthStore.isUnavailable(modelId)) {
            return null;
        }

        AIModelProperties.ProviderConfig provider = providers.get(candidate.getProvider());
        if (provider == null && !ModelProvider.NOOP.matches(candidate.getProvider())) {
            log.warn("Provider配置缺失: provider={}, modelId={}", candidate.getProvider(), modelId);
            return null;
        }

        return new ModelTarget(modelId, candidate, provider, timeoutMs);
    }

    /**
     * 解析候选模型的唯一标识 id
     * <p>
     * 优先使用配置中显式指定的 {@code id} 字段；若未配置，则回退为
     * {@code "provider::model"} 拼接形式（如 {@code "dashscope::qwen-plus"}）。
     * 此 id 同时用于：
     * <ul>
     *   <li>档位候选列表中的引用（tier.candidates 通过此 id 引用注册表中的候选）</li>
     *   <li>{@link ModelHealthStore} 中健康状态的 key</li>
     *   <li>日志中模型身份标识</li>
     * </ul>
     *
     * @param candidate 候选模型配置
     * @return 模型唯一标识 id
     */
    private String resolveId(AIModelProperties.ModelCandidate candidate) {
        if (StrUtil.isNotBlank(candidate.getId())) {
            return candidate.getId();
        }
        return String.format("%s::%s",
                Objects.toString(candidate.getProvider(), "unknown"),
                Objects.toString(candidate.getModel(), "unknown"));
    }
}

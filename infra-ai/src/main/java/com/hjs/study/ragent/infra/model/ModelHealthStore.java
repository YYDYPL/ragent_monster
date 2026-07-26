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

import com.hjs.study.ragent.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型健康状态存储器 —— 基于断路器的模型可用性管理
 * <p>
 * 为每个模型独立维护一个三态断路器（CLOSED → OPEN → HALF_OPEN），
 * 实现"快速失败/自动恢复"的弹性调用模式：
 * <ul>
 *   <li><b>CLOSED（闭合/正常）</b>：模型健康，请求正常放行。连续失败达到阈值后熔断，进入 OPEN</li>
 *   <li><b>OPEN（断开/熔断）</b>：模型被认为不可用，拒绝所有调用。经过冷却期（openDurationMs）后自动切换为 HALF_OPEN 进行探测</li>
 *   <li><b>HALF_OPEN（半开/探测）</b>：仅允许一个试探性请求通过。
 *       成功→恢复为 CLOSED；失败→立即重新熔断回到 OPEN</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>所有操作通过 {@link ConcurrentHashMap#compute} 保证原子性，避免并发竞态</li>
 *   <li>HALF_OPEN 阶段通过 {@code halfOpenInFlight} 标志确保同一时刻只有一个探测请求</li>
 *   <li>与 {@link ModelSelector} 协作：选择器中调用 {@link #isUnavailable} 预先剔除不健康节点</li>
 * </ul>
 *
 * @see ModelSelector 模型选择器（在选择候选时读取健康状态）
 */
@Component
@RequiredArgsConstructor
public class ModelHealthStore {

    private final AIModelProperties properties;

    /**
     * 模型 ID → 健康状态的映射表，使用 ConcurrentHashMap 保证并发安全
     */
    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();

    /**
     * 判断指定模型当前是否不可用（用于候选列表预过滤）
     * <p>
     * 两种不可用场景：
     * <ol>
     *   <li>断路器处于 OPEN 状态且冷却时间未到</li>
     *   <li>断路器处于 HALF_OPEN 状态且已有探测请求在进行中（本请求不可再进入）</li>
     * </ol>
     * <p>
     * 注意：该方法只做<em>瞬时快照</em>判断，不修改状态（与 {@link #allowCall} 不同）
     *
     * @param id 模型唯一标识
     * @return true 表示不可用，false 表示可用或首次访问（无记录默认可用）
     */
    public boolean isUnavailable(String id) {
        ModelHealth health = healthById.get(id);
        if (health == null) {
            return false; // 无记录 = 从未调用过，视为可用
        }
        // OPEN 且冷却时间未到 → 不可用
        if (health.state == State.OPEN && health.openUntil > System.currentTimeMillis()) {
            return true;
        }
        // HALF_OPEN 且已有探测请求进行中 → 不可用（只允许一个探测）
        return health.state == State.HALF_OPEN && health.halfOpenInFlight;
    }

    /**
     * 尝试获取调用许可（原子操作，会修改断路器状态）
     * <p>
     * 这是调用前的"准入判断"，根据当前断路器状态决定是否放行：
     * <ul>
     *   <li><b>首次访问</b>（无历史记录）：创建 CLOSED 状态记录，放行</li>
     *   <li><b>CLOSED</b>：正常放行，不修改状态</li>
     *   <li><b>OPEN</b>：
     *     <ul>
     *       <li>冷却未到期 → 拒绝</li>
     *       <li>冷却已到期 → 切换为 HALF_OPEN 并放行（作为探测请求）</li>
     *     </ul>
     *   </li>
     *   <li><b>HALF_OPEN</b>：
     *     <ul>
     *       <li>已有探测请求进行中 → 拒绝</li>
     *       <li>无探测请求 → 设置探测标志并放行</li>
     *     </ul>
     *   </li>
     * </ul>
     * <p>
     * 所有判断和状态变更在 {@link ConcurrentHashMap#compute} 的原子回调中完成，
     * 保证同一 ID 的并发访问串行化
     *
     * @param id 模型唯一标识
     * @return true 允许本次调用，false 拒绝（被熔断或探测已在进行中）
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean allowCall(String id) {
        if (id == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        AtomicBoolean allowed = new AtomicBoolean(false);
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth(); // 首次访问，初始化为 CLOSED
            }
            // --- OPEN 状态处理 ---
            if (v.state == State.OPEN) {
                if (v.openUntil > now) {
                    // 冷却时间未到，拒绝调用
                    return v;
                }
                // 冷却时间已到，切换为 HALF_OPEN 并放行一个探测请求
                v.state = State.HALF_OPEN;
                v.halfOpenInFlight = true;
                allowed.set(true);
                return v;
            }
            // --- HALF_OPEN 状态处理 ---
            if (v.state == State.HALF_OPEN) {
                if (v.halfOpenInFlight) {
                    // 已有探测请求在进行中，拒绝
                    return v;
                }
                // 无探测请求，由本请求进行探测
                v.halfOpenInFlight = true;
                allowed.set(true);
                return v;
            }
            // --- CLOSED 状态：正常放行 ---
            allowed.set(true);
            return v;
        });
        return allowed.get();
    }

    /**
     * 标记一次成功调用，将断路器重置为健康状态
     * <p>
     * 无论当前处于何种状态，成功后一律恢复为 CLOSED，清零故障计数和冷却时间。
     * 这是 HALF_OPEN 探测成功的唯一出口
     *
     * @param id 模型唯一标识
     */
    public void markSuccess(String id) {
        if (id == null) {
            return;
        }
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                return new ModelHealth();
            }
            // 恢复为完全健康状态
            v.state = State.CLOSED;
            v.consecutiveFailures = 0;  // 重置连续失败计数
            v.openUntil = 0L;           // 清除冷却截止时间
            v.halfOpenInFlight = false; // 清除探测标志
            return v;
        });
    }

    /**
     * 标记一次失败调用，推进断路器状态
     * <p>
     * 失败处理逻辑取决于当前状态：
     * <ul>
     *   <li><b>HALF_OPEN 探测失败</b>：立即重新熔断回到 OPEN，重置冷却计时（惩罚性：不给第二次探测机会）</li>
     *   <li><b>CLOSED 或其他状态</b>：累加连续失败计数，达到阈值后熔断进入 OPEN</li>
     * </ul>
     * <p>
     * 熔断后的冷却时长由配置项 {@code ai.model.selection.open-duration-ms} 控制，
     * 失败阈值由 {@code ai.model.selection.failure-threshold} 控制
     *
     * @param id 模型唯一标识
     */
    public void markFailure(String id) {
        if (id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            // HALF_OPEN 探测失败：立即重新熔断（没有第二次机会）
            if (v.state == State.HALF_OPEN) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
                v.halfOpenInFlight = false;
                return v;
            }
            // CLOSED 状态：累加失败计数
            v.consecutiveFailures++;
            // 连续失败达到阈值 → 熔断
            if (v.consecutiveFailures >= properties.getSelection().getFailureThreshold()) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0; // 熔断后重置计数，等待下一次周期
            }
            return v;
        });
    }

    /**
     * 单个模型的健康状态快照（内部数据结构）
     * <p>
     * 所有字段的读写均在 {@link ConcurrentHashMap#compute} 原子回调内完成，
     * 不额外加锁，依赖 CHM 的分段锁保证线程安全
     */
    private static class ModelHealth {
        /** 连续失败次数（CLOSED 状态下累加，达到阈值触发熔断） */
        private int consecutiveFailures;
        /** 熔断截止时间戳（毫秒），在此之前拒绝所有调用；0 表示未熔断 */
        private long openUntil;
        /** HALF_OPEN 状态下是否已有探测请求在进行中（防止并发探测） */
        private boolean halfOpenInFlight;
        /** 当前断路器状态 */
        private State state;

        private ModelHealth() {
            this.consecutiveFailures = 0;
            this.openUntil = 0L;
            this.halfOpenInFlight = false;
            this.state = State.CLOSED; // 初始状态：健康
        }
    }

    /**
     * 断路器三态枚举
     * <ul>
     *   <li>{@link #CLOSED} —— 闭合：正常通行，连续失败达阈值后切换到 OPEN</li>
     *   <li>{@link #OPEN} —— 断开：拒绝所有请求，冷却期满后切换到 HALF_OPEN</li>
     *   <li>{@link #HALF_OPEN} —— 半开：允许一个探测请求，成功→CLOSED，失败→OPEN</li>
     * </ul>
     */
    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}

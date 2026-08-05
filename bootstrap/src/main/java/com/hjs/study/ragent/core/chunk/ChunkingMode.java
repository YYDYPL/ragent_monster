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

package com.hjs.study.ragent.core.chunk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.Map;

/**
 * 分块模式枚举，同时承担外部值映射和配置对象工厂两项职责。
 * <p>
 * {@link #value} 是数据库、JSON 和前端使用的稳定小写 snake_case 值；枚举名只属于 Java
 * 内部。每个枚举常量通过“常量特定类体”实现配置构造，确保默认值与实际解析逻辑集中维护。
 * <p>
 * 注意：模式只决定没有结构化 Block 时采用哪种纯文本算法。只要 blocks 非空，统一入口就会
 * 转入 block-aware 链路，此时这些配置只提供 maxChars、overlap 等通用预算。
 */
@Getter
public enum ChunkingMode {

    /**
     * 固定字符窗口切分，并在有限回看范围内优先对齐换行或句末标点。
     */
    FIXED_SIZE("fixed_size", "固定大小", true) {
        @Override
        public ChunkingOptions createOptions(Map<String, Object> config) {
            return new FixedSizeOptions(
                    toInt(config, "chunkSize", 512),
                    toInt(config, "overlapSize", 128));
        }

        @Override
        public ChunkingOptions createDefaultOptions(Integer targetSize, Integer overlapSize) {
            return new FixedSizeOptions(
                    targetSize != null ? targetSize : 512,
                    overlapSize != null ? overlapSize : 128);
        }
    },

    /**
     * Markdown 友好的文本边界切分：识别标题、围栏代码、原子图片/链接与自然段。
     */
    STRUCTURE_AWARE("structure_aware", "语义感知（Markdown友好）", true) {
        @Override
        public ChunkingOptions createOptions(Map<String, Object> config) {
            return new TextBoundaryOptions(
                    toInt(config, "targetChars", 1400),
                    toInt(config, "overlapChars", 0),
                    toInt(config, "maxChars", 1800),
                    toInt(config, "minChars", 600));
        }

        @Override
        public ChunkingOptions createDefaultOptions(Integer targetSize, Integer overlapSize) {
            return new TextBoundaryOptions(
                    targetSize != null ? targetSize : 1400,
                    overlapSize != null ? overlapSize : 0,
                    1800,
                    600);
        }
    };

    /** 对外序列化和数据库存储值。 */
    private final String value;

    /** 管理端展示名称。 */
    private final String label;

    /** 是否允许出现在可选策略列表中。 */
    private final boolean visible;

    ChunkingMode(String value, String label, boolean visible) {
        this.value = value;
        this.label = label;
        this.visible = visible;
    }

    /**
     * 获取该模式的默认配置参数（用于 API 返回和配置校验）。
     * <p>
     * 通过空 Map 调用 createOptions，避免另写一份容易漂移的默认值表。
     *
     * @return 不可修改的默认配置 Map
     */
    public Map<String, Integer> getDefaultConfig() {
        return createOptions(Map.of()).toConfigMap();
    }

    /**
     * 从 DB/JSON 存储的原始配置构建类型安全的 ChunkingOptions
     *
     * 非法数字字符串会宽松回退默认值，不会在此抛配置异常。
     *
     * @param config 原始配置 Map（来自 DB JSON 解析），可为 null
     * @return 与当前模式匹配的强类型配置
     */
    public abstract ChunkingOptions createOptions(Map<String, Object> config);

    /**
     * 从通用参数构建 ChunkingOptions（供 ChunkerNode 等不感知具体键名的调用方使用）
     *
     * @param targetSize  通用的目标块大小，null 时使用默认值
     * @param overlapSize 通用的重叠大小，null 时使用默认值
     * @return 与当前模式匹配的强类型配置
     */
    public abstract ChunkingOptions createDefaultOptions(Integer targetSize, Integer overlapSize);

    // ============ 解析工具 ============

    /**
     * 从弱类型 Map 宽松读取整数，兼容 JSON Number 和数字字符串。
     * <p>
     * 缺失、空串、非数字以及其他对象类型均回退 defaultValue；范围修正由具体算法处理。
     */
    static int toInt(Map<String, Object> config, String key, int defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number num) return num.intValue();
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 反序列化数据库或 JSON 中的策略值。
     * <p>
     * 同时接受外部 value、Java 枚举名以及用连字符代替下划线的形式；未知值显式失败，避免静默
     * 使用错误策略。
     *
     * @param value 外部策略文本；null 原样返回 null，由上层决定默认值
     * @return 匹配到的模式
     * @throws IllegalArgumentException 未知非空策略值
     */
    @JsonCreator
    public static ChunkingMode fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        for (ChunkingMode strategy : values()) {
            if (strategy.value.equalsIgnoreCase(normalized) || strategy.name().equalsIgnoreCase(normalized)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("Unknown chunk strategy: " + value);
    }

    /** 将用户输入去首尾空白、转小写，并统一连字符与下划线。 */
    private static String normalize(String value) {
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * Jackson 序列化时输出稳定 value，而不是枚举常量名。
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}

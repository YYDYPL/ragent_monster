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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * legacy 纯文本策略注册表与查询入口。
 * <p>
 * Spring 通过构造器注入所有 {@link ChunkingStrategy} Bean，{@link #init()} 在启动阶段按
 * {@link ChunkingStrategy#getType()} 建立 EnumMap，并主动拒绝重复实现。运行阶段只读取不可变
 * Map，不做锁竞争。
 * <p>
 * 工厂不管理 block-aware Chunker；后者是固定的强类型分发链，由
 * {@link com.hjs.study.ragent.core.chunk.blockaware.BlockAwareChunkerDispatcher} 直接注入。
 */
@Component
@RequiredArgsConstructor
public class ChunkingStrategyFactory {

    /** Spring 容器发现的全部纯文本策略，仅在初始化时遍历。 */
    private final List<ChunkingStrategy> chunkingStrategies;

    /**
     * 初始化完成后的不可变注册表。volatile 保证若有线程在 Bean 初始化边界附近读取，能看到完整
     * Map 引用；正常 Spring 生命周期下业务请求只会看到初始化后的快照。
     */
    private volatile Map<ChunkingMode, ChunkingStrategy> strategies = Map.of();

    /**
     * 根据策略枚举获取对应的切分策略实现
     *
     * @param type 切分策略类型
     * @return 可选策略；type 为 null 或未注册时为空
     */
    public Optional<ChunkingStrategy> findStrategy(ChunkingMode type) {
        if (type == null) return Optional.empty();
        return Optional.ofNullable(strategies.get(type));
    }

    /**
     * 获取指定类型的切分策略，如果不存在则抛出异常
     *
     * @param type 切分策略类型
     * @return {@link ChunkingStrategy} 切分策略实现类
     * @throws IllegalArgumentException 如果指定的策略类型不存在
     */
    public ChunkingStrategy requireStrategy(ChunkingMode type) {
        Objects.requireNonNull(type, "ChunkingMode type must not be null");
        return findStrategy(type)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + type));
    }

    /**
     * 构建不可变策略索引。
     * <p>
     * 同一 ChunkingMode 出现两个 Bean 通常意味着扩展配置错误，因此选择 fail-fast，而不是依赖
     * 注入顺序随机覆盖。
     *
     * @throws IllegalStateException 存在重复模式实现
     */
    @PostConstruct
    public void init() {
        Map<ChunkingMode, ChunkingStrategy> map = new EnumMap<>(ChunkingMode.class);

        chunkingStrategies.forEach(s -> {
            ChunkingStrategy old = map.put(s.getType(), s);
            if (old != null) {
                throw new IllegalStateException(
                        "Duplicate ChunkingStrategy for type: " + s.getType()
                                + " (" + old.getClass().getName() + " vs " + s.getClass().getName() + ")"
                );
            }
        });

        this.strategies = Map.copyOf(map);
    }
}

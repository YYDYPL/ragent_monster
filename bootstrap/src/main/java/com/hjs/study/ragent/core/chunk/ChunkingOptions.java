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

import java.util.Map;

/**
 * 纯文本分块策略使用的类型安全配置根接口。
 * <p>
 * 数据库和前端传入的是弱类型 JSON Map，进入核心算法前由 {@link ChunkingMode} 转换成具体
 * record。这样策略实现只处理明确的数字字段，不需要在热路径中反复读取魔法字符串。
 * <p>
 * 该接口只服务于 legacy 纯文本策略；当 Parser 已产出结构化 Block 时，
 * {@link StructuredChunkingService} 会把其中的通用体量参数再投影为
 * {@link com.hjs.study.ragent.core.chunk.blockaware.BlockChunkConfig}。
 * sealed 限定了当前合法配置类型，新增策略时必须同步更新 permits、ChunkingMode 和工厂注册。
 *
 * @see FixedSizeOptions 固定大小切分配置
 * @see TextBoundaryOptions 文本边界切分配置（结构感知等）
 */
public sealed interface ChunkingOptions permits FixedSizeOptions, TextBoundaryOptions {

    /**
     * 将强类型配置导出为只读 Map。
     * <p>
     * 该桥接主要用于 API 展示默认值、整篇不分块哨兵判断，以及从 legacy 配置派生
     * block-aware 预算。返回键名是外部配置契约，重命名会影响已保存的 JSON。
     *
     * @return 参数名到整数值的映射；当前 record 实现使用 {@link Map#of}，因此不可修改
     */
    Map<String, Integer> toConfigMap();
}

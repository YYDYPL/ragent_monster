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

/**
 * 解析阶段的强类型中间表示（Intermediate Representation，IR）。
 *
 * <p>{@link com.hjs.study.ragent.core.parser.model.ParsedDocument} 是文档级容器，
 * {@link com.hjs.study.ragent.core.parser.model.Block} 是内容级密封接口。Block 的排列顺序就是
 * 原文阅读顺序，下游分块器依赖这个顺序恢复章节上下文，因此解析器不应随意排序。
 *
 * <p>这些类型采用 record，主要表达不可变数据契约；不过 record 只保证字段引用不可重新赋值，
 * 并不会自动复制传入的 {@link java.util.List} 或 {@link java.util.Map}。调用方若把可变集合传入
 * record，仍应避免在交付下游后继续修改。
 */
package com.hjs.study.ragent.core.parser.model;

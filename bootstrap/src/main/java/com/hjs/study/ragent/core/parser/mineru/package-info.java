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
 * MinerU SaaS 文档解析适配层。
 *
 * <p>完整调用链是：
 *
 * <pre>
 * MinerUDocumentParser
 *   ├─ 获取 Redisson 分布式许可
 *   ├─ MinerUClient 申请预签名上传地址
 *   ├─ MinerUClient 上传原文件
 *   ├─ MinerUPollingExecutor 定时查询任务
 *   ├─ MinerUClient 下载结果 ZIP
 *   └─ MinerUResultUnpacker 解析 Markdown、上传图片并生成 Block
 * </pre>
 *
 * <p>这里同时跨越外部 HTTP、Redis 许可、定时调度、ZIP 解包和对象存储。阅读代码时应分别追踪
 * “解析任务许可”“MinerU batchId”“业务 documentId”三个标识：它们解决的是限流、外部任务
 * 关联和内部资产归属三个不同问题，不能互相替代。
 */
package com.hjs.study.ragent.core.parser.mineru;

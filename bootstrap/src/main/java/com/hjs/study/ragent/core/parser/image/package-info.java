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
 * 独立图片文件的多模态解析实现。
 *
 * <p>图片本身不能直接形成有意义的文本向量，因此这里同时产生两类数据：
 *
 * <ul>
 *   <li>VLM 描述：作为可检索、可喂给 LLM 的语义文本；</li>
 *   <li>对象存储 URL：作为最终回答中的图片展示资产。</li>
 * </ul>
 *
 * <p>两者被封装在同一个 ImageBlock 中。当前实现采用失败即终止策略：如果 VLM 没有生成描述，
 * 不会只保存图片链接，因为这种 Chunk 几乎无法通过文本检索召回。
 */
package com.hjs.study.ragent.core.parser.image;

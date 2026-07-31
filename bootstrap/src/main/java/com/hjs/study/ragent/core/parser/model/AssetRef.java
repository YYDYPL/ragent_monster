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

package com.hjs.study.ragent.core.parser.model;

/**
 * 资产引用：指向对象存储中已上传的二进制资源（当前主要是图片）。
 * <p>
 * 由 MinerUResultUnpacker / Excel 等解析器在上传资产后构造，
 * 挂在 ImageBlock 上，并最终回填到 VectorChunk.assets，供来源展示和资产追踪使用。
 * 该对象只保存引用，不持有文件字节，也不负责访问权限与 URL 续期。
 *
 * @param publicUrl     浏览器可访问的预览 URL；当前资产桶配置为公共读
 * @param mime          MIME 类型，如 image/png，供前端和下游判断资源类型
 * @param sourceBlockId 关联的 Block.id()，用于把资产反查到产生它的内容块
 */
public record AssetRef(String publicUrl, String mime, String sourceBlockId) {
}

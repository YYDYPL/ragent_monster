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

package com.hjs.study.ragent.core.parser.mineru;

/**
 * MinerU 申请上传链接接口的返回凭证（单文件）。
 * <p>
 * {@link MinerUClient#requestUpload} 返回:batchId 用于后续轮询,
 * uploadUrl 是 MinerU OSS 的预签名 PUT 链接，具有时效性；它本身等同临时写入凭据，不应写入
 * 普通日志、前端响应或长期持久化。上传完成后，后续轮询只需要 batchId。
 *
 * @param batchId   MinerU 分配的 batch_id，供后续轮询关联任务
 * @param uploadUrl 文件上传目标 URL，PUT 原始字节且无须业务鉴权头
 */
public record BatchUploadTicket(
        String batchId,
        String uploadUrl
) {
}

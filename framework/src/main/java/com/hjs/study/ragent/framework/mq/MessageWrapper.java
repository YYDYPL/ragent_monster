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

package com.hjs.study.ragent.framework.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * 统一消息外壳。
 * <p>
 * MQ 的原始 body 只描述业务数据，缺少排障和幂等需要的公共字段。该外壳把业务 key、业务载荷、
 * 消息唯一标识和发送时间固定下来，使生产者、消费者和事务回查使用同一份协议。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageWrapper<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务 key。
     * 用于按订单、文档或任务等业务实体检索消息；它不要求在全局唯一。
     */
    private String keys;

    /**
     * 业务载荷
     */
    private T body;

    /**
     * 消息实例的全局唯一标识，用于客户端幂等验证。
     * 同一业务 key 的多次合法事件可以拥有不同 uuid。
     */
    @Builder.Default
    private String uuid = UUID.randomUUID().toString();

    /**
     * 消息发送时间
     */
    @Builder.Default
    private Long timestamp = System.currentTimeMillis();
}

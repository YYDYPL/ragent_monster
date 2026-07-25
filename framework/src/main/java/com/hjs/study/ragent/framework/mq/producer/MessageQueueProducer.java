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

package com.hjs.study.ragent.framework.mq.producer;

import org.apache.rocketmq.client.producer.SendResult;

import java.util.function.Consumer;

/**
 * 业务模块使用的消息生产者抽象。
 * <p>
 * 接口故意只暴露“普通发送”和“事务发送”两种业务语义，不暴露 RocketMQTemplate、MessageBuilder
 * 等 SDK 类型。这样业务模块依赖的是稳定协议，基础设施模块可以承担实现替换和统一治理。
 * </p>
 */
public interface MessageQueueProducer {

    /**
     * 发送消息
     *
     * @param topic   目标 topic
     * @param keys    业务 key，可用于幂等判断
     * @param bizDesc 业务描述，用于日志标识
     * @param body    业务载荷
     * @return RocketMQ 发送结果，包含 msgId、sendStatus 等信息
     */
    SendResult send(String topic, String keys, String bizDesc, Object body);

    /**
     * 发送事务消息
     * <p>
     * 流程：发送 half 消息 → 执行本地事务 → 根据结果 commit/rollback
     * 这不是分布式数据库事务：它保证“本地事务结果”和“消息是否对消费者可见”协调一致。
     * <p>
     * 事务回查由按 topic 注册的 {@link TransactionChecker} 处理，需提前通过
     * {@link DelegatingTransactionListener#registerChecker(String, TransactionChecker)} 注册
     *
     * @param topic            目标 topic
     * @param keys             业务 key
     * @param bizDesc          业务描述
     * @param body             业务载荷
     * @param localTransaction 本地事务逻辑，在 half 消息发送成功后执行；抛异常则回滚消息
     */
    void sendInTransaction(String topic, String keys, String bizDesc, Object body,
                           Consumer<Object> localTransaction);
}

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

package com.hjs.study.ragent.framework.config;

import com.hjs.study.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.hjs.study.ragent.framework.mq.producer.MessageQueueProducer;
import com.hjs.study.ragent.framework.mq.producer.RocketMQProducerAdapter;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 消息队列自动装配配置类
 * <p>
 * 教学说明：
 * 1. 负责将 RocketMQ 的生产者组件以及事务监听器注入到 Spring 容器中。
 * 2. 通过自定义的适配器（Adapter）封装原生的 RocketMQTemplate，屏蔽底层细节，提供更友好的 API 给业务层使用。
 * </p>
 */
@Configuration
public class RocketMQAutoConfiguration {

    /**
     * 注册 RocketMQ 事务消息监听器代理类
     * <p>
     * 教学说明：
     * - 事务消息是 RocketMQ 的高级特性，主要用于解决本地事务与消息发送的分布式一致性问题（如：本地数据库执行成功才真正投递消息）。
     * - DelegatingTransactionListener 是项目内自定义的代理监听器，负责根据不同的事务场景将回调分发给具体的业务监听器。
     * </p>
     *
     * @return 事务监听器代理实例
     */
    @Bean
    public DelegatingTransactionListener delegatingTransactionListener() {
        return new DelegatingTransactionListener();
    }

    /**
     * 注册自定义的消息队列生产者（适配器）
     * <p>
     * 教学说明：
     * - RocketMQTemplate：Spring Boot 集成 RocketMQ 后提供的原生操作模板类。
     * - RocketMQProducerAdapter：是对 RocketMQTemplate 的一层包装（适配器模式）。
     * - 为什么不直接用 RocketMQTemplate？通过 Adapter 包装可以统一规范消息发送格式、统一处理发送异常、或者在发送前后添加日志/链路追踪等公共逻辑，避免业务代码与底层框架强耦合。
     * </p>
     *
     * @param rocketMQTemplate    RocketMQ 原生模板（由 rocketmq-spring-boot-starter 自动注入）
     * @param transactionListener 事务消息监听器（即上面注入的 DelegatingTransactionListener）
     * @return 封装后的通用消息生产者 Bean
     */
    @Bean
    public MessageQueueProducer messageQueueProducer(RocketMQTemplate rocketMQTemplate,
                                                     DelegatingTransactionListener transactionListener) {
        return new RocketMQProducerAdapter(rocketMQTemplate, transactionListener);
    }
}

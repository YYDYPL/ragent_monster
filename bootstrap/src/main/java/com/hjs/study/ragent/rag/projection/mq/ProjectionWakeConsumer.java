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

package com.hjs.study.ragent.rag.projection.mq;

import com.hjs.study.ragent.framework.mq.MessageWrapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionWakeMessage;
import com.hjs.study.ragent.rag.projection.worker.ProjectionJobExecutor;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/** MQ 只触发按 ID 抢占；真正任务参数始终重新读取 PostgreSQL。 */
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "rag-projection-wakeup_topic${unique-name:}",
        consumerGroup = "rag-projection-wakeup_cg${unique-name:}"
)
public class ProjectionWakeConsumer implements RocketMQListener<MessageWrapper<ProjectionWakeMessage>> {

    private final ProjectionJobExecutor executor;

    @Override
    public void onMessage(MessageWrapper<ProjectionWakeMessage> wrapper) {
        ProjectionWakeMessage message = wrapper.getBody();
        if (message != null && message.getProjectionId() != null) {
            executor.executeById(message.getProjectionId());
        }
    }
}

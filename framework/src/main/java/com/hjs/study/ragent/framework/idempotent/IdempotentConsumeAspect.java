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

package com.hjs.study.ragent.framework.idempotent;

import com.hjs.study.ragent.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 防止消息队列重复消费的切面。
 * <p>
 * 消息队列通常提供“至少一次”投递语义，因此同一业务消息可能再次到达消费者。
 * 本切面在 Redis 中维护 {@code CONSUMING -> CONSUMED} 状态机：首次请求获得执行权，
 * 已完成请求直接跳过，仍在执行的请求抛异常让 MQ 按其重试策略稍后再投递。
 * </p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentConsumeAspect {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 用一条 Redis 命令完成“仅首次写入 + 读取旧状态”。
     * 不能先 GET 再 SET，否则两个消费者可能同时看到 key 不存在并同时执行业务。
     */
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local value = ARGV[1]
            local expire_time_ms = ARGV[2]
            return redis.call('SET', key, value, 'NX', 'GET', 'PX', expire_time_ms)
            """;

    /**
     * 增强方法标记 {@link IdempotentConsume} 注解逻辑
     */
    @Around("@annotation(com.hjs.study.ragent.framework.idempotent.IdempotentConsume)")
    public Object idempotentConsume(ProceedingJoinPoint joinPoint) throws Throwable {
        IdempotentConsume idempotentConsume = getIdempotentConsumeAnnotation(joinPoint);
        String uniqueKey = idempotentConsume.keyPrefix()
                + SpELUtil.parseKey(idempotentConsume.key(), ((MethodSignature) joinPoint.getSignature()).getMethod(), joinPoint.getArgs());
        long keyTimeoutSeconds = idempotentConsume.keyTimeout();

        // 返回值是写入前的旧状态：null 表示本次抢到执行权，0/1 分别表示消费中/已完成。
        String absentAndGet = stringRedisTemplate.execute(
                RedisScript.of(LUA_SCRIPT, String.class),
                List.of(uniqueKey),
                IdempotentConsumeStatusEnum.CONSUMING.getCode(),
                String.valueOf(TimeUnit.SECONDS.toMillis(keyTimeoutSeconds))
        );

        // 消费中不能并行执行；已完成不再执行业务，这两条分支共同保证幂等语义。
        boolean errorFlag = IdempotentConsumeStatusEnum.isError(absentAndGet);
        if (errorFlag) {
            log.warn("[{}] MQ repeated consumption, wait for delayed retry.", uniqueKey);
            throw new ServiceException(String.format("消息消费者幂等异常，幂等标识：%s", uniqueKey));
        }
        if (IdempotentConsumeStatusEnum.CONSUMED.getCode().equals(absentAndGet)) {
            log.info("[{}] MQ consumption already completed, skip.", uniqueKey);
            return null;
        }

        try {
            Object result = joinPoint.proceed();
            // 业务成功后保留已完成标记，直到 TTL 到期，覆盖 Broker 的重复投递窗口。
            stringRedisTemplate.opsForValue().set(
                    uniqueKey,
                    IdempotentConsumeStatusEnum.CONSUMED.getCode(),
                    keyTimeoutSeconds,
                    TimeUnit.SECONDS
            );
            return result;
        } catch (Throwable ex) {
            // 失败时删除“消费中”标记，下一次投递才能重新执行业务。
            stringRedisTemplate.delete(uniqueKey);
            throw ex;
        }
    }

    /**
     * @return 返回自定义防重复消费注解
     */
    public static IdempotentConsume getIdempotentConsumeAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(IdempotentConsume.class);
    }
}

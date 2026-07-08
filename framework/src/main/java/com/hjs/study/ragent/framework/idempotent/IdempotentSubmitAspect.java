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

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.google.gson.Gson;
import com.hjs.study.ragent.framework.context.UserContext;
import com.hjs.study.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 防重复提交切面
 *
 * <p>
 * 该切面用于拦截所有标记了 {@link IdempotentSubmit} 的方法，
 * 在方法执行前基于 Redisson 分布式锁实现“同一请求只能执行一次”的控制
 * </p>
 *
 * <p>
 * 它的核心思想是：
 * </p>
 * <ul>
 *   <li>先为当前请求计算出唯一锁 Key</li>
 *   <li>尝试获取分布式锁</li>
 *   <li>获取成功则继续执行业务逻辑</li>
 *   <li>获取失败则判定为重复提交，直接抛异常</li>
 *   <li>业务执行完成后释放锁</li>
 * </ul>
 *
 * <p>
 * 相比单机内存锁，分布式锁可以在多实例部署场景下仍然保持幂等控制有效
 * </p>
 */
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentSubmitAspect {

    /**
     * Redisson 客户端
     *
     * <p>
     * 用于创建分布式锁对象
     * </p>
     */
    private final RedissonClient redissonClient;

    /**
     * Gson 序列化工具
     *
     * <p>
     * 用于把方法参数序列化成 JSON，再计算 MD5 摘要，作为默认锁 Key 的一部分
     * </p>
     */
    private final Gson gson = new Gson();

    /**
     * 是否开启评估模式
     *
     * <p>
     * 打开后将直接跳过幂等校验逻辑，通常用于某些测试、评估或调试场景
     * </p>
     */
    @Value("${app.eval.enabled:false}")
    private boolean evalEnabled;

    /**
     * 增强方法标记 {@link IdempotentSubmit} 注解逻辑
     *
     * <p>
     * 这是防重复提交的核心入口：
     * </p>
     * <ul>
     *   <li>评估模式下直接放行</li>
     *   <li>获取注解配置</li>
     *   <li>构建唯一锁 Key</li>
     *   <li>尝试加锁</li>
     *   <li>加锁失败则视为重复提交</li>
     *   <li>执行原方法并最终释放锁</li>
     * </ul>
     *
     * @param joinPoint 当前切点
     * @return 原方法执行结果
     * @throws Throwable 原方法执行异常
     */
    @Around("@annotation(com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit)")
    public Object idempotentSubmit(ProceedingJoinPoint joinPoint) throws Throwable {
        if (evalEnabled) {
            // 评估模式下跳过幂等控制，直接执行原逻辑
            return joinPoint.proceed();
        }
        IdempotentSubmit idempotentSubmit = getIdempotentSubmitAnnotation(joinPoint);
        // 获取分布式锁标识
        String lockKey = buildLockKey(joinPoint, idempotentSubmit);
        RLock lock = redissonClient.getLock(lockKey);
        // 尝试获取锁，获取锁失败就意味着已经重复提交，直接抛出异常
        if (!lock.tryLock()) {
            throw new ClientException(idempotentSubmit.message());
        }
        Object result;
        try {
            // 执行标记了防重复提交注解的方法原逻辑
            result = joinPoint.proceed();
        } finally {
            lock.unlock();
        }
        return result;
    }

    /**
     * @return 返回自定义防重复提交注解
     *
     * <p>
     * 通过目标类重新获取真实方法，避免只拿到代理方法导致注解解析不准确
     * </p>
     */
    public static IdempotentSubmit getIdempotentSubmitAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(IdempotentSubmit.class);
    }

    /**
     * @return 获取当前线程上下文 ServletPath
     *
     * <p>
     * 当没有显式配置自定义幂等 Key 时，接口路径会参与默认锁 Key 的构造，
     * 用来区分不同接口的请求
     * </p>
     */
    private String getServletPath() {
        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return Objects.requireNonNull(sra).getRequest().getServletPath();
    }

    /**
     * @return 当前操作用户 ID
     *
     * <p>
     * 用户 ID 是默认锁 Key 的组成部分之一，
     * 这样可以避免不同用户调用同一接口时互相抢同一把锁
     * </p>
     */
    private String getCurrentUserId() {
        return UserContext.getUserId();
    }

    /**
     * @return joinPoint md5
     *
     * <p>
     * 这里会把当前方法入参序列化成 JSON，并计算 MD5 值，
     * 用作默认锁 Key 的参数指纹部分。
     * 这样当同一用户、同一路径、相同参数重复提交时，才能命中同一把锁
     * </p>
     */
    private String calcArgsMD5(ProceedingJoinPoint joinPoint) {
        return DigestUtil.md5Hex(gson.toJson(joinPoint.getArgs()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构建分布式锁 Key
     *
     * <p>
     * 构建规则分两种：
     * </p>
     * <ul>
     *   <li>如果注解配置了自定义 key，则优先用 SpEL 解析结果</li>
     *   <li>否则使用“接口路径 + 当前用户 + 参数 MD5”作为默认唯一标识</li>
     * </ul>
     *
     * <p>
     * 这样的设计兼顾了通用性和可扩展性：
     * </p>
     * <ul>
     *   <li>默认方案开箱即用</li>
     *   <li>复杂业务场景可通过 SpEL 精准指定幂等粒度</li>
     * </ul>
     *
     * @param joinPoint         当前切点
     * @param idempotentSubmit  防重复提交注解
     * @return 分布式锁 Key
     */
    private String buildLockKey(ProceedingJoinPoint joinPoint, IdempotentSubmit idempotentSubmit) {
        if (StrUtil.isNotBlank(idempotentSubmit.key())) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Object keyValue = SpELUtil.parseKey(idempotentSubmit.key(), signature.getMethod(), joinPoint.getArgs());
            return String.format("idempotent-submit:key:%s", keyValue);
        }
        return String.format(
                "idempotent-submit:path:%s:currentUserId:%s:md5:%s",
                getServletPath(),
                getCurrentUserId(),
                calcArgsMD5(joinPoint)
        );
    }
}

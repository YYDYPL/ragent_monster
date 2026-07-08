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
 * 消息消费幂等切面
 *
 * <p>
 * 该切面用于拦截所有标记了 {@link IdempotentConsume} 的方法，
 * 在真正执行消费逻辑之前，先基于 Redis 判断这条消息是否已经被处理过
 * </p>
 *
 * <p>
 * 它的整体思路是：
 * </p>
 * <ul>
 *   <li>根据注解配置和方法参数计算出唯一幂等 Key</li>
 *   <li>通过 Redis 原子操作把当前消息标记为“消费中”</li>
 *   <li>如果之前已经是“已消费”，则直接跳过</li>
 *   <li>如果之前已经是“消费中”，说明可能在重试或重复投递，抛异常等待延迟重试</li>
 *   <li>执行成功后，把状态更新为“已消费”</li>
 *   <li>执行失败后，删除幂等 Key，允许后续重试重新消费</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentConsumeAspect {

    /**
     * Redis 操作模板
     *
     * <p>
     * 用于执行幂等状态写入、更新和删除操作
     * </p>
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Redis Lua 脚本
     *
     * <p>
     * 该脚本通过 {@code SET key value NX GET PX expire} 实现一个原子操作：
     * </p>
     * <ul>
     *   <li>如果 key 不存在，则写入“消费中”状态，并设置过期时间</li>
     *   <li>如果 key 已存在，则返回旧值，供 Java 侧判断当前状态</li>
     * </ul>
     *
     * <p>
     * 之所以使用 Lua 而不是多条普通命令，是为了避免并发下出现非原子竞争问题
     * </p>
     */
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local value = ARGV[1]
            local expire_time_ms = ARGV[2]
            return redis.call('SET', key, value, 'NX', 'GET', 'PX', expire_time_ms)
            """;

    /**
     * 增强方法标记 {@link IdempotentConsume} 注解逻辑
     *
     * <p>
     * 这是整个幂等消费的核心入口。方法执行前先做 Redis 状态判定，
     * 只有在“首次消费”场景下才真正放行业务方法执行
     * </p>
     *
     * @param joinPoint 当前切点
     * @return 原方法执行结果；若已消费则返回 {@code null}
     * @throws Throwable 原方法执行异常或幂等异常
     */
    @Around("@annotation(com.nageoffer.ai.ragent.framework.idempotent.IdempotentConsume)")
    public Object idempotentConsume(ProceedingJoinPoint joinPoint) throws Throwable {
        IdempotentConsume idempotentConsume = getIdempotentConsumeAnnotation(joinPoint);
        // 通过“前缀 + SpEL 解析结果”构造本次消息消费的唯一幂等标识
        String uniqueKey = idempotentConsume.keyPrefix()
                + SpELUtil.parseKey(idempotentConsume.key(), ((MethodSignature) joinPoint.getSignature()).getMethod(), joinPoint.getArgs());
        long keyTimeoutSeconds = idempotentConsume.keyTimeout();

        // 原子写入“消费中”状态：
        // - 如果 key 不存在，则成功写入，返回 null
        // - 如果 key 已存在，则返回旧值（可能是消费中，也可能是已消费）
        String absentAndGet = stringRedisTemplate.execute(
                RedisScript.of(LUA_SCRIPT, String.class),
                List.of(uniqueKey),
                IdempotentConsumeStatusEnum.CONSUMING.getCode(),
                String.valueOf(TimeUnit.SECONDS.toMillis(keyTimeoutSeconds))
        );

        // 如果已有消费中状态，提示延迟消费；已完成则直接跳过
        boolean errorFlag = IdempotentConsumeStatusEnum.isError(absentAndGet);
        if (errorFlag) {
            log.warn("[{}] MQ repeated consumption, wait for delayed retry.", uniqueKey);
            throw new ServiceException(String.format("消息消费者幂等异常，幂等标识：%s", uniqueKey));
        }
        if (IdempotentConsumeStatusEnum.CONSUMED.getCode().equals(absentAndGet)) {
            log.info("[{}] MQ consumption already completed, skip.", uniqueKey);
            // 已消费的消息直接跳过，不再重复执行业务逻辑
            return null;
        }

        try {
            // 首次消费，真正执行消息处理逻辑
            Object result = joinPoint.proceed();
            // 执行成功后，把状态改成“已消费”，后续同 key 消息将直接跳过
            stringRedisTemplate.opsForValue().set(
                    uniqueKey,
                    IdempotentConsumeStatusEnum.CONSUMED.getCode(),
                    keyTimeoutSeconds,
                    TimeUnit.SECONDS
            );
            return result;
        } catch (Throwable ex) {
            // 执行失败时删除“消费中”标记，让后续重试仍有机会重新消费
            stringRedisTemplate.delete(uniqueKey);
            throw ex;
        }
    }

    /**
     * @return 返回自定义防重复消费注解
     *
     * <p>
     * 由于切面拿到的方法有时可能是代理方法，这里通过目标类重新反射获取真正的方法对象，
     * 以确保能正确读取到方法上的 {@link IdempotentConsume} 注解
     * </p>
     */
    public static IdempotentConsume getIdempotentConsumeAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(IdempotentConsume.class);
    }
}

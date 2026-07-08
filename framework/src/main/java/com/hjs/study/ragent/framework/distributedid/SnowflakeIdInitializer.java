package com.hjs.study.ragent.framework.distributedid;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Singleton;
import cn.hutool.core.lang.Snowflake;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 分布式 Snowflake 初始化器
 *
 * <p>
 * 该类的职责是在应用启动时初始化雪花算法所需的节点参数，
 * 然后把生成器注册到 Hutool 的单例容器中，供整个应用统一使用
 * </p>
 *
 * <p>
 * 雪花算法通常依赖两个关键节点维度：
 * </p>
 * <ul>
 *   <li>{@code workerId}：工作机器编号</li>
 *   <li>{@code datacenterId}：数据中心编号</li>
 * </ul>
 *
 * <p>
 * 如果多个服务实例使用了相同的 workerId 和 datacenterId，
 * 就可能生成重复 ID。因此在分布式环境中，需要有一套集中分配机制。
 * 当前实现选择从 Redis 中通过 Lua 脚本原子获取这两个编号，
 * 从而避免多实例并发启动时出现冲突
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnowflakeIdInitializer {

    /**
     * Redis 操作模板
     *
     * <p>
     * 用于执行 Lua 脚本，从 Redis 中获取当前实例对应的 workerId 与 datacenterId
     * </p>
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 在 Spring Bean 初始化完成后执行雪花算法初始化
     *
     * <p>
     * {@link PostConstruct} 表示该方法会在依赖注入完成后自动执行，
     * 因此应用启动阶段就会完成雪花算法的节点参数分配
     * </p>
     *
     * <p>
     * 整个流程可以概括为：
     * </p>
     * <ul>
     *   <li>加载位于 classpath 下的 Lua 脚本</li>
     *   <li>执行脚本，原子获取 workerId 和 datacenterId</li>
     *   <li>创建 Hutool 的 {@link Snowflake} 实例</li>
     *   <li>将该实例注册到 Hutool 的 {@link Singleton} 容器中</li>
     * </ul>
     *
     * <p>
     * 初始化成功后，后续调用 {@code IdUtil.getSnowflakeNextId()} 时，
     * 就会基于这个全局配置好的 Snowflake 实例生成 ID
     * </p>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @PostConstruct
    public void init() {
        // 加载 Lua 脚本。脚本负责在 Redis 中原子分配 workerId / datacenterId
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/snowflake_init.lua")));
        script.setResultType(List.class);

        try {
            // 执行 Lua 脚本获取 workerId 和 datacenterId
            List<Long> result = stringRedisTemplate.execute(script, Collections.emptyList());

            // 理论上脚本应当返回两个值：workerId 和 datacenterId
            if (CollUtil.isEmpty(result) || result.size() != 2) {
                throw new RuntimeException("从Redis获取WorkerId和DataCenterId失败");
            }

            Long workerId = result.get(0);
            Long datacenterId = result.get(1);

            // 基于分配到的节点参数创建 Snowflake 实例
            Snowflake snowflake = new Snowflake(workerId, datacenterId);
            // 注册到 Hutool 的单例容器，后续 IdUtil 会复用这个实例
            Singleton.put(snowflake);

            log.info("分布式Snowflake初始化完成, workerId: {}, datacenterId: {}", workerId, datacenterId);
        } catch (Exception e) {
            // 启动阶段如果初始化失败，直接抛异常阻止应用带着错误的 ID 配置继续运行
            throw new RuntimeException("分布式Snowflake初始化失败", e);
        }
    }
}

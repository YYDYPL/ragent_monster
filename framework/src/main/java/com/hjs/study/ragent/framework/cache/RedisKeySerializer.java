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

package com.hjs.study.ragent.framework.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis Key 序列化器
 *
 * <p>
 * 该类用于统一处理 Redis 中 Key 的序列化与反序列化逻辑。
 * 在 Spring Data Redis 中，Redis 的 key 和 value 通常都会通过各自的序列化器
 * 转换成字节数组后再写入 Redis。这个类专门负责“key”这一部分的处理
 * </p>
 *
 * <p>
 * 当前实现的核心目标是：在真正写入 Redis 之前，自动给 key 拼接统一前缀。
 * 这样做的好处包括：
 * </p>
 * <ul>
 *   <li>区分不同环境或不同业务系统的缓存键，避免冲突</li>
 *   <li>统一命名规范，便于排查和管理 Redis 数据</li>
 *   <li>在同一个 Redis 实例中实现逻辑隔离</li>
 * </ul>
 *
 * <p>
 * 例如，当配置的前缀为 {@code prod:ragent:}，原始 key 为 {@code user:1001} 时，
 * 最终写入 Redis 的 key 就会变成 {@code prod:ragent:user:1001}
 * </p>
 */
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "framework.cache.redis.prefix")
public class RedisKeySerializer implements RedisSerializer<String> {

    /**
     * Redis Key 前缀
     *
     * <p>
     * 从配置项 {@code framework.cache.redis.prefix} 中读取。
     * 如果没有配置该值，则默认注入空字符串 {@code ""}。
     * </p>
     *
     * <p>
     * 注意：虽然这里设置了默认值为空字符串，但由于类上使用了
     * {@link ConditionalOnProperty}，只有当配置项存在时，这个 Bean 才会被注册进 Spring 容器
     * </p>
     */
    @Value("${framework.cache.redis.prefix:}")
    private String keyPrefix;

    /**
     * 将字符串类型的 Redis Key 序列化为字节数组
     *
     * <p>
     * 序列化流程很简单：
     * </p>
     * <ul>
     *   <li>先将配置中的前缀 {@link #keyPrefix} 与原始 key 拼接</li>
     *   <li>再把拼接后的字符串转换为字节数组，交给 Redis 客户端使用</li>
     * </ul>
     *
     * @param key 原始业务 key，例如 {@code user:1001}
     * @return 拼接前缀后的字节数组
     * @throws SerializationException 序列化异常
     */
    @Override
    public byte[] serialize(String key) throws SerializationException {
        String builderKey = keyPrefix + key;
        // 这里把业务 key 和统一前缀拼接，生成真正写入 Redis 的 key
        return builderKey.getBytes();
    }

    /**
     * 将 Redis 返回的字节数组反序列化为字符串
     *
     * <p>
     * 这里显式使用 UTF-8 字符集进行解码，避免依赖平台默认编码导致不同环境结果不一致
     * </p>
     *
     * @param bytes Redis 中读取出来的原始字节数组
     * @return 反序列化后的字符串 key
     * @throws SerializationException 反序列化异常
     */
    @Override
    public String deserialize(byte[] bytes) throws SerializationException {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

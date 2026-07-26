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

package com.hjs.study.ragent.framework.distributedid;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.springframework.stereotype.Component;

/**
 * 自定义 ID 生成器
 *
 * <p>
 * 该类实现了 MyBatis-Plus 提供的 {@link IdentifierGenerator} 接口，
 * 用于接管实体主键的生成逻辑
 * </p>
 *
 * <p>
 * 当前实现基于 Hutool 的 Snowflake 雪花算法生成分布式唯一 ID，
 * 用来替换 MyBatis-Plus 默认的分布式 ID 生成策略
 * </p>
 *
 * <p>
 * 为什么需要自定义主键生成器：
 * </p>
 * <ul>
 *   <li>在分布式系统中，多个服务实例同时写库时，不能依赖数据库自增主键</li>
 *   <li>雪花算法可以在本地快速生成全局唯一且趋势递增的 long 型 ID</li>
 *   <li>统一主键生成策略后，所有实体都能按照同一规则生成唯一标识</li>
 * </ul>
 *
 * <p>
 * 它通常会被 MyBatis-Plus 在插入数据时自动调用，
 * 无需业务代码手动为主键赋值
 * </p>
 */
@Component
public class CustomIdentifierGenerator implements IdentifierGenerator {

    /**
     * 生成数值型主键
     *
     * <p>
     * 当实体主键字段是数值类型时，MyBatis-Plus 会优先调用该方法。
     * 这里直接委托给 Hutool 的 {@code IdUtil.getSnowflakeNextId()}，
     * 返回一个 long 型分布式唯一 ID
     * </p>
     *
     * @param entity 当前待插入的实体对象
     * @return 新生成的数值型唯一 ID
     */
    @Override
    public Number nextId(Object entity) {
        return IdUtil.getSnowflakeNextId();
    }

    /**
     * 生成字符串型主键
     *
     * <p>
     * 尽管方法名叫 {@code nextUUID}，但这里并没有生成传统意义上的 UUID，
     * 而是把雪花算法生成的 long 型 ID 转成字符串返回
     * </p>
     *
     * <p>
     * 这样做的好处是：
     * </p>
     * <ul>
     *   <li>兼容需要字符串主键的字段类型</li>
     *   <li>仍然保留雪花 ID 全局唯一、趋势递增的特性</li>
     * </ul>
     *
     * @param entity 当前待插入的实体对象
     * @return 字符串形式的雪花唯一 ID
     */
    @Override
    public String nextUUID(Object entity) {
        return IdUtil.getSnowflakeNextIdStr();
    }
}

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

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.hjs.study.ragent.framework.database.MyMetaObjectHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 数据库持久层配置类
 * <p>
 * 教学说明：
 * 1. @Configuration 标识这是一个 Spring 配置类，Spring 启动时会解析并注册其中定义的 @Bean。
 * 2. 此类主要用于配置 MyBatis-Plus 的核心插件（如分页）以及实体类的公共字段自动填充策略。
 * </p>
 */
@Configuration
public class DataBaseConfiguration {

    /**
     * 配置 MyBatis-Plus 的核心拦截器（当前主要是分页插件）
     * <p>
     * 教学说明：
     * - PaginationInnerInterceptor：MyBatis-Plus 提供的分页拦截器，用于在执行 SQL 前拦截并动态拼接 `LIMIT/OFFSET` 分页语句。
     * - DbType.POSTGRE_SQL：指定数据库方言为 PostgreSQL。不同的数据库分页语法不同（如 MySQL 用 LIMIT，Oracle 用 ROWNUM），指定方言能确保分页 SQL 拼接正确。
     * </p>
     *
     * @return 组装好的 MyBatis-Plus 拦截器链
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 添加内部拦截器：PostgreSQL 分页拦截器
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 配置 MyBatis-Plus 源数据自动填充处理器
     * <p>
     * 教学说明：
     * - MetaObjectHandler：MyBatis-Plus 提供的元对象处理器接口。
     * - 作用：当我们执行 INSERT 或 UPDATE 操作时，自动为实体类中带有 `@TableField(fill = ...)` 的字段（如 create_time, update_time, creator 等）赋值，省去每次手动 set 的繁琐操作。
     * - MyMetaObjectHandler 是项目自定义的实现类，里面包含了具体的赋值逻辑。
     * </p>
     *
     * @return 自定义的元数据填充处理器 Bean
     */
    @Bean
    public MetaObjectHandler myMetaObjectHandler() {
        return new MyMetaObjectHandler();
    }
}

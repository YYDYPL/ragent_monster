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

package com.hjs.study.ragent.framework.database;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.util.Date;

/**
 * MyBatis-Plus 元数据自动填充处理器
 *
 * <p>
 * 该类实现了 MyBatis-Plus 提供的 {@link MetaObjectHandler} 接口，
 * 用于在执行数据库插入或更新操作时，自动为实体对象中的某些公共字段赋值
 * </p>
 *
 * <p>
 * 在实际项目中，很多表都会包含一些“通用字段”，例如：
 * </p>
 * <ul>
 *   <li>{@code createTime}：创建时间</li>
 *   <li>{@code updateTime}：更新时间</li>
 *   <li>{@code deleted}：逻辑删除标记</li>
 * </ul>
 *
 * <p>
 * 如果每次新增和更新数据时都手动去 set 这些字段，会造成大量重复代码。
 * 因此可以把这类通用赋值逻辑集中收口在这个处理器中统一完成
 * </p>
 *
 * <p>
 * 它通常会和实体字段上的 {@code @TableField(fill = ...)} 配合使用，
 * 从而在执行 INSERT / UPDATE 时自动触发
 * </p>
 */
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入数据时的自动填充逻辑
     *
     * <p>
     * 当执行 INSERT 语句时，MyBatis-Plus 会回调这个方法，
     * 这里统一补齐新记录初始化所需的公共字段
     * </p>
     *
     * <p>
     * 当前填充规则如下：
     * </p>
     * <ul>
     *   <li>{@code createTime}：设置为当前时间，表示记录创建时间</li>
     *   <li>{@code updateTime}：设置为当前时间，表示记录初始更新时间</li>
     *   <li>{@code deleted}：设置为 0，表示默认未删除</li>
     * </ul>
     *
     * <p>
     * 这里使用的是 {@code strictInsertFill}，它的含义是：
     * 只有在目标字段当前为空、且符合填充条件时才会赋值，
     * 不会粗暴覆盖调用方已经手动设置好的值
     * </p>
     *
     * @param metaObject MyBatis 封装的元对象，内部持有当前待插入实体的信息
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        // 新增数据时自动写入创建时间
        strictInsertFill(metaObject, "createTime", Date::new, Date.class);
        // 新增数据时同时初始化更新时间
        strictInsertFill(metaObject, "updateTime", Date::new, Date.class);
        // 新增数据时默认标记为“未删除”
        strictInsertFill(metaObject, "deleted", () -> 0, Integer.class);
    }

    /**
     * 更新数据时的自动填充逻辑
     *
     * <p>
     * 当执行 UPDATE 语句时，MyBatis-Plus 会回调该方法。
     * 更新场景下一般不需要改动创建时间和删除标记，
     * 但通常需要刷新 {@code updateTime}，表示这条记录最近一次被修改的时间
     * </p>
     *
     * <p>
     * 这里使用 {@code setFieldValByName} 直接按字段名写值，
     * 把 {@code updateTime} 更新为当前时间
     * </p>
     *
     * @param metaObject MyBatis 封装的元对象，内部持有当前待更新实体的信息
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.setFieldValByName("updateTime", new Date(), metaObject);
    }
}

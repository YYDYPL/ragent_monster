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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 防重复提交注解
 *
 * <p>
 * 该注解用于标记“需要防止用户重复提交”的接口方法，
 * 典型场景包括：
 * </p>
 * <ul>
 *   <li>用户连续快速点击提交按钮</li>
 *   <li>网络卡顿导致前端重复发起相同请求</li>
 *   <li>浏览器刷新、重试、脚本重复调用触发相同操作</li>
 * </ul>
 *
 * <p>
 * 在业务上，这类重复请求往往会导致严重问题，例如：
 * </p>
 * <ul>
 *   <li>重复下单</li>
 *   <li>重复支付</li>
 *   <li>重复创建资源</li>
 *   <li>重复提交审批或表单</li>
 * </ul>
 *
 * <p>
 * 通过在方法上加这个注解，并配合对应的切面，
 * 系统会在请求进入时基于用户、接口路径、参数内容或自定义 SpEL 生成唯一锁 Key，
 * 然后通过分布式锁限制同一业务请求在短时间内只能执行一次
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentSubmit {

    /**
     * 通过 SpEL 表达式生成的唯一 Key，优先级高于默认幂等逻辑
     *
     * <p>
     * 如果配置了该字段，系统会优先用它作为幂等锁的唯一标识，
     * 而不是使用默认的“路径 + 当前用户 + 参数摘要”组合方式
     * </p>
     *
     * <p>
     * 适用于业务上已经存在明确唯一键的场景，例如：
     * </p>
     * <ul>
     *   <li>{@code #request.orderNo}</li>
     *   <li>{@code #dto.bizId}</li>
     * </ul>
     */
    String key() default "";

    /**
     * 触发幂等失败逻辑时，返回的错误提示信息
     *
     * <p>
     * 当系统判定为重复提交时，会直接抛出客户端异常并返回该提示文案
     * </p>
     */
    String message() default "您操作太快，请稍后再试";
}

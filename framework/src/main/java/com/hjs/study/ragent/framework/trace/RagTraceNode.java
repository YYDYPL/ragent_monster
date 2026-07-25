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

package com.hjs.study.ragent.framework.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 RAG 链路中的普通 Trace 节点。
 * <p>
 * 运行时 AOP 读取该注解，在方法进入和退出时记录节点。它适合生命周期完全落在一次同步方法调用内的
 * 阶段；跨线程流式阶段需要改用 {@link RagStreamTraceSupport}，否则只会记录任务提交时间。
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceNode {

    /**
     * 节点名称（用于展示）
     */
    String name() default "";

    /**
     * 节点类型（用于分组统计）。
     * 名称面向具体操作，类型面向仪表盘聚合，二者不要混为同一个字段。
     */
    String type() default "METHOD";
}

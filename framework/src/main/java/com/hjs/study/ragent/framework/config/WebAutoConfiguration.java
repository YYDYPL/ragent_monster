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

import com.hjs.study.ragent.framework.web.GlobalExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Web 组件自动装配配置类
 * <p>
 * 教学说明：
 * 1. 负责向 Spring 容器注册 Web 层相关的全局通用组件。
 * 2. 这里配置的组件将会拦截或辅助处理所有的 HTTP 请求。
 * </p>
 */
@Configuration
public class WebAutoConfiguration {

    /**
     * 构建并注册全局异常拦截器组件 (GlobalExceptionHandler)
     * <p>
     * 教学说明：
     * - GlobalExceptionHandler 通常内部使用了 @RestControllerAdvice 或 @ControllerAdvice。
     * - 作用：当 Controller 层抛出异常（如之前定义的 ClientException、ServiceException 等）而没有被 try-catch 捕获时，
     *   请求会被转发到这个全局异常处理器中。
     * - 收益：统一封装异常信息为标准的 Result 格式返回给前端，避免直接将堆栈报错暴露给用户，提升接口的安全性和规范性。
     * </p>
     *
     * @return 全局异常处理器 Bean
     */
    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}

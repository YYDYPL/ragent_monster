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

package com.hjs.study.ragent.framework.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.hjs.study.ragent.framework.exception.ClientException;


/**
 * 当前请求的用户上下文容器。
 * <p>
 * Controller 拦截器在请求入口写入用户，业务层只通过这里读取，避免把 HttpServletRequest
 * 层层向下传递。使用 TTL 是为了让已包装的异步线程池把上下文复制到子任务。
 * </p>
 * <p>
 * TTL 不等于自动清理：请求结束和线程任务结束后仍必须调用 {@link #clear()}，否则线程池复用
 * 可能让下一位用户读到上一位用户的信息。
 * </p>
 */
public final class UserContext {

    private static final TransmittableThreadLocal<LoginUser> CONTEXT = new TransmittableThreadLocal<>();

    /**
     * 设置当前线程的用户上下文
     */
    public static void set(LoginUser user) {
        CONTEXT.set(user);
    }

    /**
     * 获取当前线程的用户上下文
     */
    public static LoginUser get() {
        return CONTEXT.get();
    }

    /**
     * 获取当前线程用户，若不存在则抛异常
     */
    public static LoginUser requireUser() {
        LoginUser user = CONTEXT.get();
        if (user == null) {
            // 对必须登录的业务尽早失败，避免在更深层以 null userId 写出脏数据。
            throw new ClientException("未获取到当前登录用户");
        }
        return user;
    }

    /**
     * 获取当前用户 ID（未登录返回 null）
     */
    public static String getUserId() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getUserId();
    }

    /**
     * 获取当前用户名（未登录返回 null）
     */
    public static String getUsername() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getUsername();
    }

    /**
     * 获取当前角色（未登录返回 null）
     */
    public static String getRole() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getRole();
    }

    /**
     * 获取当前头像（未登录返回 null）
     */
    public static String getAvatar() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getAvatar();
    }

    /**
     * 清理当前线程的用户上下文
     */
    public static void clear() {
        // remove 比 set(null) 更适合线程池：既释放引用，也不会留下一个空值槽位。
        CONTEXT.remove();
    }

    /**
     * 判断是否已存在用户上下文
     */
    public static boolean hasUser() {
        return CONTEXT.get() != null;
    }
}

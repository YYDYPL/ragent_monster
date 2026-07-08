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

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.ArrayUtil;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * SpEL 表达式解析工具类
 *
 * <p>
 * 该工具类用于在运行时解析注解中配置的 SpEL 表达式，
 * 常用于从方法参数中动态提取某个字段值，进而生成幂等 Key、缓存 Key、权限标识等
 * </p>
 *
 * <p>
 * 在本项目中，它主要服务于幂等相关注解：
 * </p>
 * <ul>
 *   <li>{@link IdempotentConsume}：从消息参数中提取唯一标识</li>
 *   <li>{@link IdempotentSubmit}：从请求参数中提取业务唯一标识</li>
 * </ul>
 *
 * <p>
 * 例如某个注解里写了：
 * </p>
 * <ul>
 *   <li>{@code #request.orderNo}</li>
 *   <li>{@code #dto.bizId}</li>
 *   <li>{@code T(java.lang.String).valueOf(#id)}</li>
 * </ul>
 *
 * <p>
 * 这个工具类就负责在方法真正执行前，把这些表达式解析成最终的字符串或对象值
 * </p>
 */
public final class SpELUtil {

    /**
     * 参数名发现器
     *
     * <p>
     * 用于根据方法对象获取参数名称，
     * 这样才能在 SpEL 上下文中把 {@code #userId}、{@code #dto} 这类变量名映射到真实入参
     * </p>
     */
    private static final DefaultParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    /**
     * SpEL 表达式解析器
     *
     * <p>
     * Spring 提供的标准表达式解析器，用于把字符串形式的 SpEL 编译成可执行表达式对象
     * </p>
     */
    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    /**
     * 校验并返回实际使用的 spEL 表达式
     *
     * @param spEl spEL 表达式
     * @param method 当前目标方法
     * @param contextObj 方法入参数组
     * @return 实际使用的 spEL 表达式
     */
    public static Object parseKey(String spEl, Method method, Object[] contextObj) {
        // 只有包含典型 SpEL 特征（例如 #变量 或 T(类型)）时，才真正按表达式解析
        // 否则直接把原字符串当作普通字面量返回
        List<String> spELFlag = ListUtil.of("#", "T(");
        Optional<String> optional = spELFlag.stream().filter(spEl::contains).findFirst();
        if (optional.isPresent()) {
            return parse(spEl, method, contextObj);
        }
        return spEl;
    }

    /**
     * 解析 SpEL 表达式
     *
     * @param spEl       spEl 表达式
     * @param method     当前目标方法
     * @param contextObj 上下文对象
     * @return 解析得到的表达式结果
     */
    public static Object parse(String spEl, Method method, Object[] contextObj) {
        Expression exp = EXPRESSION_PARSER.parseExpression(spEl);
        String[] params = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        StandardEvaluationContext context = new StandardEvaluationContext();
        if (ArrayUtil.isNotEmpty(params)) {
            for (int len = 0; len < params.length; len++) {
                // 把方法参数名和实际参数值绑定到 SpEL 上下文中，
                // 这样表达式中的 #paramName 才能正确取值
                context.setVariable(params[len], contextObj[len]);
            }
        }
        return exp.getValue(context);
    }
}

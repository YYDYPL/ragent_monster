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

package com.hjs.study.ragent.core.parser.mineru;

/**
 * MinerU 任务状态快照。
 * <p>
 * 这是一次轮询响应的不可变视图，不表示本地状态机会持久化。字段是否为空由 state 决定，
 * 调用方应先判断 {@link #completed()} 或 {@link #failed()}，再读取对应载荷。
 *
 * @param state        当前状态
 * @param zipUrl       结果 ZIP 下载 URL，仅 {@link MinerUTaskState#DONE} 时非空
 * @param errorMessage 失败原因，仅 {@link MinerUTaskState#FAILED} 时非空
 */
public record MinerUStatus(
        MinerUTaskState state,
        String zipUrl,
        String errorMessage
) {

    /**
     * @return 只有 DONE 才为 true；UNKNOWN 不会被误判为完成
     */
    public boolean completed() {
        return state == MinerUTaskState.DONE;
    }

    /**
     * @return 只有 FAILED 才为 true；网络异常由调用方异常路径处理
     */
    public boolean failed() {
        return state == MinerUTaskState.FAILED;
    }
}

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
 * MinerU 任务状态归一化枚举。
 * <p>
 * 与 MinerU SaaS 返回的 {@code state} 字段映射:
 * <ul>
 *   <li>{@code waiting-file / pending / running / converting} → {@link #RUNNING}</li>
 *   <li>{@code done} → {@link #DONE}</li>
 *   <li>{@code failed} → {@link #FAILED}</li>
 *   <li>无法识别的状态 → {@link #UNKNOWN}（上层视为未完成并继续轮询）</li>
 * </ul>
 */
public enum MinerUTaskState {
    /** 尚未产生最终结果，包括排队、上传后等待和解析中。 */
    RUNNING,

    /** 解析完成，状态响应应携带结果 ZIP URL。 */
    DONE,

    /** 外部任务最终失败，状态响应应携带错误信息。 */
    FAILED,

    /** 新增或无法识别的外部状态；采用向前兼容的继续轮询策略。 */
    UNKNOWN;

    /**
     * 从 MinerU 原始字段值映射到内部有限状态。
     * <p>
     * 同时兼容若干常见同义词，降低外部接口小幅改名造成的中断。未知值不抛异常，而是返回
     * UNKNOWN，最终仍受轮询 deadline 约束。
     *
     * @param raw 外部 state 字符串，可为 null
     * @return 归一化任务状态
     */
    public static MinerUTaskState parse(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw.toLowerCase()) {
            case "done", "success", "succeeded", "completed" -> DONE;
            case "failed", "fail", "error" -> FAILED;
            case "waiting-file", "pending", "running", "converting", "queueing", "queue" -> RUNNING;
            default -> UNKNOWN;
        };
    }
}

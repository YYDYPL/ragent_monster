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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MinerU SaaS 配置。
 * <p>
 * 配置项绑定到 application.yaml 的 {@code mineru.*} 节。时间类配置目前使用整数秒，调用方
 * 会在边界处转换成 Duration 或 TimeUnit。生产配置需要满足：
 * {@code leaseSeconds > timeoutSeconds}，否则任务仍在执行时分布式许可可能提前回收。
 */
@Configuration
@ConfigurationProperties("mineru")
@Data
public class MinerUProperties {

    /**
     * MinerU SaaS API 根地址。客户端会在其后拼接 v4 资源路径，末尾不宜额外添加斜杠。
     */
    private String apiUrl = "https://mineru.net/api/v4";

    /**
     * API Token，通常从环境变量 MINERU_API_KEY 注入；不得写入日志或提交真实值。
     */
    private String apiKey;

    /**
     * 内部轮询间隔（秒）。调度器会设置 100ms 的绝对下限，避免错误配置形成忙轮询。
     */
    private int pollIntervalSeconds = 5;

    /**
     * 单任务业务超时（秒），从提交轮询 Future 时开始计算。
     */
    private int timeoutSeconds = 300;

    /**
     * 是否要求 MinerU 提取表格。
     */
    private boolean enableTable = true;

    /**
     * 是否要求 MinerU 提取公式。
     */
    private boolean enableFormula = true;

    /**
     * 是否强制 OCR；原生文本 PDF 通常不需要。
     * <p>
     * 字段名不带 {@code is} 前缀:Lombok 对 boolean 字段会自动加 {@code is} 前缀生成
     * getter {@link #isOcr()}，setter 为 {@link #setOcr(boolean)};
     * Spring {@code @ConfigurationProperties} 据 setter 名识别属性为 {@code ocr}，
     * 故 yaml 必须写 {@code mineru.ocr: false} 而非 {@code is-ocr}
     */
    private boolean ocr = false;

    /**
     * 语言代码，遵循 MinerU（PaddleOCR）规范；默认 ch（中英文）。
     */
    private String language = "ch";

    /**
     * 全局 outstanding 任务上限。通过 Redisson 分布式信号量跨应用实例共同生效。
     */
    private int concurrencyLimit = 16;

    /**
     * MinerU 解析分布式信号量名称；共享同一 Redis 的实例必须使用相同名称。
     */
    private String semaphoreName = "rag:mineru:parse";

    /**
     * 获取 MinerU 解析许可的最大等待时间（秒）；超时后本次解析直接失败。
     */
    private int maxWaitSeconds = 30;

    /**
     * MinerU 解析许可自动释放时间（秒），用于实例崩溃后的兜底回收，需大于 timeoutSeconds
     * 及合理的网络/解包缓冲时间。
     */
    private int leaseSeconds = 900;
}

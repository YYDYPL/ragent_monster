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

import com.hjs.study.ragent.core.parser.DocumentParser;
import com.hjs.study.ragent.core.parser.ParserType;
import com.hjs.study.ragent.core.parser.model.ParsedDocument;
import com.hjs.study.ragent.framework.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MinerU 文档解析器（PDF / Word / PPT，亦可显式用于 Excel）。
 * <p>
 * 走 MinerU 官方"本地文件批量上传解析"，串联各组件实现 B-lite 异步解析:
 * <ol>
 *   <li>{@link RPermitExpirableSemaphore} 获取跨实例解析许可，限制 MinerU outstanding 任务数</li>
 *   <li>{@link MinerUClient#requestUpload} 申请上传链接，拿 batchId + 上传 URL</li>
 *   <li>{@link MinerUClient#uploadFile} 把源文件字节 PUT 上传到 MinerU OSS</li>
 *   <li>{@link MinerUPollingExecutor#submitAndAwait} 阻塞等待完成</li>
 *   <li>{@link MinerUClient#downloadZip} 下载 zip</li>
 *   <li>{@link MinerUResultUnpacker#unpack} 解包为 Block 列表(图片自动上传 RustFS)</li>
 * </ol>
 * <p>
 * 本地上传链路不依赖业务对象存储提供公网 URL，适配内网/本地部署；但应用实例本身仍需要访问
 * MinerU API、预签名上传地址和结果下载地址。配置项见 {@link MinerUProperties}。
 * <p>
 * 解析器是同步接口：直到外部解析、下载和解包全部完成才返回 ParsedDocument。分布式许可覆盖
 * 整个生命周期，确保多实例合计 outstanding 数不超过配置上限。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MinerUDocumentParser implements DocumentParser {

    /**
     * options 字段：原始文件名，写入 Provenance.sourceFile，并优先作为 MinerU 上传文件名。
     */
    public static final String OPT_SOURCE_FILE = "sourceFile";

    /**
     * options 字段：文档 ID，用于资产 key 命名；不传时自动生成 UUID。
     */
    public static final String OPT_DOCUMENT_ID = "documentId";

    /**
     * ParsedDocument.metadata 字段：MinerU 分配的 batchId，用于排障和外部任务关联。
     */
    public static final String META_BATCH_ID = "minerU.batchId";

    /**
     * ParsedDocument.metadata 字段：结果 ZIP 下载 URL。该 URL 可能有时效，不应作为永久资产地址。
     */
    public static final String META_ZIP_URL = "minerU.zipUrl";

    private final MinerUClient minerUClient;
    private final MinerUPollingExecutor pollingExecutor;
    private final MinerUResultUnpacker resultUnpacker;
    private final MinerUProperties properties;
    private final RedissonClient redissonClient;

    public MinerUDocumentParser(MinerUClient minerUClient,
                                MinerUPollingExecutor pollingExecutor,
                                MinerUResultUnpacker resultUnpacker,
                                MinerUProperties properties,
                                RedissonClient redissonClient) {
        this.minerUClient = minerUClient;
        this.pollingExecutor = pollingExecutor;
        this.resultUnpacker = resultUnpacker;
        this.properties = properties;
        this.redissonClient = redissonClient;
    }

    /**
     * 初始化或校准分布式许可总数。
     * <p>
     * 所有应用实例应使用相同 semaphoreName 和 concurrencyLimit；否则不同实例启动时可能互相
     * 覆盖许可数量，造成限流语义不稳定。
     */
    @PostConstruct
    void initSemaphore() {
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(properties.getSemaphoreName());
        semaphore.setPermits(properties.getConcurrencyLimit());
        log.info("MinerU 分布式解析限流初始化: semaphoreName={}, maxConcurrent={}",
                properties.getSemaphoreName(), properties.getConcurrencyLimit());
    }

    @Override
    public String getParserType() {
        return ParserType.MINERU.getType();
    }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        // Excel 不纳入 MIME 自动路由：默认走 POI 简单 key-value，复杂版面由上层显式选择 MinerU。
        String lower = mimeType.toLowerCase(Locale.ROOT);
        return lower.contains("pdf")
                || lower.contains("wordprocessingml") || lower.contains("msword")
                || lower.contains("presentationml") || lower.contains("powerpoint");
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            throw new ServiceException("MinerU 解析输入字节为空");
        }

        // Expirable Semaphore 返回 permitId 而不是布尔值；释放时必须携带同一 ID。
        String permitId = null;
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(properties.getSemaphoreName());
        try {
            // maxWait 控制用户等待许可的时间，lease 控制实例异常退出后的自动回收。
            permitId = semaphore.tryAcquire(
                    properties.getMaxWaitSeconds(),
                    properties.getLeaseSeconds(),
                    TimeUnit.SECONDS
            );
            if (permitId == null) {
                throw new ServiceException("MinerU 解析任务过多，请稍后重试");
            }
            return doParseStructured(content, mimeType, options);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("MinerU 获取解析许可被中断");
        } finally {
            if (permitId != null) {
                // tryRelease 对“租约已自动过期”返回 false，因此无需把它升级为解析失败。
                boolean released = semaphore.tryRelease(permitId);
                if (!released) {
                    log.warn("MinerU parse permit already expired or released, permitId={}", permitId);
                }
            }
        }
    }

    /**
     * 在已经持有分布式许可的前提下执行完整外部解析链。
     * <p>
     * 方法按严格顺序推进，任何一步失败都会抛出异常并由外层 finally 释放许可。当前没有在
     * MinerU 侧主动取消已提交 batch 的 API，因此本地超时后外部任务可能继续运行。
     */
    private ParsedDocument doParseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        String sourceFile = extractString(options, OPT_SOURCE_FILE, "");
        String documentId = extractString(options, OPT_DOCUMENT_ID, UUID.randomUUID().toString());
        // MinerU 靠 name 扩展名识别格式,缺文件名时按 mimeType 补全
        String uploadName = resolveUploadName(sourceFile, mimeType, documentId);

        // 1. 申请上传链接(只提交元信息,不带 url)
        BatchSubmitRequest request = buildSubmitRequest(uploadName, documentId);
        BatchUploadTicket ticket = minerUClient.requestUpload(request);

        // 2. 把源文件字节直接 PUT 上传到 MinerU OSS
        minerUClient.uploadFile(ticket.uploadUrl(), content);
        log.info("MinerU 源文件上传完毕 documentId={} batchId={}", documentId, ticket.batchId());

        // 3. 阻塞 await 完成（上传后 MinerU 自动提交解析）。
        MinerUStatus status;
        try {
            // Future 内部有业务 deadline，外层 get 再增加 30 秒，防止调度线程卡住导致永久等待。
            status = pollingExecutor
                    .submitAndAwait(ticket.batchId(), Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .get(properties.getTimeoutSeconds() + 30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ServiceException("MinerU 等待超时(包含调度缓冲)batchId=" + ticket.batchId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("MinerU 等待被中断 batchId=" + ticket.batchId());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new ServiceException("MinerU 等待异常 batchId=" + ticket.batchId() + ": " + cause.getMessage());
        }

        // 4. 下载 zip
        byte[] zipBytes = minerUClient.downloadZip(status.zipUrl());

        // 5. 解包为 ParsedDocument
        ParsedDocument parsed = resultUnpacker.unpack(zipBytes, sourceFile, documentId);

        // 6. 复制原 metadata 后注入外部任务信息；不原地修改 unpacker 返回的 Map。
        Map<String, Object> mergedMeta = new HashMap<>(parsed.metadata() == null ? Map.of() : parsed.metadata());
        mergedMeta.put(META_BATCH_ID, ticket.batchId());
        mergedMeta.put(META_ZIP_URL, status.zipUrl());
        mergedMeta.put("parser", getParserType());
        mergedMeta.put("mimeType", mimeType == null ? "" : mimeType);

        return ParsedDocument.of(parsed.blocks(), mergedMeta);
    }

    /**
     * 计算上传到 MinerU 的文件名，确保带正确扩展名。
     * <p>
     * 有原始文件名直接用,否则按 mimeType 合成 {@code doc-{documentId}{ext}}
     */
    private String resolveUploadName(String sourceFile, String mimeType, String documentId) {
        if (sourceFile != null && !sourceFile.isBlank()) {
            return sourceFile;
        }
        return "doc-" + documentId + extFromMime(mimeType);
    }

    /**
     * 把应用配置和本次文件标识组合成单文件提交请求。
     */
    private BatchSubmitRequest buildSubmitRequest(String fileName, String documentId) {
        return new BatchSubmitRequest(
                fileName,
                documentId,
                properties.isOcr(),
                properties.isEnableTable(),
                properties.isEnableFormula(),
                properties.getLanguage()
        );
    }

    /**
     * 把常见 Office/PDF MIME 映射为 MinerU 用于识别格式的扩展名。
     * <p>
     * 无法识别时返回 .bin；这通常会导致远端拒绝或解析失败，而不是在本地猜测错误格式。
     */
    private static String extFromMime(String mimeType) {
        if (mimeType == null) {
            return ".bin";
        }
        String lower = mimeType.toLowerCase(Locale.ROOT);
        if (lower.contains("pdf")) return ".pdf";
        if (lower.contains("wordprocessingml")) return ".docx";
        if (lower.contains("msword")) return ".doc";
        if (lower.contains("presentationml")) return ".pptx";
        if (lower.contains("powerpoint")) return ".ppt";
        if (lower.contains("spreadsheetml")) return ".xlsx";
        if (lower.contains("ms-excel") || lower.contains("excel")) return ".xls";
        return ".bin";
    }

    /**
     * 从 options 读取非空字符串，兼容任意可安全 {@code toString()} 的值。
     */
    private static String extractString(Map<String, Object> options, String key, String defaultValue) {
        if (options == null) {
            return defaultValue;
        }
        Object v = options.get(key);
        return (v == null || v.toString().isBlank()) ? defaultValue : v.toString();
    }
}

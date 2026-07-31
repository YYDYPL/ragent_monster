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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hjs.study.ragent.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * MinerU SaaS HTTP 客户端。
 * <p>
 * 提供四个核心方法(走"本地文件批量上传解析"链路):
 * <ul>
 *   <li>{@link #requestUpload} 申请上传链接,返回 batch_id + 上传 URL</li>
 *   <li>{@link #uploadFile} 把文件字节 PUT 上传到 MinerU OSS</li>
 *   <li>{@link #queryResult} 查询任务状态(轮询用)</li>
 *   <li>{@link #downloadZip} 下载结果 zip 字节流</li>
 * </ul>
 * <p>
 * 鉴权：业务 API 使用 {@code Authorization: Bearer <api-key>}；上传和下载使用预签名 URL，
 * 不附加业务 Token。响应使用 {@link JsonNode} 做容错读取，只依赖当前流程需要的字段，避免为
 * 第三方完整响应建立脆弱 DTO。
 * <p>
 * 客户端执行同步 HTTP 调用，连接、读取和整体超时由注入的 {@code syncHttpClient} 统一配置。
 * 本类不做业务重试：轮询阶段的瞬时异常由 MinerUPollingExecutor 吸收，提交和上传失败则直接
 * 交给上层任务重试。
 */
@Slf4j
@Component
public class MinerUClient {

    /**
     * 申请上传接口的 JSON 媒体类型；OkHttp RequestBody 会据此写 Content-Type。
     */
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    /** 复用连接池的同步 OkHttp 客户端。 */
    private final OkHttpClient httpClient;

    /** 只用于构造请求树和解析响应树，无业务状态。 */
    private final ObjectMapper objectMapper;

    /** API 地址、凭据和轮询参数。 */
    private final MinerUProperties properties;

    /**
     * @param httpClient 由主应用配置的同步 HTTP Client
     * @param objectMapper 应用共享 JSON 映射器
     * @param properties MinerU 配置
     */
    public MinerUClient(@Qualifier("syncHttpClient") OkHttpClient httpClient,
                        ObjectMapper objectMapper,
                        MinerUProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 申请上传链接(本地文件批量上传解析的第一步)
     * <p>
     * 只提交文件元信息(不带 url),MinerU 返回 batch_id 与预签名上传 URL
     * 拿到 URL 后须调 {@link #uploadFile} 把文件字节 PUT 上去,MinerU 才会自动提交解析
     *
     * @param request 单文件解析请求
     * @return 含 batch_id + 上传 URL 的凭证
     * @throws ServiceException 网络异常 / api-key 缺失 / SaaS 返回错误 / 缺少上传 URL
     */
    public BatchUploadTicket requestUpload(BatchSubmitRequest request) {
        requireApiKey();

        // 外层字段属于 batch 级配置；files 数组当前只放一个文件。
        ObjectNode body = objectMapper.createObjectNode();
        body.put("enable_formula", request.enableFormula());
        body.put("enable_table", request.enableTable());
        body.put("language", request.language() == null ? "ch" : request.language());

        ArrayNode files = body.putArray("files");
        ObjectNode file = files.addObject();
        if (request.fileName() != null) {
            file.put("name", request.fileName());
        }
        file.put("is_ocr", request.isOcr());
        if (request.dataId() != null) {
            file.put("data_id", request.dataId());
        }

        String url = properties.getApiUrl() + "/file-urls/batch";
        Request httpRequest = newJsonPost(url, body.toString());

        JsonNode root = executeAndParse(httpRequest, "requestUpload");
        ensureSuccess(root, "requestUpload");

        // JsonNode.path 在字段不存在时返回 MissingNode，后续 asText(null) 可统一处理缺字段。
        JsonNode data = root.path("data");
        String batchId = data.path("batch_id").asText(null);
        if (batchId == null || batchId.isBlank()) {
            throw new ServiceException("MinerU requestUpload 返回缺少 batch_id, body=" + root);
        }

        JsonNode fileUrls = data.path("file_urls");
        if (!fileUrls.isArray() || fileUrls.isEmpty()) {
            throw new ServiceException("MinerU requestUpload 返回缺少 file_urls, body=" + root);
        }
        String uploadUrl = fileUrls.get(0).asText(null);
        if (uploadUrl == null || uploadUrl.isBlank()) {
            throw new ServiceException("MinerU requestUpload 返回的 file_urls[0] 为空, body=" + root);
        }

        log.info("MinerU 申请上传链接成功 batchId={} fileName={}", batchId, request.fileName());
        return new BatchUploadTicket(batchId, uploadUrl);
    }

    /**
     * 上传文件字节到 MinerU 预签名 URL(本地文件批量上传解析的第二步)
     * <p>
     * 注意:目标是 OSS 预签名 PUT 链接,按 MinerU 官方要求<b>不设 Content-Type、不带 Authorization</b>
     * 上传成功后 MinerU 自动探测并提交解析任务,随后即可 {@link #queryResult} 轮询
     * <p>
     * 当前成功日志会输出完整 uploadUrl；由于预签名 URL 具有临时凭据性质，生产日志策略应对其
     * 脱敏或避免采集。
     *
     * @param uploadUrl {@link #requestUpload} 返回的上传 URL
     * @param content   文件原始字节
     * @throws ServiceException 网络异常 / 非 2xx 响应
     */
    public void uploadFile(String uploadUrl, byte[] content) {
        if (uploadUrl == null || uploadUrl.isBlank()) {
            throw new ServiceException("uploadUrl 不能为空");
        }
        if (content == null || content.length == 0) {
            throw new ServiceException("上传字节不能为空");
        }
        // 预签名 PUT:body 无 Content-Type(传 null),不加 Authorization
        Request httpRequest = new Request.Builder()
                .url(uploadUrl)
                .put(RequestBody.create(content, null))
                .build();
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String body = readBodySafe(response);
                throw new ServiceException("MinerU uploadFile 失败 code=" + response.code() + " body=" + body);
            }
            log.info("MinerU 文件上传成功 size={} url={}", content.length, uploadUrl);
        } catch (IOException e) {
            throw new ServiceException("MinerU uploadFile 网络异常: " + e.getMessage());
        }
    }

    /**
     * 查询任务状态并归一化为 {@link MinerUStatus}。
     * <p>
     * batch API 即使业务码成功，也可能尚未产生 extract_result；这种情况不是错误，而是 RUNNING。
     * 当前提交模型为单文件，因此数组非空时只读取第一个元素。
     *
     * @param batchId 申请上传时返回的批次 ID
     * @return 当前状态快照
     */
    public MinerUStatus queryResult(String batchId) {
        requireApiKey();
        if (batchId == null || batchId.isBlank()) {
            throw new ServiceException("batchId 不能为空");
        }

        // MinerU v4 按 batch_id 查询结果:batch_id 是路径段(/extract-results/batch/{batch_id}),不是查询参数
        String url = properties.getApiUrl() + "/extract-results/batch/" + batchId;
        Request httpRequest = newGet(url);

        JsonNode root = executeAndParse(httpRequest, "queryResult");
        ensureSuccess(root, "queryResult");

        JsonNode data = root.path("data");
        JsonNode extractResult = data.path("extract_result");
        if (!extractResult.isArray() || extractResult.isEmpty()) {
            // 任务可能仍在排队,暂无 extract_result
            return new MinerUStatus(MinerUTaskState.RUNNING, null, null);
        }

        // 单文件提交，只取第一个；未来升级真正 batch 时需改成按 data_id 关联。
        JsonNode item = extractResult.get(0);
        String stateRaw = item.path("state").asText(null);
        MinerUTaskState state = MinerUTaskState.parse(stateRaw);
        String zipUrl = item.path("full_zip_url").asText(null);
        String errMsg = item.path("err_msg").asText(null);

        return new MinerUStatus(state, zipUrl, errMsg);
    }

    /**
     * 下载结果 ZIP 字节流。
     * <p>
     * 此 URL 通常是有时效的预签名 URL，拿到 MinerUStatus.zipUrl 后应立即下载。响应一次性读入
     * byte[]，因此文件规模仍受 HTTP Client 和应用内存限制。
     *
     * @param zipUrl DONE 状态返回的结果地址
     * @return 完整 ZIP 字节
     */
    public byte[] downloadZip(String zipUrl) {
        if (zipUrl == null || zipUrl.isBlank()) {
            throw new ServiceException("zipUrl 不能为空");
        }
        Request httpRequest = new Request.Builder().url(zipUrl).get().build();
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String body = readBodySafe(response);
                throw new ServiceException("MinerU downloadZip 失败 code=" + response.code() + " body=" + body);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ServiceException("MinerU downloadZip 响应体为空");
            }
            return body.bytes();
        } catch (IOException e) {
            throw new ServiceException("MinerU downloadZip 网络异常: " + e.getMessage());
        }
    }

    // ============== private helpers ==============

    /**
     * 在发起需要业务鉴权的请求前快速校验配置，避免发送无意义的 Bearer null。
     */
    private void requireApiKey() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ServiceException("MinerU api-key 未配置,请设置环境变量 MINERU_API_KEY");
        }
    }

    /**
     * 构造带 Bearer Token 的 JSON POST 请求。
     */
    private Request newJsonPost(String url, String jsonBody) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON_MEDIA))
                .build();
    }

    /**
     * 构造带 Bearer Token 的 GET 请求。
     */
    private Request newGet(String url) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .get()
                .build();
    }

    /**
     * 执行 HTTP 请求并把成功响应解析为 JSON 树。
     * <p>
     * HTTP 非 2xx 与 JSON 解析失败在此统一转换为 ServiceException；MinerU 业务 code 是否成功
     * 由调用方随后使用 {@link #ensureSuccess(JsonNode, String)} 检查。响应体只读取一次。
     *
     * @param request 已构造的 OkHttp 请求
     * @param opName  用于异常定位的操作名，不发送给远端
     */
    private JsonNode executeAndParse(Request request, String opName) {
        try (Response response = httpClient.newCall(request).execute()) {
            String body = readBodySafe(response);
            if (!response.isSuccessful()) {
                throw new ServiceException(String.format(
                        "MinerU %s HTTP 异常 code=%d, body=%s", opName, response.code(), body));
            }
            try {
                return objectMapper.readTree(body);
            } catch (IOException e) {
                throw new ServiceException("MinerU " + opName + " 响应非 JSON: " + body);
            }
        } catch (IOException e) {
            throw new ServiceException("MinerU " + opName + " 网络异常: " + e.getMessage());
        }
    }

    /**
     * 检查 MinerU 业务码，非 0 抛出业务异常。
     * <p>
     * MinerU 标准响应格式 {@code {"code":0,"msg":"ok","data":{...}}}
     */
    private void ensureSuccess(JsonNode root, String opName) {
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String msg = root.path("msg").asText("unknown");
            throw new ServiceException(String.format(
                    "MinerU %s 业务异常 code=%d msg=%s", opName, code, msg));
        }
    }

    /**
     * 尽力读取响应体供诊断。
     * <p>
     * 读取失败时返回空串，不让“记录远端错误正文失败”覆盖原始 HTTP 状态。调用后响应体已消费，
     * 因此同一个 Response 不应再次读取。
     */
    private String readBodySafe(Response response) {
        try {
            ResponseBody body = response.body();
            return body == null ? "" : body.string();
        } catch (IOException e) {
            return "";
        }
    }
}

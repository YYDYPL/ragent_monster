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

package com.hjs.study.ragent.core.parser.image;

import com.hjs.study.ragent.core.parser.DocumentParser;
import com.hjs.study.ragent.core.parser.ParserType;
import com.hjs.study.ragent.core.parser.model.AssetRef;
import com.hjs.study.ragent.core.parser.model.ImageBlock;
import com.hjs.study.ragent.core.parser.model.ParsedDocument;
import com.hjs.study.ragent.core.parser.model.Provenance;
import com.hjs.study.ragent.framework.exception.ServiceException;
import com.hjs.study.ragent.infra.vlm.VlmService;
import com.hjs.study.ragent.rag.dto.StoredFileDTO;
import com.hjs.study.ragent.rag.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 图片文档解析器（PNG / JPG / SVG）——写入侧“图生文”。
 * <p>
 * 独立上传的图片本身没有可检索文本，直接 embedding {@code ![](url)} 等于噪声、永远召回不到。
 * 因此入库期用 VLM 把图片转成「中文描述 + 图中文字 OCR」作为可检索文本，同时把原图上传到
 * asset-bucket 供答复展示。产出单个带 {@code description} 的 {@link ImageBlock}：
 * <ul>
 *   <li>{@code description} 进 embedding，负责召回</li>
 *   <li>{@code asset.publicUrl} 由 {@link com.hjs.study.ragent.core.chunk.blockaware.ImageChunker}
 *       渲染为 {@code ![caption](url)}，随答复返回、前端展示</li>
 * </ul>
 * <p>
 * SVG 是矢量 XML，VLM 视觉输入只认栅格格式，故先 {@link #rasterizeSvg} 渲染成 PNG 再并入 PNG 链路
 * <p>
 * 优先级高于 Tika（Tika 已对 image/* 返回 false），避免图片被当平文本兜底。
 * <p>
 * 该实现同时产生外部副作用（VLM 调用和对象存储写入），不具备关系数据库事务原子性。当前策略
 * 是先生成描述、再上传资产：VLM 失败不会留下孤立图片；上传成功后若后续文档事务失败，则需要
 * 上层清理或对账机制回收孤立资产。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class ImageDocumentParser implements DocumentParser {

    /**
     * options：原始文件名，用于标题和 Provenance。
     */
    public static final String OPT_SOURCE_FILE = "sourceFile";

    /**
     * options：业务文档 ID，用于构造稳定的资产目录前缀。
     */
    public static final String OPT_DOCUMENT_ID = "documentId";

    /**
     * 视觉语言模型门面：把图片字节转换为可检索描述。
     */
    private final VlmService vlmService;

    /**
     * 图片资产存储：保存原图并生成前端可访问 URL。
     */
    private final FileStorageService fileStorageService;

    /**
     * 图生文 Prompt 与输出长度配置。
     */
    private final ImageParseProperties properties;

    /**
     * 通过构造器显式注入所有外部依赖，解析器本身不保存单次请求状态。
     */
    public ImageDocumentParser(VlmService vlmService,
                               FileStorageService fileStorageService,
                               ImageParseProperties properties) {
        this.vlmService = vlmService;
        this.fileStorageService = fileStorageService;
        this.properties = properties;
    }

    @Override
    public String getParserType() {
        return ParserType.IMAGE.getType();
    }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        // 使用 ROOT Locale，避免土耳其语等区域规则改变 MIME 的大小写结果。
        String lower = mimeType.toLowerCase(Locale.ROOT);
        return lower.equals("image/png")
                || lower.equals("image/jpeg")
                || lower.equals("image/jpg")
                || lower.equals("image/svg+xml");
    }

    @Override
    public ParsedDocument parseStructured(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            throw new ServiceException("图片解析输入字节为空");
        }
        // documentId 缺失时生成 UUID，保证资产 key 不会全部落在同一个空目录。
        String sourceFile = extract(options, OPT_SOURCE_FILE, "");
        String documentId = extract(options, OPT_DOCUMENT_ID, UUID.randomUUID().toString());

        // 0. SVG 归一化：矢量 XML 栅格化成 PNG，此后字节与 mime 与 PNG 路径完全一致
        if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).equals("image/svg+xml")) {
            content = rasterizeSvg(content);
            mimeType = "image/png";
        }

        // 1. VLM 图生文（失败直接抛错，不兜底，避免产生"有图无描述"或"有描述无图"的残缺数据）
        // 直接取整段输出作描述，不解析任何分隔符 —— prompt 措辞可自由调整，解析器不耦合
        String description = vlmService.describeImage(
                content, mimeType, properties.getDescriptionPrompt(), properties.getMaxOutputTokens());
        description = description == null ? "" : description.strip();
        // 空描述等同失败：放过去只会产出「有图无描述」的纯链接 chunk，永远召回不到，故直接抛错暴露问题
        if (description.isBlank()) {
            throw new ServiceException("VLM 返回空描述，无法生成可检索文本：file=" + sourceFile);
        }

        // 2. 原图上传资产桶（public-read），拿匿名可达的公网 URL
        String ext = extFromMime(mimeType);
        // UUID 解决同一文档中重名图片的覆盖问题，documentId 则便于按文档归档和清理。
        String filename = "assets/" + documentId + "/" + UUID.randomUUID() + "." + ext;
        StoredFileDTO stored = fileStorageService.uploadAsset(content, filename, mimeType);
        String publicUrl = fileStorageService.getPublicUrl(stored.getUrl());

        // 3. 构造 ImageBlock：description 同时用于 content(展示/答题)与 embeddingText(向量，由 ImageChunker 去 URL)
        String blockId = UUID.randomUUID().toString();
        // caption/altText 取无扩展名文件名；真正可检索正文仍是 description。
        String caption = stripExt(sourceFile);
        AssetRef asset = new AssetRef(publicUrl, mimeType, blockId);
        ImageBlock block = new ImageBlock(blockId, Provenance.ofFile(sourceFile), List.of(),
                asset, caption, caption, description);

        log.info("图片图生文完成: file={}, descChars={}, url={}", sourceFile, description.length(), publicUrl);
        return ParsedDocument.of(List.of(block), Map.of(
                "parser", getParserType(),
                "mimeType", mimeType == null ? "" : mimeType,
                "descriptionChars", description.length()
        ));
    }

    /**
     * 从弱类型 options 中读取非空字符串。
     *
     * @param options      调用方解析选项
     * @param key          目标键
     * @param defaultValue Map、值缺失或值为空白时的默认值
     */
    private static String extract(Map<String, Object> options, String key, String defaultValue) {
        if (options == null) {
            return defaultValue;
        }
        Object v = options.get(key);
        return (v == null || v.toString().isBlank()) ? defaultValue : v.toString();
    }

    /**
     * 把 SVG 栅格化为 PNG 字节。
     * <p>
     * 必须铺白底：PNGTranscoder 默认透明背景，VLM 解码带 alpha 的 PNG 会把透明区合成为黑/空，
     * 导致模型「看不到内容」返回空描述。无内在尺寸的 SVG 设宽度上限避免超大画布；
     * 失败直接抛错，不产残缺数据（与「VLM 失败不兜底」一致）
     *
     * @param svg 原始 SVG XML 字节
     * @return 带白色背景、宽度受限的 PNG 字节
     */
    private static byte[] rasterizeSvg(byte[] svg) {
        try {
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_MAX_WIDTH, 1600f);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, Color.WHITE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transcoder.transcode(new TranscoderInput(new ByteArrayInputStream(svg)), new TranscoderOutput(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new ServiceException("SVG 栅格化失败：" + e.getMessage());
        }
    }

    /**
     * 把已支持的图片 MIME 映射为对象存储文件扩展名。
     * <p>
     * SVG 在调用本方法前已被转换为 image/png；未知或 null 值按 png 兜底。
     */
    private static String extFromMime(String mimeType) {
        if (mimeType == null) {
            return "png";
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> "jpg";
            default -> "png";
        };
    }

    /**
     * 去掉文件名最后一个扩展名，用作人类可读标题。
     */
    private static String stripExt(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}

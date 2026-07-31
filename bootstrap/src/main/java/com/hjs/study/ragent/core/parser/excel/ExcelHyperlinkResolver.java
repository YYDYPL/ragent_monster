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

package com.hjs.study.ragent.core.parser.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Hyperlink;

/**
 * Excel 超链接解析器。
 * <p>
 * 解决"文字里放链接"的硬需求：cell 的可见文字与底层 URL 是分离的（URL 是 cell metadata），
 * 必须显式读出 hyperlink 并拼接成 Markdown 内联形式 {@code [text](url)}。
 * <p>
 * MinerU 等 OCR / 版面识别工具通常拿不到此元数据——这是规整 Excel 优先走 POI 的核心原因。
 * 本类只负责组合文本，不验证 URL 协议，也不转义可见文本或地址中的 Markdown 特殊字符；
 * 返回值最终作为知识文本而非浏览器导航白名单使用。
 */
public final class ExcelHyperlinkResolver {

    private ExcelHyperlinkResolver() {
    }

    /**
     * 包装 cell 文字为 markdown 内联超链接形式
     *
     * @param cellText cell 的可见文字（已经过 ExcelValueFormatter 格式化）
     * @param cell     cell 实例，用于查询 hyperlink；可空
     * @return 如果 cell 有非空超链接：{@code [cellText](url)}；否则原样返回 cellText
     */
    public static String wrap(String cellText, Cell cell) {
        if (cell == null) {
            return cellText == null ? "" : cellText;
        }
        Hyperlink hyperlink = cell.getHyperlink();
        if (hyperlink == null) {
            return cellText == null ? "" : cellText;
        }
        String url = hyperlink.getAddress();
        if (url == null || url.isBlank()) {
            return cellText == null ? "" : cellText;
        }
        // 可见文字优先级：格式化后的 cell 值 > hyperlink label > URL。
        String visible = (cellText == null || cellText.isEmpty()) ? hyperlink.getLabel() : cellText;
        if (visible == null || visible.isEmpty()) {
            visible = url;
        }
        return "[" + visible + "](" + url + ")";
    }
}

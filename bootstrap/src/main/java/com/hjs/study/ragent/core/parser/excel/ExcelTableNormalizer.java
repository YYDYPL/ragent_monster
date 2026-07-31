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

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel 表格规范化器（简单 key-value 版）。
 * <p>
 * 把一个 POI Sheet 转为单个干净的 (headers, rows) 二维结构，只处理「规整单表」的通用清洗：
 * <ul>
 *   <li>合并单元格展开：合并区域的左上角值复制到该区域每个 cell（行级 chunk 自包含友好）</li>
 *   <li>多行表头展平：前 N 行合并成单行表头，列名用分隔符拼接（如 "财务|收入"）</li>
 *   <li>超链接保留：cell 文字外包 {@code [text](url)}</li>
 *   <li>公式回退：通过 {@link ExcelValueFormatter}</li>
 *   <li>全空行 / 尾部全空列跳过</li>
 * </ul>
 * <p>
 * <b>不再做</b>多表格区域切分、文档/section 标题识别、横向重复列折叠等版面启发式
 * 这类复杂版面的 Excel 应由上层路由到 MinerU 解析，POI 只负责一 sheet 一张规整表
 * <p>
 * 实现先读取一个矩形内存网格，再在网格上完成合并区域展开和空列筛选。该做法逻辑直观，
 * 但空间复杂度约为 {@code 行数 × 最大列数}；超大或极稀疏工作表应在上传层限制规模，或改用
 * 流式事件模型。
 */
public final class ExcelTableNormalizer {

    /**
     * 多行表头展平时使用的分隔符
     */
    public static final String HEADER_SEPARATOR = "|";

    /**
     * 划删除线 cell 的包裹标记（软删除约定：用 GFM 删除线 {@code ~~值~~} 包裹原值，保留文本并显式标注）
     */
    private static final String STRIKETHROUGH_WRAP = "~~";

    private ExcelTableNormalizer() {
    }

    /**
     * 规范化结果。
     *
     * @param headers 已展平的列名（长度等于有效列数）
     * @param rows    数据行（与 headers 对齐，全空行已跳过）
     */
    public record NormalizedTable(
            List<String> headers,
            List<List<String>> rows
    ) {
        /**
         * 只有表头和数据行都为空才算空表；“有表头、零数据行”仍是有效结构。
         */
        public boolean isEmpty() {
            return headers.isEmpty() && rows.isEmpty();
        }

        /**
         * 返回共享不可变空集合组成的空表。
         */
        static NormalizedTable empty() {
            return new NormalizedTable(List.of(), List.of());
        }
    }

    /**
     * 规范化 Sheet 为单张表：前 headerRows 行为表头，其余为数据行。
     * <p>
     * 方法不修改 POI Sheet，只修改内部二维 grid。处理顺序不能随意交换：必须先展开合并单元格，
     * 再判断空列和展平表头，否则合并区域中除左上角之外的列可能被错误删除。
     *
     * @param sheet      POI sheet
     * @param formatter  DataFormatter 实例（线程不安全，调用方持有）
     * @param evaluator  公式求值器，可空
     * @param headerRows 表头占用的行数，{@code >= 1}
     * @return 规范化结果；空 sheet 返回空表
     */
    public static NormalizedTable normalize(Sheet sheet,
                                            DataFormatter formatter,
                                            FormulaEvaluator evaluator,
                                            int headerRows) {
        if (headerRows < 1) {
            throw new IllegalArgumentException("headerRows must be >= 1, got " + headerRows);
        }

        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < 0) {
            return NormalizedTable.empty();
        }

        int maxCol = computeMaxColumns(sheet, lastRowNum);
        if (maxCol == 0) {
            return NormalizedTable.empty();
        }

        // 步骤 1: 读取 sheet 到二维 grid（已应用 hyperlink wrap 与公式回退）
        String[][] grid = readGrid(sheet, lastRowNum, maxCol, formatter, evaluator);

        // 步骤 2: 展开合并单元格（grid 上原地填充）
        expandMergedRegions(grid, sheet.getMergedRegions(), lastRowNum, maxCol);

        // 步骤 3: 丢弃全空列（表头与数据全程为空的列，含中间与尾部）
        int[] cols = selectNonEmptyColumns(grid, 0, lastRowNum, maxCol);
        if (cols.length == 0) {
            return NormalizedTable.empty();
        }

        // 步骤 4: 前 headerRows 行展平为表头，其余收集为数据行
        int effectiveHeaderRows = Math.min(headerRows, lastRowNum + 1);
        List<String> headers = flattenHeaders(grid, 0, effectiveHeaderRows, cols);
        List<List<String>> rows = effectiveHeaderRows <= lastRowNum
                ? collectDataRows(grid, effectiveHeaderRows, lastRowNum, cols)
                : List.of();
        return new NormalizedTable(headers, rows);
    }

    /**
     * 计算 Sheet 内实际出现过的最大列数（跨所有 Row）。
     * <p>
     * POI 的 {@code getLastCellNum()} 返回“最后单元格索引 + 1”，空行返回负值，因此可直接与
     * 当前最大宽度比较。
     */
    private static int computeMaxColumns(Sheet sheet, int lastRowNum) {
        int maxCol = 0;
        for (int r = 0; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int lastCellNum = row.getLastCellNum();
            if (lastCellNum > maxCol) {
                maxCol = lastCellNum;
            }
        }
        return maxCol;
    }

    /**
     * 选出非全空列：返回在闭区间 [startRow, endRow] 至少有一个非空 cell 的列索引。
     * <p>
     * 全空列（表头与数据全程为空）不携带信息一律丢弃，既裁尾部空列，也裁夹在数据列中间的空列；
     * 仅表头空但有数据的列保留（数据本身有意义）
     */
    private static int[] selectNonEmptyColumns(String[][] grid, int startRow, int endRow, int maxCol) {
        List<Integer> kept = new ArrayList<>(maxCol);
        for (int c = 0; c < maxCol; c++) {
            boolean hasValue = false;
            for (int r = startRow; r <= endRow; r++) {
                String v = grid[r][c];
                if (v != null && !v.isEmpty()) {
                    hasValue = true;
                    break;
                }
            }
            if (hasValue) {
                kept.add(c);
            }
        }
        int[] cols = new int[kept.size()];
        for (int i = 0; i < cols.length; i++) {
            cols[i] = kept.get(i);
        }
        return cols;
    }

    /**
     * 读取 Sheet 到二维数组，同时应用显示值格式化、超链接恢复和删除线标记。
     * <p>
     * 用 {@link Row.MissingCellPolicy#RETURN_NULL_AND_BLANK}：不存在的 cell 返回 null，
     * BLANK cell 仍返回 cell 实例（可携带 hyperlink）。这样空文字 + 超链接的场景能正确解析
     */
    private static String[][] readGrid(Sheet sheet, int lastRowNum, int maxCol,
                                       DataFormatter formatter, FormulaEvaluator evaluator) {
        String[][] grid = new String[lastRowNum + 1][maxCol];
        for (int r = 0; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            for (int c = 0; c < maxCol; c++) {
                // 无物理 Cell 的位置也必须写空串，保证整个 grid 是规则矩形。
                String value = "";
                if (row != null) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    if (cell != null) {
                        String formatted = ExcelValueFormatter.format(cell, formatter, evaluator);
                        value = ExcelHyperlinkResolver.wrap(formatted, cell);
                        // 软删除约定：非空且划删除线的 cell，用 ~~值~~ 包裹原值
                        if (!value.isEmpty() && ExcelValueFormatter.isStrikethrough(cell)) {
                            value = STRIKETHROUGH_WRAP + value + STRIKETHROUGH_WRAP;
                        }
                    }
                }
                grid[r][c] = value;
            }
        }
        return grid;
    }

    /**
     * 把合并区域的左上角值复制到区域内所有 cell 位置。
     * <p>
     * 复制的目的不是还原 Excel 视觉布局，而是让后续按行切分出的每个 Chunk 都能自包含地携带
     * 分组值。越出当前有效网格的区域会被裁剪；左上角为空的区域保持为空。
     */
    private static void expandMergedRegions(String[][] grid,
                                            List<CellRangeAddress> mergedRegions,
                                            int lastRowNum, int maxCol) {
        if (mergedRegions == null || mergedRegions.isEmpty()) {
            return;
        }
        for (CellRangeAddress region : mergedRegions) {
            int firstRow = region.getFirstRow();
            int firstCol = region.getFirstColumn();
            if (firstRow < 0 || firstRow > lastRowNum || firstCol < 0 || firstCol >= maxCol) {
                continue;
            }
            String value = grid[firstRow][firstCol];
            if (value == null || value.isEmpty()) {
                continue;
            }
            int rEnd = Math.min(region.getLastRow(), lastRowNum);
            int cEnd = Math.min(region.getLastColumn(), maxCol - 1);
            for (int r = firstRow; r <= rEnd; r++) {
                for (int c = firstCol; c <= cEnd; c++) {
                    grid[r][c] = value;
                }
            }
        }
    }

    /**
     * 展平前 N 行为单行表头。
     * <p>
     * 同一列从上到下的非空标题用 {@link #HEADER_SEPARATOR} 拼接。只跳过“相邻重复值”，
     * 用于消除合并单元格展开造成的重复；非相邻的相同标题仍会保留。
     */
    private static List<String> flattenHeaders(String[][] grid, int startRow, int headerRows, int[] cols) {
        List<String> headers = new ArrayList<>(cols.length);
        for (int c : cols) {
            StringBuilder sb = new StringBuilder();
            String prev = null;
            for (int r = startRow; r < startRow + headerRows; r++) {
                String v = grid[r][c];
                if (v == null || v.isEmpty()) {
                    continue;
                }
                if (v.equals(prev)) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append(HEADER_SEPARATOR);
                }
                sb.append(v);
                prev = v;
            }
            headers.add(sb.toString());
        }
        return headers;
    }

    /**
     * 收集数据行并跳过全空行。
     * <p>
     * 每一行严格按筛选后的 cols 顺序构造，因此 row 与 headers 的列索引保持一致。
     */
    private static List<List<String>> collectDataRows(String[][] grid, int startRow,
                                                      int endRow, int[] cols) {
        List<List<String>> rows = new ArrayList<>();
        for (int r = startRow; r <= endRow; r++) {
            List<String> rowValues = new ArrayList<>(cols.length);
            boolean allEmpty = true;
            for (int c : cols) {
                String v = grid[r][c];
                if (v != null && !v.isEmpty()) {
                    allEmpty = false;
                }
                rowValues.add(v == null ? "" : v);
            }
            if (!allEmpty) {
                rows.add(rowValues);
            }
        }
        return rows;
    }
}

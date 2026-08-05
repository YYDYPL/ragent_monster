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

package com.hjs.study.ragent.core.chunk.strategy;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.hjs.study.ragent.core.chunk.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * legacy 纯文本结构感知分块器（Markdown 友好）。
 * <p>
 * 该类处理的是完整 String，不是 Parser 的强类型 Block。它用轻量正则和线性扫描识别标题、自然
 * 段、围栏代码以及独占一行的图片/链接，再只在这些块边界之间打包。与 CommonMark AST 相比，
 * 这种实现依赖少、保留原始子串，但不理解嵌套列表、表格 AST 等复杂语法。
 * <p>
 * 主体切分不会修改 LF 规范化后的内容；入口会先把 CRLF/CR 统一为 LF。配置中的 max 是软结构
 * 预算：单个原子块超长、为满足 min 吸收下一块、或合并过小末块时都可能超过 max。overlap 则
 * 直接复制上一结果的尾部字符，可能从结构块中间开始，并会额外扩大下一 Chunk。
 * <p>
 * 有 Parser Block 时统一入口不会调用本类，而会使用 block-aware 专用 Chunker。
 */
@Component
public class StructureAwareTextChunker implements ChunkingStrategy {

    /** ATX 风格一到六级标题；不识别 Setext 标题。 */
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");

    /** 三反引号围栏的开始/结束行；不识别波浪线围栏。 */
    private static final Pattern CODE_FENCE = Pattern.compile("^```.*$");

    /** 独占一行的 Markdown 图片，可带 title，作为不可拆原子块。 */
    private static final Pattern ATOMIC_IMAGE = Pattern.compile("^!\\[[^]]*]\\([^)]+\\)(?:\\s*\"[^\"]*\")?\\s*$");

    /** 独占一行的 Markdown 链接，作为不可拆原子块。 */
    private static final Pattern ATOMIC_LINK = Pattern.compile("^\\[[^]]+]\\([^)]+\\)\\s*$");

    /** 返回工厂注册键 STRUCTURE_AWARE。 */
    @Override
    public ChunkingMode getType() {
        return ChunkingMode.STRUCTURE_AWARE;
    }

    /**
     * 扫描 Markdown 边界、按预算打包并物化 Chunk。
     *
     * @param text legacy 纯文本；空白返回空列表
     * @param config 与 STRUCTURE_AWARE 匹配的 TextBoundaryOptions
     * @return 保持文本顺序、index 从 0 开始的 Chunk
     */
    @Override
    public List<VectorChunk> chunk(String text, ChunkingOptions config) {
        if (StrUtil.isBlank(text)) return List.of();

        // 统一行尾：Windows \r\n → \n，老 Mac \r → \n，避免 \r 残留导致空行/标题识别失败
        text = text.replace("\r\n", "\n").replace("\r", "\n");

        TextBoundaryOptions opts = (TextBoundaryOptions) config;
        // 当前 target 主要参与末块是否过小的判断；主体贪心打包以 max 为上限。
        int effectiveTarget = opts.targetChars();
        int effectiveMax = opts.maxChars();
        int effectiveMin = opts.minChars();
        int effectiveOverlap = opts.overlapChars();

        // 1) 扫描成“块”（记录原文的 start/end 下标，确保输出 substring 完全等于原文）
        List<Block> blocks = segmentToBlocks(text);

        if (blocks.isEmpty()) {
            VectorChunk chunk = VectorChunk.builder()
                    .content(text)
                    .index(0)
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .build();
            return List.of(chunk); // 极端兜底：整体作为一个块
        }

        // 2) 依据 min/target/max 打包成 chunk（只在块边界切分）
        List<int[]> ranges = packBlocksToChunks(blocks, text.length(), effectiveMin, effectiveTarget, effectiveMax);

        // overlap 是上一范围尾部的原始字符副本；主体 range 仍只在识别出的块边界结束。
        List<VectorChunk> out = materialize(text, ranges, effectiveOverlap);

        // materialize 已生成临时 DTO；这里重建确保最终 ID、index 连续且只保留对外字段。
        for (int i = 0; i < out.size(); i++) {
            VectorChunk chunk = VectorChunk.builder()
                    .content(out.get(i).getContent())
                    .index(i)
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .build();
            out.set(i, chunk);
        }
        return out;
    }

    // ----------- 仅供本算法使用的轻量文本块模型 -----------
    /**
     * 原文中的半开区间 [start,end)，只保存类型和坐标，不复制正文。
     */
    @Getter
    @ToString
    @AllArgsConstructor
    private static class Block {
        /** 影响边界识别的四类最小语法单元。 */
        enum Kind {HEADING, CODE, ATOMIC, PARA}

        final Kind kind;
        final int start;   // 在原文中的起始（含）
        final int end;     // 在原文中的结束（不含）
    }

    // ----------- 1) 线性扫描生成块 -----------
    /**
     * 按行扫描输入，生成不重叠且保持顺序的语法块。
     * <p>
     * 围栏内所有内容归入 CODE；未闭合围栏一直延伸到文末。空行自身不单独建块，随后由
     * coalesceTrailingBlanks 归入前一个块，以便 substring 恢复原貌。
     */
    private List<Block> segmentToBlocks(String text) {
        List<Block> blocks = new ArrayList<>();
        int n = text.length();
        int pos = 0;

        boolean inFence = false;
        int fenceStart = -1;

        boolean inPara = false;
        int paraStart = -1;

        while (pos < n) {
            int lineEnd = indexOfNl(text, pos);
            // [pos, lineEnd) 不含换行字符；lineEndNl = 包含换行（若有）
            int lineEndNl = lineEnd < n && text.charAt(lineEnd) == '\n' ? lineEnd + 1 : lineEnd;
            String line = text.substring(pos, lineEnd);

            String trimmed = trimRightKeepLeft(line); // 不改左侧空白，保留原貌；右侧空白不影响判断

            if (!inFence && CODE_FENCE.matcher(trimmed).matches()) {
                // 先把正在积累的段落收尾
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                // 进入代码围栏
                inFence = true;
                fenceStart = pos;
                pos = lineEndNl;
                continue;
            }

            if (inFence) {
                // 直到遇到 fence 结束行
                if (CODE_FENCE.matcher(trimmed).matches()) {
                    // 包含结束 fence 行
                    blocks.add(new Block(Block.Kind.CODE, fenceStart, lineEndNl));
                    inFence = false;
                }
                pos = lineEndNl;
                continue;
            }

            // 空行 => 段落边界
            if (trimmed.isEmpty()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                // 空行本身并入前一块或下一块？——保持原貌：把空行并入前一块（若无前一块，则作为 0 长度过渡）
                pos = lineEndNl;
                continue;
            }

            // 标题/原子行（图片/链接）都作为独立块
            if (HEADING.matcher(trimmed).matches()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                blocks.add(new Block(Block.Kind.HEADING, pos, lineEndNl));
                pos = lineEndNl;
                continue;
            }
            if (ATOMIC_IMAGE.matcher(trimmed).matches() || ATOMIC_LINK.matcher(trimmed).matches()) {
                if (inPara) {
                    blocks.add(new Block(Block.Kind.PARA, paraStart, pos));
                    inPara = false;
                }
                blocks.add(new Block(Block.Kind.ATOMIC, pos, lineEndNl));
                pos = lineEndNl;
                continue;
            }

            // 其他：并入当前段落
            if (!inPara) {
                inPara = true;
                paraStart = pos;
            }
            pos = lineEndNl;
        }

        // 收尾
        if (inFence) {
            // 未闭合 fence：将剩余部分作为 CODE（保持原样）
            blocks.add(new Block(Block.Kind.CODE, fenceStart, n));
        } else if (inPara) {
            blocks.add(new Block(Block.Kind.PARA, paraStart, n));
        }
        return coalesceTrailingBlanks(blocks, text);
    }

    /**
     * 把相邻语法块之间纯空白区间扩入前一块，不生成只含空白的 Block。
     * <p>
     * 只调整 end 坐标，不改写原文；最后物化时空行仍会出现在 Chunk 中。
     */
    private List<Block> coalesceTrailingBlanks(List<Block> blocks, String text) {
        if (blocks.isEmpty()) return blocks;
        List<Block> out = new ArrayList<>();
        Block prev = blocks.get(0);
        for (int i = 1; i < blocks.size(); i++) {
            Block cur = blocks.get(i);
            if (isAllBlank(text, prev.end, cur.start)) {
                // 把中间空白并入 prev，但别丢掉 cur
                prev = new Block(prev.kind, prev.start, cur.start);
            }
            // 无论是否并入空白，prev 都该进结果，然后向前推进
            out.add(prev);
            prev = cur;
        }
        out.add(prev);
        return out;
    }

    // ----------- 2) 打包成 chunk（仅在块边界切） -----------
    /**
     * 将语法块贪心打包为原文坐标范围。
     * <p>
     * 能放入 max 就继续吸收；若当前结果小于 min，即使下一块导致超限也“忍一次”以避免碎块。
     * 单个起始块本身超过 max 时也保持完整。最后一个范围过小时，可与前一范围合并到最多 max*2。
     * target 只参与末块“小”的阈值 {@code min(min, target/2)}。
     */
    private List<int[]> packBlocksToChunks(List<Block> blocks, int textLen, int min, int target, int max) {
        List<int[]> ranges = new ArrayList<>();
        int i = 0;
        while (i < blocks.size()) {
            int chunkStart = blocks.get(i).start;
            int chunkEnd = blocks.get(i).end; // 不含
            int size = chunkEnd - chunkStart;

            int j = i + 1;
            while (j < blocks.size()) {
                Block b = blocks.get(j);
                int afterAdd = (b.end - chunkStart); // 等同于 size + nextSize + 中间空白（已包含）

                if (afterAdd <= max) {
                    // 还能加
                    chunkEnd = b.end;
                    size = afterAdd;
                    j++;
                } else {
                    // 超过 max：若当前 size < min，则“忍一次超限”，把这个块也吸进去（保证不要太小）
                    if (size < min) {
                        chunkEnd = b.end;
                        size = afterAdd;
                        j++;
                    }
                    break;
                }
            }

            ranges.add(new int[]{chunkStart, chunkEnd});
            i = j;
        }

        // 若最后一个 chunk 明显过小，尝试与前一个合并（仍不跨越 max 过多）
        if (ranges.size() >= 2) {
            int[] last = ranges.get(ranges.size() - 1);
            if (last[1] - last[0] < Math.min(min, target / 2)) {
                int[] prev = ranges.get(ranges.size() - 2);
                if (last[1] - prev[0] <= max * 2) { // 放宽一下，尽量合并到可接受大小
                    prev[1] = last[1];
                    ranges.remove(ranges.size() - 1);
                }
            }
        }
        return ranges;
    }

    // ----------- 3) 物化为 Chunk，必要时追加 overlap（复制原文尾部） -----------
    /**
     * 从半开坐标区间截取正文，并把上一范围末尾最多 overlap 个字符前置到下一 Chunk。
     * <p>
     * 重叠字符来自上一范围的原始正文，不包含上一 Chunk 已经前置的重叠，避免递归膨胀。此方法生成
     * 的 ID 会在外层最终编号步骤中替换，当前只承担正文物化。
     */
    private List<VectorChunk> materialize(String text, List<int[]> ranges, int overlap) {
        if (ranges.isEmpty()) return List.of();
        List<VectorChunk> out = new ArrayList<>();
        String prevTail = null;

        for (int k = 0; k < ranges.size(); k++) {
            int s = ranges.get(k)[0];
            int e = ranges.get(k)[1];
            String body = text.substring(s, e);
            if (overlap > 0 && prevTail != null && !prevTail.isEmpty()) {
                body = prevTail + body;
            }

            VectorChunk chunk = VectorChunk.builder()
                    .content(body)
                    .index(k)
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .build();
            out.add(chunk);

            // 计算下一块的 overlap 尾部（完全来自本 chunk 原文结尾）
            if (overlap > 0) {
                prevTail = tailByChars(text.substring(s, e), overlap);
            }
        }
        return out;
    }

    // ----------- 无状态字符串小工具 -----------
    /** 返回 from 之后首个 LF 的索引；不存在时返回字符串长度。 */
    private int indexOfNl(String s, int from) {
        int p = s.indexOf('\n', from);
        return p < 0 ? s.length() : p;
    }

    /** 只去掉一行右侧空白用于语法判断，左侧缩进保持不变。 */
    private String trimRightKeepLeft(String s) {
        int r = s.length();
        while (r > 0 && Character.isWhitespace(s.charAt(r - 1)) && s.charAt(r - 1) != '\n' && s.charAt(r - 1) != '\r') {
            r--;
        }
        return s.substring(0, r);
    }

    /** 判断半开区间是否只包含空格、Tab 和换行。 */
    private boolean isAllBlank(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!(c == ' ' || c == '\t' || c == '\r' || c == '\n')) return false;
        }
        return true;
    }

    /** 返回最多 n 个末尾字符；n 非正时返回空串。 */
    private String tailByChars(String s, int n) {
        if (n <= 0) return "";
        int len = s.length();
        return len <= n ? s : s.substring(len - n);
    }
}

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

package com.hjs.study.ragent.core.chunk.blockaware;

import com.hjs.study.ragent.core.parser.model.HeadingBlock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 标题层级状态转换器。
 * <p>
 * Heading 本身不产生 VectorChunk，而是修改 Dispatcher 局部持有的章节路径；随后的段落、表格、
 * 图片、代码和列表都会复制该路径。语义是 H_N 保留当前路径前 N-1 项，再追加自己：
 * <ul>
 *   <li>H1 "A" → ["A"]</li>
 *   <li>... 再来 H2 "B" → ["A", "B"]</li>
 *   <li>... 再来 H2 "C" → ["A", "C"]（同级替换）</li>
 *   <li>... 再来 H1 "D" → ["D"]（顶级重置）</li>
 *   <li>... 再来 H3 "E" → ["D", "E"]（不会制造虚假的空 H2 层级）</li>
 * </ul>
 * <p>
 * 这是无状态纯转换组件，返回 {@link List#copyOf(java.util.Collection)} 生成的不可变新列表，不会
 * 修改 currentPath。调用方约定 currentPath 非 null。
 */
@Component
public class HeadingHandler {

    /**
     * 根据新标题计算下一份章节路径。
     *
     * @param currentPath 当前 outlinePath（不可变）
     * @param heading     新的 HeadingBlock
     * @return 新的 outlinePath（不可变）
     */
    public List<String> update(List<String> currentPath, HeadingBlock heading) {
        if (heading == null) {
            return currentPath;
        }
        // 非法的 0/负数层级按 H1 处理，避免 keep 计算出现负数。
        int targetLevel = Math.max(1, heading.level());
        int keep = Math.min(currentPath.size(), targetLevel - 1);

        List<String> next = new ArrayList<>(keep + 1);
        for (int i = 0; i < keep; i++) {
            next.add(currentPath.get(i));
        }
        next.add(heading.text() == null ? "" : heading.text());
        return List.copyOf(next);
    }
}

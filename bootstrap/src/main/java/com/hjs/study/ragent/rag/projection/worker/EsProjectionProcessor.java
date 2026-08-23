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

package com.hjs.study.ragent.rag.projection.worker;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.hjs.study.ragent.rag.core.keyword.EsKeywordIndexService;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.model.ProjectionAction;
import com.hjs.study.ragent.rag.projection.model.ProjectionTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "es")
public class EsProjectionProcessor implements ProjectionProcessor {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final ProjectionStateMapper stateMapper;
    private final EsKeywordIndexService indexService;

    @Override
    public ProjectionTarget target() {
        return ProjectionTarget.ELASTICSEARCH;
    }

    @Override
    public ProjectionExecutionResult process(ProjectionStateDO state) {
        ProjectionAction action = ProjectionAction.valueOf(state.getAction());
        if (action == ProjectionAction.PURGE_KB) {
            indexService.deleteByCollection(state.getCollectionName());
            return ProjectionExecutionResult.success();
        }
        if (action == ProjectionAction.DELETE) {
            indexService.deleteDocumentIndex(state.getCollectionName(), state.getDocId());
            return ProjectionExecutionResult.success();
        }

        KnowledgeDocumentDO document = documentMapper.selectById(state.getDocId());
        if (document == null) {
            throw new ProjectionExecutionException("UPSERT 源文档不存在", false, "ES_LOAD_SOURCE");
        }
        if (!state.getDesiredVersion().equals(document.getContentVersion())) {
            ProjectionStateDO latest = stateMapper.selectById(state.getId());
            if (latest != null && !state.getDesiredVersion().equals(latest.getDesiredVersion())) {
                // 执行期间又发生编辑，让外层版本 CAS 把任务恢复为 PENDING。
                return ProjectionExecutionResult.success();
            }
            throw new ProjectionExecutionException("文档版本与任务期望版本不一致", false, "ES_LOAD_SOURCE");
        }
        List<KnowledgeChunkDO> chunks = chunkMapper.selectList(Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                .eq(KnowledgeChunkDO::getDocId, state.getDocId())
                .eq(KnowledgeChunkDO::getEnabled, 1)
                .orderByAsc(KnowledgeChunkDO::getChunkIndex));
        indexService.writeDocumentVersion(state.getKbId(), state.getCollectionName(), state.getDocId(),
                state.getDesiredVersion(), chunks);
        return ProjectionExecutionResult.success();
    }

    @Override
    public void afterSuccess(ProjectionStateDO state, ProjectionExecutionResult result) {
        if (ProjectionAction.UPSERT.name().equals(state.getAction())) {
            indexService.deleteOldDocumentVersions(state.getCollectionName(), state.getDocId(),
                    state.getDesiredVersion());
        }
    }
}

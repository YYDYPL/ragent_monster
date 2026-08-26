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
package com.hjs.study.ragent.rag.projection;

import com.hjs.study.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.hjs.study.ragent.rag.projection.config.ProjectionProperties;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionAttemptMapper;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionBatchMapper;
import com.hjs.study.ragent.rag.projection.dao.mapper.ProjectionStateMapper;
import com.hjs.study.ragent.rag.projection.service.ProjectionAdminService;
import com.hjs.study.ragent.rag.projection.service.ProjectionCoordinator;
import com.hjs.study.ragent.rag.projection.worker.ProjectionJobExecutor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProjectionAdminServiceTest {

    private final ProjectionStateMapper stateMapper = mock(ProjectionStateMapper.class);
    private final ProjectionAdminService service = new ProjectionAdminService(
            stateMapper,
            mock(ProjectionAttemptMapper.class),
            mock(ProjectionBatchMapper.class),
            mock(KnowledgeDocumentMapper.class),
            mock(ProjectionCoordinator.class),
            mock(ProjectionJobExecutor.class),
            mock(ProjectionProperties.class));

    @Test
    void pageAllowsMissingOptionalTargetFilter() {
        assertThatCode(() -> service.page(1, 20, null, null, null, null, null, null))
                .doesNotThrowAnyException();

        verify(stateMapper).selectPage(any(), any());
    }

    @Test
    void pageAllowsBlankOptionalTargetFilter() {
        assertThatCode(() -> service.page(1, 20, "  ", null, null, null, null, null))
                .doesNotThrowAnyException();
    }
}

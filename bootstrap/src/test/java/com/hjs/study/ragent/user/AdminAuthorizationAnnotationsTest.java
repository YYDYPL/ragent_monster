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
package com.hjs.study.ragent.user;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.hjs.study.ragent.admin.controller.DashboardController;
import com.hjs.study.ragent.audit.controller.BizChangeLogController;
import com.hjs.study.ragent.ingestion.controller.IngestionPipelineController;
import com.hjs.study.ragent.ingestion.controller.IngestionTaskController;
import com.hjs.study.ragent.knowledge.controller.KnowledgeBaseController;
import com.hjs.study.ragent.knowledge.controller.KnowledgeChunkController;
import com.hjs.study.ragent.knowledge.controller.KnowledgeDocumentController;
import com.hjs.study.ragent.rag.controller.GraphController;
import com.hjs.study.ragent.rag.controller.IntentTreeController;
import com.hjs.study.ragent.rag.controller.QueryTermMappingController;
import com.hjs.study.ragent.rag.controller.RAGSettingsController;
import com.hjs.study.ragent.rag.controller.RagTraceController;
import com.hjs.study.ragent.rag.controller.SampleQuestionController;
import com.hjs.study.ragent.rag.eval.EvalController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthorizationAnnotationsTest {

    @Test
    void adminControllersRequireAdminRole() {
        List<Class<?>> adminControllers = List.of(
                DashboardController.class,
                BizChangeLogController.class,
                IngestionPipelineController.class,
                IngestionTaskController.class,
                KnowledgeBaseController.class,
                KnowledgeChunkController.class,
                IntentTreeController.class,
                QueryTermMappingController.class,
                RagTraceController.class,
                GraphController.class,
                RAGSettingsController.class,
                EvalController.class
        );

        adminControllers.forEach(controller -> assertAdminOnly(controller.getAnnotation(SaCheckRole.class)));
    }

    @Test
    void documentSourceReadsRemainAvailableButManagementRequiresAdmin() throws Exception {
        assertAdminOnly(method(KnowledgeDocumentController.class, "upload").getAnnotation(SaCheckRole.class));
        assertAdminOnly(method(KnowledgeDocumentController.class, "startChunk").getAnnotation(SaCheckRole.class));
        assertAdminOnly(method(KnowledgeDocumentController.class, "delete").getAnnotation(SaCheckRole.class));
        assertAdminOnly(method(KnowledgeDocumentController.class, "update").getAnnotation(SaCheckRole.class));
        assertAdminOnly(method(KnowledgeDocumentController.class, "page").getAnnotation(SaCheckRole.class));
        assertAdminOnly(method(KnowledgeDocumentController.class, "search").getAnnotation(SaCheckRole.class));
        assertAdminOnly(method(KnowledgeDocumentController.class, "enable").getAnnotation(SaCheckRole.class));
        assertAdminOnly(method(KnowledgeDocumentController.class, "getChunkLogs").getAnnotation(SaCheckRole.class));

        assertThat(method(KnowledgeDocumentController.class, "get").getAnnotation(SaCheckRole.class)).isNull();
        assertThat(method(KnowledgeDocumentController.class, "preview").getAnnotation(SaCheckRole.class)).isNull();
        assertThat(method(KnowledgeDocumentController.class, "file").getAnnotation(SaCheckRole.class)).isNull();
    }

    @Test
    void publicSampleQuestionsRemainAvailableButManagementRequiresAdmin() throws Exception {
        assertThat(method(SampleQuestionController.class, "listSampleQuestions").getAnnotation(SaCheckRole.class)).isNull();
        assertAdminOnly(method(SampleQuestionController.class, "pageQuery").getAnnotation(SaCheckRole.class));
        assertAdminOnly(method(SampleQuestionController.class, "queryById").getAnnotation(SaCheckRole.class));
        assertAdminOnly(method(SampleQuestionController.class, "create").getAnnotation(SaCheckRole.class));
        assertAdminOnly(method(SampleQuestionController.class, "update").getAnnotation(SaCheckRole.class));
        assertAdminOnly(method(SampleQuestionController.class, "delete").getAnnotation(SaCheckRole.class));
    }

    private Method method(Class<?> type, String name) {
        return List.of(type.getDeclaredMethods()).stream()
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private void assertAdminOnly(SaCheckRole annotation) {
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly("admin");
    }
}

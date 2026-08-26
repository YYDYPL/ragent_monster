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
package com.hjs.study.ragent.rag.core.intent;

import com.hjs.study.ragent.rag.core.rewrite.RewriteResult;
import com.hjs.study.ragent.rag.dto.SubQuestionIntent;
import com.hjs.study.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentResolverTest {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void weakSystemIntentMustNotBypassGlobalKnowledgeRetrieval() {
        IntentClassifier classifier = question -> List.of(score(0.35, IntentKind.SYSTEM));
        IntentResolver resolver = new IntentResolver(classifier, DIRECT_EXECUTOR);

        List<SubQuestionIntent> result = resolver.resolve(
                new RewriteResult("销售业绩月报中的角色权限与数据范围有什么关系？", List.of()));

        assertEquals(1, result.size());
        assertTrue(result.get(0).nodeScores().isEmpty());
        assertTrue(result.stream().noneMatch(item -> resolver.isSystemOnly(item.nodeScores())));
    }

    @Test
    void confidentSystemIntentRemainsEligibleForDirectResponse() {
        IntentClassifier classifier = question -> List.of(score(0.80, IntentKind.SYSTEM));
        IntentResolver resolver = new IntentResolver(classifier, DIRECT_EXECUTOR);

        List<SubQuestionIntent> result = resolver.resolve(
                new RewriteResult("我想申请产品试用", List.of()));

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).nodeScores().size());
        assertTrue(resolver.isSystemOnly(result.get(0).nodeScores()));
    }

    private NodeScore score(double value, IntentKind kind) {
        IntentNode node = IntentNode.builder()
                .id("intent")
                .name("intent")
                .kind(kind)
                .build();
        return NodeScore.builder().node(node).score(value).build();
    }
}

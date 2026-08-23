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

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ProjectionExecutionResult {
    boolean complete;
    String phase;
    String externalTrackId;
    Long externalTrackVersion;
    String externalDocumentId;

    public static ProjectionExecutionResult success() {
        return ProjectionExecutionResult.builder().complete(true).phase("COMPLETED").build();
    }

    public static ProjectionExecutionResult waiting(String phase, String trackId, String documentId) {
        return ProjectionExecutionResult.builder()
                .complete(false)
                .phase(phase)
                .externalTrackId(trackId)
                .externalDocumentId(documentId)
                .build();
    }

    public static ProjectionExecutionResult waiting(String phase, String trackId, Long trackVersion,
                                                    String documentId) {
        return ProjectionExecutionResult.builder()
                .complete(false)
                .phase(phase)
                .externalTrackId(trackId)
                .externalTrackVersion(trackVersion)
                .externalDocumentId(documentId)
                .build();
    }

    public static ProjectionExecutionResult successWithExternal(String trackId, Long trackVersion,
                                                                String documentId) {
        return ProjectionExecutionResult.builder()
                .complete(true)
                .phase("COMPLETED")
                .externalTrackId(trackId)
                .externalTrackVersion(trackVersion)
                .externalDocumentId(documentId)
                .build();
    }
}

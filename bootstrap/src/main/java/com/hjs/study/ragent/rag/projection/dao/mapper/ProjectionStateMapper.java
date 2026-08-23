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

package com.hjs.study.ragent.rag.projection.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface ProjectionStateMapper extends BaseMapper<ProjectionStateDO> {

    /**
     * 合并同一实体的连续变更。正在执行的任务保留租约，由执行器在版本 CAS 失败后重新排队。
     */
    @Insert("INSERT INTO t_rag_projection_state "
            + "(id, target, scope_type, entity_id, kb_id, collection_name, doc_id, desired_version, "
            + "applied_version, action, status, config_fingerprint, desired_time, create_time, update_time) "
            + "VALUES (#{state.id}, #{state.target}, #{state.scopeType}, #{state.entityId}, #{state.kbId}, "
            + "#{state.collectionName}, #{state.docId}, #{state.desiredVersion}, -1, #{state.action}, "
            + "'PENDING', #{state.configFingerprint}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (target, scope_type, entity_id) DO UPDATE SET "
            + "kb_id = EXCLUDED.kb_id, collection_name = EXCLUDED.collection_name, doc_id = EXCLUDED.doc_id, "
            + "desired_version = EXCLUDED.desired_version, action = EXCLUDED.action, "
            + "status = CASE WHEN t_rag_projection_state.status IN ('RUNNING', 'WAIT_EXTERNAL') "
            + "THEN t_rag_projection_state.status ELSE 'PENDING' END, "
            + "phase = CASE WHEN t_rag_projection_state.status IN ('RUNNING', 'WAIT_EXTERNAL') "
            + "THEN t_rag_projection_state.phase ELSE NULL END, "
            + "next_retry_time = NULL, last_error = NULL, completed_at = NULL, "
            + "config_fingerprint = EXCLUDED.config_fingerprint, desired_time = CURRENT_TIMESTAMP, "
            + "update_time = CURRENT_TIMESTAMP")
    int mergeDesiredState(@Param("state") ProjectionStateDO state);
}

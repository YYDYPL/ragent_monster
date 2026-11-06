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

package com.hjs.study.ragent.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hjs.study.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentDO> {

    /**
     * 在业务事务内原子递增内容版本，避免并发编辑丢失版本。
     */
    @Update("UPDATE t_knowledge_document "
            + "SET content_version = content_version + 1, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{docId} AND deleted = 0")
    int incrementContentVersion(@Param("docId") String docId);
}

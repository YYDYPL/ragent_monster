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

package com.hjs.study.ragent.rag.projection.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** 可重建检索投影的期望状态。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_rag_projection_state")
public class ProjectionStateDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String target;
    private String scopeType;
    private String entityId;
    private String kbId;
    private String collectionName;
    private String docId;
    private Long desiredVersion;
    private Long appliedVersion;
    private String action;
    private String status;
    private String phase;
    private Integer attemptCount;
    private Date nextRetryTime;
    private String leaseOwner;
    private Date leaseUntil;
    private String lastError;
    private String externalTrackId;
    private Long externalTrackVersion;
    private String externalDocumentId;
    private String configFingerprint;
    private Date desiredTime;
    private Date startedAt;
    private Date completedAt;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}

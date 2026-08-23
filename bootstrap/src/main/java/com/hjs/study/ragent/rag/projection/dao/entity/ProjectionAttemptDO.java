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

/** 单次投影执行审计，错误字段只允许保存脱敏后的摘要。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_rag_projection_attempt")
public class ProjectionAttemptDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String projectionId;
    private String target;
    private Long desiredVersion;
    private Integer attemptNo;
    private String result;
    private String phase;
    private String errorSummary;
    private Date startedAt;
    private Date finishedAt;
    private Long durationMs;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}

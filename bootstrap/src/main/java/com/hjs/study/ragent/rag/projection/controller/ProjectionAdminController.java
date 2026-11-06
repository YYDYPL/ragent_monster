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

package com.hjs.study.ragent.rag.projection.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hjs.study.ragent.framework.convention.Result;
import com.hjs.study.ragent.framework.web.Results;
import com.hjs.study.ragent.rag.projection.controller.request.ProjectionBatchRequest;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionAttemptDO;
import com.hjs.study.ragent.rag.projection.dao.entity.ProjectionStateDO;
import com.hjs.study.ragent.rag.projection.service.ProjectionAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/projections")
public class ProjectionAdminController {

    private final ProjectionAdminService service;

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        requireAdmin();
        return Results.success(service.summary());
    }

    @GetMapping
    public Result<IPage<ProjectionStateDO>> page(@RequestParam(defaultValue = "1") int current,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(required = false) String target,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String kbId,
                                                 @RequestParam(required = false) String docId,
                                                 @RequestParam(required = false)
                                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date from,
                                                 @RequestParam(required = false)
                                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date to) {
        requireAdmin();
        return Results.success(service.page(current, size, target, status, kbId, docId, from, to));
    }

    @GetMapping("/{id}/attempts")
    public Result<List<ProjectionAttemptDO>> attempts(@PathVariable String id) {
        requireAdmin();
        return Results.success(service.attempts(id));
    }

    @PostMapping("/rebuild")
    public ResponseEntity<Result<Map<String, String>>> rebuild(
            @RequestBody(required = false) ProjectionBatchRequest request) {
        requireAdmin();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Results.success(Map.of("batchId", service.rebuild(request))));
    }

    @PostMapping("/reconcile")
    public ResponseEntity<Result<Map<String, String>>> reconcile(
            @RequestBody(required = false) ProjectionBatchRequest request) {
        requireAdmin();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Results.success(Map.of("batchId", service.reconcile(request))));
    }

    @PostMapping("/{id}/retry")
    public Result<Void> retry(@PathVariable String id) {
        requireAdmin();
        service.retry(id);
        return Results.success();
    }

    private void requireAdmin() {
        StpUtil.checkRole("admin");
    }
}

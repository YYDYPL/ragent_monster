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
import com.hjs.study.ragent.framework.convention.Result;
import com.hjs.study.ragent.framework.web.Results;
import com.hjs.study.ragent.rag.projection.dao.entity.GraphWorkspaceBindingDO;
import com.hjs.study.ragent.rag.projection.service.GraphWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/kg/workspaces")
public class GraphWorkspaceAdminController {

    private final GraphWorkspaceService service;

    @GetMapping
    public Result<List<GraphWorkspaceBindingDO>> list() {
        requireAdmin();
        return Results.success(service.list());
    }

    @PostMapping("/{kbId}/validate")
    public Result<GraphWorkspaceBindingDO> validate(@PathVariable String kbId) {
        requireAdmin();
        return Results.success(service.validate(kbId));
    }

    @PostMapping("/{kbId}/enable")
    public Result<GraphWorkspaceBindingDO> enable(@PathVariable String kbId) {
        requireAdmin();
        return Results.success(service.enable(kbId));
    }

    @PostMapping("/{kbId}/disable")
    public Result<Void> disable(@PathVariable String kbId) {
        requireAdmin();
        service.disable(kbId);
        return Results.success();
    }

    private void requireAdmin() {
        StpUtil.checkRole("admin");
    }
}

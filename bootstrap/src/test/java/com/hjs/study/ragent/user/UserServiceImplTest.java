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

import com.hjs.study.ragent.audit.support.BizChangeLogContext;
import com.hjs.study.ragent.framework.context.LoginUser;
import com.hjs.study.ragent.framework.context.UserContext;
import com.hjs.study.ragent.framework.exception.ClientException;
import com.hjs.study.ragent.user.controller.request.ChangePasswordRequest;
import com.hjs.study.ragent.user.controller.request.UserCreateRequest;
import com.hjs.study.ragent.user.controller.request.UserUpdateRequest;
import com.hjs.study.ragent.user.dao.entity.UserDO;
import com.hjs.study.ragent.user.dao.mapper.UserMapper;
import com.hjs.study.ragent.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    private UserMapper userMapper;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userService = new UserServiceImpl(userMapper, mock(BizChangeLogContext.class));
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void createsGuestRoleForNonReservedAccount() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.insert(any(UserDO.class))).thenAnswer(invocation -> {
            UserDO user = invocation.getArgument(0);
            user.setId("2100000000000000000");
            return 1;
        });
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("interviewer");
        request.setPassword("temporary-password");
        request.setRole("guest");

        String id = userService.create(request);

        ArgumentCaptor<UserDO> captor = ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).insert(captor.capture());
        assertThat(id).isEqualTo("2100000000000000000");
        assertThat(captor.getValue().getRole()).isEqualTo("guest");
    }

    @Test
    void rejectsReservedGuestUsernameCreation() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("guest");
        request.setPassword("guest");
        request.setRole("guest");

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(ClientException.class)
                .hasMessage("系统保留用户名不可用");
    }

    @Test
    void rejectsReservedGuestAccountUpdate() {
        when(userMapper.selectOne(any())).thenReturn(UserDO.builder()
                .id("2001523723396308994")
                .username("guest")
                .password("guest")
                .role("guest")
                .deleted(0)
                .build());

        assertThatThrownBy(() -> userService.update("2001523723396308994", new UserUpdateRequest()))
                .isInstanceOf(ClientException.class)
                .hasMessage("系统保留账号不允许修改或删除");
    }

    @Test
    void guestCannotChangeSharedAccountPassword() {
        UserContext.set(LoginUser.builder()
                .userId("2001523723396308994")
                .username("guest")
                .role("guest")
                .build());
        when(userMapper.selectOne(any())).thenReturn(UserDO.builder()
                .id("2001523723396308994")
                .username("guest")
                .password("guest")
                .role("guest")
                .deleted(0)
                .build());
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("guest");
        request.setNewPassword("changed");

        assertThatThrownBy(() -> userService.changePassword(request))
                .isInstanceOf(ClientException.class)
                .hasMessage("游客体验账号不允许修改密码");
    }
}

package com.travel.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.travel.common.Result;
import com.travel.entity.User;
import com.travel.mapper.UserMapper;
import com.travel.dto.LoginRequest;
import com.travel.dto.RegisterRequest;
import com.travel.dto.LoginResponse;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

@Service
@RequiredArgsConstructor
public class UserService extends ServiceImpl<UserMapper, User> {

    private final BCryptPasswordEncoder passwordEncoder;

    public LoginResponse register(RegisterRequest req) {
        // 检查用户名是否重复
        long count = count(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (count > 0) {
            throw new RuntimeException("用户名已存在");
        }
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname() != null ? req.getNickname() : req.getUsername());
        user.setStatus(1);
        save(user);

        // 自动登录
        StpUtil.login(user.getId());
        return buildLoginResponse(user);
    }

    public LoginResponse login(LoginRequest req) {
        User user = getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.getUsername())
                .eq(User::getStatus, 1));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }
        StpUtil.login(user.getId());
        return buildLoginResponse(user);
    }

    public void logout() {
        StpUtil.logout();
    }

    public User getCurrentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        return getById(userId);
    }

    private LoginResponse buildLoginResponse(User user) {
        LoginResponse resp = new LoginResponse();
        resp.setToken(StpUtil.getTokenValue());
        resp.setUserId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setNickname(user.getNickname());
        resp.setAvatar(user.getAvatar());
        return resp;
    }
}

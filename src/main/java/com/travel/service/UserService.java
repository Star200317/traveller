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
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService extends ServiceImpl<UserMapper, User> {

    private final BCryptPasswordEncoder passwordEncoder;
    private final VerifyCodeService verifyCodeService;

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

    /**
     * 发送验证码
     */
    public String sendVerifyCode(String username) {
        // 检查用户是否存在
        User user = getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .eq(User::getStatus, 1));
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 生成验证码
        String code = verifyCodeService.generateCode(username);

        // TODO: 实际项目中，这里应该发送邮件或短信
        // 当前模拟实现：返回验证码（开发测试用）
        log.info("[UserService] 验证码已生成（开发模式），username={}, code={}", username, code);

        return code;
    }

    /**
     * 重置密码
     */
    public void resetPassword(String username, String code, String newPassword) {
        // 验证验证码
        if (!verifyCodeService.verifyCode(username, code)) {
            throw new RuntimeException("验证码错误或已过期");
        }

        // 获取用户
        User user = getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .eq(User::getStatus, 1));
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 更新密码
        user.setPassword(passwordEncoder.encode(newPassword));
        updateById(user);

        log.info("[UserService] 密码重置成功，username={}", username);
    }
}

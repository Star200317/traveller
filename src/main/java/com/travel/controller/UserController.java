package com.travel.controller;

import com.travel.common.Result;
import com.travel.dto.UserProfileRequest;
import com.travel.entity.User;
import com.travel.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户控制器
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*")
public class UserController {

    private final UserService userService;

    /**
     * 更新用户资料（头像、个人信息）
     */
    @PutMapping("/profile")
    public Result<User> updateProfile(@RequestBody UserProfileRequest req) {
        User user = userService.updateProfile(req);
        user.setPassword(null);
        return Result.success(user);
    }

    /**
     * 修改密码
     */
    @PostMapping("/changePassword")
    public Result<Void> changePassword(@RequestBody Map<String, String> params) {
        userService.changePassword(params.get("oldPassword"), params.get("newPassword"));
        return Result.success(null);
    }

    /**
     * 删除账号
     */
    @DeleteMapping("/account")
    public Result<Void> deleteAccount() {
        userService.deleteAccount();
        return Result.success(null);
    }
}

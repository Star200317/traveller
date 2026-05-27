package com.travel.controller;

import com.travel.common.Result;
import com.travel.dto.UserProfileRequest;
import com.travel.entity.User;
import com.travel.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*")
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    public Result<User> getProfile() {
        User user = userService.getCurrentUser();
        if (user == null) {
            return Result.fail("用户不存在");
        }
        user.setPassword(null);
        return Result.success(user);
    }

    @PutMapping("/profile")
    public Result<User> updateProfile(@RequestBody UserProfileRequest req) {
        User user = userService.updateProfile(req);
        user.setPassword(null);
        return Result.success(user);
    }

    @PostMapping("/changePassword")
    public Result<Void> changePassword(@RequestBody Map<String, String> params) {
        userService.changePassword(params.get("oldPassword"), params.get("newPassword"));
        return Result.success(null);
    }

    @DeleteMapping("/account")
    public Result<Void> deleteAccount() {
        userService.deleteAccount();
        return Result.success(null);
    }
}

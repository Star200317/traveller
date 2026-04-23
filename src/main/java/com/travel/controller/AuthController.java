package com.travel.controller;

import com.travel.common.Result;
import com.travel.dto.*;
import com.travel.entity.User;
import com.travel.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest req) {
        return Result.success(userService.register(req));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.success(userService.login(req));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        userService.logout();
        return Result.success();
    }

    @GetMapping("/me")
    public Result<User> me() {
        User user = userService.getCurrentUser();
        user.setPassword(null);
        return Result.success(user);
    }
}

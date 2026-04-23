package com.travel.exception;

import com.travel.common.Result;
import cn.dev33.satoken.exception.NotLoginException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(401).body(Result.fail(401, "请先登录"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(fe -> fe.getDefaultMessage()).orElse("参数错误");
        return Result.fail(400, msg);
    }

    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntime(RuntimeException e) {
        log.error("[RuntimeException]", e);
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("[Exception]", e);
        return Result.fail("服务器内部错误");
    }
}

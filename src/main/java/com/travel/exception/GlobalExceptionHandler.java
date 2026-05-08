package com.travel.exception;

import com.travel.common.Result;
import cn.dev33.satoken.exception.NotLoginException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
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
    public Object handleRuntime(RuntimeException e, HttpServletRequest request) {
        log.error("[RuntimeException]", e);
        // 如果是SSE请求，返回文本格式
        if (isSseRequest(request)) {
            return "data: {\"error\": \" + escapeJson(e.getMessage()) + \"\"}\n\n";
        }
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Object handleException(Exception e, HttpServletRequest request) {
        log.error("[Exception]", e);
        // 如果是SSE请求，返回文本格式
        if (isSseRequest(request)) {
            return "data: {\"error\": \"服务器内部错误\"}\n\n";
        }
        return Result.fail("服务器内部错误");
    }
    
    /**
     * 判断是否是SSE请求
     */
    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
    
    /**
     * 转义JSON字符串中的特殊字符
     */
    private String escapeJson(String message) {
        if (message == null) {
            return "";
        }
        return message.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

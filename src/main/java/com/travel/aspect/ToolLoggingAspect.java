package com.travel.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 工具调用日志切面
 * 记录所有 AI Tool 的调用情况
 *
 * 注意：Spring AI 的工具调用可能不经过 Spring AOP 代理，
 * 因此同时在工具类内部添加了手动日志记录作为备用方案
 */
@Slf4j
@Aspect
@Component
public class ToolLoggingAspect {

    /**
     * 拦截所有带有 @Tool 注解的方法（类级别或方法级别）
     */
    @Around("@within(tool) || @annotation(tool)")
    public Object logToolCall(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        return logToolCallInternal(joinPoint, tool);
    }

    /**
     * 内部日志记录方法
     */
    private Object logToolCallInternal(ProceedingJoinPoint joinPoint, Tool tool) throws Throwable {
        // 添加调试日志，确认切面是否被触发
        log.debug("[AOP_DEBUG] 切面被触发: {}.{}",
                joinPoint.getTarget().getClass().getName(),
                joinPoint.getSignature().getName());
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();

        // 记录工具调用开始
        log.info("[TOOL_CALL] 开始调用 => {}.{}()", className, methodName);
        if (tool != null && tool.description() != null) {
            log.info("[TOOL_CALL] 工具描述: {}", tool.description().split("\\n")[0].trim());
        }

        // 记录参数
        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                log.info("[TOOL_CALL] 参数[{}]: {}", i, args[i]);
            }
        }

        long startTime = System.currentTimeMillis();
        Object result = null;

        try {
            // 执行原方法
            result = joinPoint.proceed();

            long duration = System.currentTimeMillis() - startTime;

            // 记录调用成功
            if (result != null) {
                String resultStr = result.toString();
                // 截断过长的结果
                if (resultStr.length() > 500) {
                    resultStr = resultStr.substring(0, 500) + "... (截断，总长度: " + resultStr.length() + ")";
                }
                log.info("[TOOL_CALL] 调用成功 <= {}.{}() | 耗时: {}ms | 结果: {}",
                        className, methodName, duration, resultStr);
            } else {
                log.info("[TOOL_CALL] 调用成功 <= {}.{}() | 耗时: {}ms | 结果: null",
                        className, methodName, duration);
            }

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[TOOL_CALL] 调用失败 <= {}.{}() | 耗时: {}ms | 错误: {}",
                    className, methodName, duration, e.getMessage(), e);
            throw e;
        }
    }
}

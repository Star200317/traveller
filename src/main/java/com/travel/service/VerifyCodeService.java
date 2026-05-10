package com.travel.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 验证码服务（内存存储，适用于单机部署）
 * 生产环境建议使用Redis存储
 */
@Slf4j
@Service
public class VerifyCodeService {

    // 存储验证码：key=username, value={code, expireTime}
    private final Map<String, CodeInfo> codeStore = new ConcurrentHashMap<>();

    // 验证码有效期：5分钟
    private static final long CODE_EXPIRE_MS = 5 * 60 * 1000;

    // 清理过期验证码的调度器
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "verify-code-cleaner");
        t.setDaemon(true);
        return t;
    });

    public VerifyCodeService() {
        // 每分钟清理一次过期验证码
        scheduler.scheduleAtFixedRate(this::cleanExpiredCodes, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 生成并存储验证码
     */
    public String generateCode(String username) {
        // 生成6位数字验证码
        String code = String.format("%06d", (int) (Math.random() * 1000000));

        CodeInfo info = new CodeInfo(code, System.currentTimeMillis() + CODE_EXPIRE_MS);
        codeStore.put(username, info);

        log.info("[VerifyCode] 为用户 {} 生成验证码: {}", username, code);
        return code;
    }

    /**
     * 验证验证码是否正确且未过期
     */
    public boolean verifyCode(String username, String code) {
        CodeInfo info = codeStore.get(username);

        if (info == null) {
            log.warn("[VerifyCode] 用户 {} 验证码不存在或已过期", username);
            return false;
        }

        if (System.currentTimeMillis() > info.expireTime) {
            log.warn("[VerifyCode] 用户 {} 验证码已过期", username);
            codeStore.remove(username);
            return false;
        }

        if (!info.code.equals(code)) {
            log.warn("[VerifyCode] 用户 {} 验证码不匹配: 输入={}, 实际={}", username, code, info.code);
            return false;
        }

        // 验证成功后删除验证码（一次性）
        codeStore.remove(username);
        log.info("[VerifyCode] 用户 {} 验证码验证成功", username);
        return true;
    }

    /**
     * 清理过期验证码
     */
    private void cleanExpiredCodes() {
        long now = System.currentTimeMillis();
        codeStore.entrySet().removeIf(entry -> entry.getValue().expireTime < now);
    }

    /**
     * 验证码信息
     */
    private static class CodeInfo {
        String code;
        long expireTime;

        CodeInfo(String code, long expireTime) {
            this.code = code;
            this.expireTime = expireTime;
        }
    }
}

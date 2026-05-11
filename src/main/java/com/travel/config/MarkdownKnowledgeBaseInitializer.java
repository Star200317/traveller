package com.travel.config;

import com.travel.entity.KnowledgeDoc;
import com.travel.service.RagService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Markdown知识库启动初始化器
 *
 * 应用启动后自动扫描知识库目录下的.md文件，将其：
 * 1. 读取文本内容
 * 2. 创建 KnowledgeDoc 记录
 * 3. 调用 RagService 向量化并存入 Pinecone
 *
 * 知识库路径配置：knowledge-base.md-path
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkdownKnowledgeBaseInitializer implements CommandLineRunner {

    private final RagService ragService;

    @Value("${knowledge-base.md-path:D:/Star/ai-travel-guide/知识库}")
    private String markdownKnowledgeBasePath;

    @Value("${knowledge-base.auto-index-on-startup:true}")
    private boolean autoIndexOnStartup;

    @Value("${knowledge-base.reindex-if-exists:false}")
    private boolean reindexIfExists;

    /** Pinecone 索引重试配置 */
    private static final int INDEX_MAX_RETRIES = 3;
    private static final long INDEX_RETRY_BASE_DELAY_MS = 2000;

    @Override
    public void run(String... args) {
        if (!autoIndexOnStartup) {
            log.info("[KB-MD] auto-index disabled");
            return;
        }
        CompletableFuture.runAsync(this::doIndex);
    }

    private void doIndex() {
        try {
            Path basePath = Paths.get(markdownKnowledgeBasePath);
            if (!Files.exists(basePath)) {
                log.warn("[KB-MD] path not found: {}, will create", markdownKnowledgeBasePath);
                Files.createDirectories(basePath);
                return;
            }

            log.info("[KB-MD] starting scan: {}", markdownKnowledgeBasePath);
            long t0 = System.currentTimeMillis();
            int total = 0, ok = 0, skip = 0, fail = 0;

            // 扫描所有.md文件（包括子目录）
            List<Path> mdFiles;
            try {
                mdFiles = Files.walk(basePath)
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                        .toList();
            } catch (IOException e) {
                log.error("[KB-MD] failed to walk directory", e);
                return;
            }

            if (mdFiles.isEmpty()) {
                log.info("[KB-MD] no .md files found in {}", markdownKnowledgeBasePath);
                return;
            }

            log.info("[KB-MD] found {} .md files", mdFiles.size());

            for (Path mdPath : mdFiles) {
                total++;
                try {
                    if (indexMarkdown(mdPath)) {
                        ok++;
                    } else {
                        skip++;
                    }
                } catch (Exception e) {
                    fail++;
                    log.error("[KB-MD] FAIL: {} - {}: {}", mdPath.getFileName(), e.getClass().getSimpleName(), e.getMessage(), e);
                }
            }

            log.info("[KB-MD] done: total={} ok={} skip={} fail={} ms={}",
                    total, ok, skip, fail, System.currentTimeMillis() - t0);

        } catch (Exception e) {
            log.error("[KB-MD] fatal error", e);
        }
    }

    /**
     * 处理单个Markdown文件
     * @return true=已处理, false=已存在跳过
     */
    private boolean indexMarkdown(Path mdPath) throws Exception {
        String fileName = mdPath.getFileName().toString();

        // 去重检查：按fileName查询是否已存在
        if (!reindexIfExists) {
            List<KnowledgeDoc> existing = ragService.lambdaQuery()
                    .eq(KnowledgeDoc::getFileName, fileName)
                    .eq(KnowledgeDoc::getDeleted, 0)
                    .list();
            if (!existing.isEmpty()) {
                log.debug("[KB-MD] skip existing: {}", fileName);
                return false;
            }
        }

        log.info("[KB-MD] parsing: {} ({})", fileName, formatSize(Files.size(mdPath)));

        // 读取Markdown文件内容
        String content = Files.readString(mdPath);

        if (content == null || content.isBlank()) {
            log.warn("[KB-MD] empty content: {}", fileName);
            return false;
        }

        String cleaned = cleanMarkdown(content);
        String category = detectCategory(fileName, cleaned);
        String title = buildTitle(fileName, mdPath);

        KnowledgeDoc kd = new KnowledgeDoc();
        kd.setUserId(null);           // null=公共库
        kd.setCategory(category);
        kd.setTitle(title);
        kd.setContent(cleaned);
        kd.setFileName(fileName);
        kd.setFileSize(Files.size(mdPath));
        kd.setStatus(0);
        kd.setDeleted(0);
        kd.setCreateTime(LocalDateTime.now());
        kd.setUpdateTime(LocalDateTime.now());

        // 必须先 save，否则 getId() 为 null
        ragService.save(kd);
        log.info("[KB-MD] saved: [{}] {}", kd.getId(), title);

        // 向量化并存入Pinecone（带重试）
        indexDocumentWithRetry(kd);
        log.info("[KB-MD] indexed: [{}] {} cat={}", kd.getId(), title, category);
        return true;
    }

    /**
     * 带重试的 Pinecone 索引调用
     * 遇到超时/网络异常自动重试，最多 INDEX_MAX_RETRIES 次
     */
    private void indexDocumentWithRetry(KnowledgeDoc kd) throws Exception {
        int attempt = 0;
        long delay = INDEX_RETRY_BASE_DELAY_MS;

        while (attempt < INDEX_MAX_RETRIES) {
            try {
                ragService.indexDocument(kd);
                return; // 成功，退出
            } catch (Exception e) {
                attempt++;
                // 判断是否是 Pinecone 超时/网络相关异常
                boolean isRetryable = isRetryableException(e);

                if (isRetryable && attempt < INDEX_MAX_RETRIES) {
                    log.warn("[KB-MD] Pinecone 索引异常，第{}次重试（等待{}ms）: {} - {}: {}",
                            attempt, delay, kd.getFileName(),
                            e.getClass().getSimpleName(), e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("索引被中断: " + kd.getFileName(), ie);
                    }
                    delay *= 2; // 指数退避：2s → 4s → 8s
                } else {
                    // 非可重试异常，或已达最大重试次数
                    log.error("[KB-MD] Pinecone 索引失败（尝试{}/{}）: {} - {}: {}",
                            attempt, INDEX_MAX_RETRIES, kd.getFileName(),
                            e.getClass().getSimpleName(), e.getMessage(), e);
                    throw e;
                }
            }
        }
    }

    /**
     * 判断异常是否可重试（超时/网络异常/gRPC异常）
     * 覆盖：SocketTimeoutException、StatusRuntimeException（gRPC）、Connection reset 等
     */
    private boolean isRetryableException(Exception e) {
        // 1. gRPC 状态码判断（Pinecone 使用 gRPC）
        if (e instanceof StatusRuntimeException) {
            Status status = ((StatusRuntimeException) e).getStatus();
            // UNAVAILABLE: 连接断开，可重试
            // DEADLINE_EXCEEDED: 超时，可重试
            // RESOURCE_EXHAUSTED: 限流，可重试
            // INTERNAL: 内部错误有时可重试
            if (status != null) {
                Status.Code code = status.getCode();
                if (code == Status.Code.UNAVAILABLE
                        || code == Status.Code.DEADLINE_EXCEEDED
                        || code == Status.Code.RESOURCE_EXHAUSTED
                        || code == Status.Code.INTERNAL) {
                    return true;
                }
            }
        }

        // 2. 遍历整个 cause 链，检查消息中的关键词
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage() != null ? current.getMessage().toLowerCase() : "";
            if (msg.contains("timed out")
                    || msg.contains("timeout")
                    || msg.contains("socket")
                    || msg.contains("connection reset")
                    || msg.contains("unavailable")
                    || msg.contains("io exception")
                    || msg.contains("pineconeunmapped")
                    || msg.contains("reset")
                    || msg.contains("eof")) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }

    /**
     * 清理Markdown内容
     * - 移除多余空行
     * - 保留正文内容（包含景点、美食、住宿信息）
     */
    private String cleanMarkdown(String text) {
        // 清理多余空行（3个以上连续换行压缩为2个）
        String cleaned = text.replaceAll("\\n{3,}", "\n\n");
        // 去除每行首尾空白，去除空行
        return cleaned.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.joining("\n"))
                .trim();
    }

    /**
     * 检测文档分类
     * 根据文件名和内容判断是景点/城市/旅行常识
     */
    private String detectCategory(String fileName, String content) {
        String lower = fileName.toLowerCase();
        String contentLower = content.toLowerCase();

        if (lower.contains("景点") || contentLower.contains("## 景点")) return "attraction";
        if (lower.contains("美食") || lower.contains("餐厅") || contentLower.contains("## 美食")) return "city";
        if (lower.contains("住宿") || contentLower.contains("酒店") || contentLower.contains("## 住宿")) return "city";

        // 默认归类为城市知识库
        return "city";
    }

    /**
     * 构建文档标题
     * 从文件名提取城市名（如"旅游-北京.md" → "北京"）
     */
    private String buildTitle(String fileName, Path filePath) {
        // 去掉.md后缀
        String name = fileName.replaceAll("(?i)\\.md$", "");

        // 如果是"旅游-城市名"格式，提取城市名
        if (name.startsWith("旅游-")) {
            return name.substring("旅游-".length());
        }

        return name;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

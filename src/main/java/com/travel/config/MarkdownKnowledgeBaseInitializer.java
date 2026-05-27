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
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // 未向量化文件路径（扫描源）
    private Path getPendingPath() {
        return Paths.get(markdownKnowledgeBasePath, "未向量化");
    }

    // 已向量化文件路径（移动目标）
    private Path getIndexedPath() {
        return Paths.get(markdownKnowledgeBasePath, "已向量化");
    }

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
            Path pendingPath = getPendingPath();
            Path indexedPath = getIndexedPath();

            // 创建目录（如果不存在）
            if (!Files.exists(pendingPath)) {
                log.warn("[KB-MD] pending path not found: {}, will create", pendingPath);
                Files.createDirectories(pendingPath);
            }
            if (!Files.exists(indexedPath)) {
                log.warn("[KB-MD] indexed path not found: {}, will create", indexedPath);
                Files.createDirectories(indexedPath);
            }

            log.info("[KB-MD] starting scan: {}", pendingPath);
            long t0 = System.currentTimeMillis();
            int total = 0, ok = 0, skip = 0, fail = 0;

            // 扫描所有.md文件（包括子目录）
            List<Path> mdFiles;
            try {
                mdFiles = Files.walk(pendingPath)
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                        .toList();
            } catch (IOException e) {
                log.error("[KB-MD] failed to walk directory", e);
                return;
            }

            if (mdFiles.isEmpty()) {
                log.info("[KB-MD] no .md files found in {}", pendingPath);
                return;
            }

            log.info("[KB-MD] found {} .md files", mdFiles.size());

            for (Path mdPath : mdFiles) {
                total++;
                try {
                    if (indexMarkdown(mdPath)) {
                        ok++;
                        // 向量化成功后，移动到"已向量化"目录
                        moveToIndexed(mdPath, indexedPath);
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
     * 按章节拆分为多个文档分别索引
     * @return true=已处理, false=已存在跳过
     */
    private boolean indexMarkdown(Path mdPath) throws Exception {
        String fileName = mdPath.getFileName().toString();

        log.info("[KB-MD] parsing: {} ({})", fileName, formatSize(Files.size(mdPath)));

        // 读取Markdown文件内容
        String content = Files.readString(mdPath);

        if (content == null || content.isBlank()) {
            log.warn("[KB-MD] empty content: {}", fileName);
            return false;
        }

        String cleaned = cleanMarkdown(content);
        String baseTitle = buildTitle(fileName, mdPath);
        
        // 按章节拆分文档
        List<DocSection> sections = splitDocumentBySections(cleaned, fileName);
        
        if (sections.isEmpty()) {
            // 没有识别到章节，按原方式处理（归类为city）
            log.warn("[KB-MD] no sections found in {}, indexing as city", fileName);
            return indexAsSingleDocument(fileName, baseTitle, cleaned, mdPath);
        }
        
        log.info("[KB-MD] split {} into {} sections", fileName, sections.size());
        
        // 为每个章节创建独立的KnowledgeDoc
        boolean anySuccess = false;
        for (DocSection section : sections) {
            try {
                if (indexSectionDocument(section, baseTitle, fileName)) {
                    anySuccess = true;
                }
            } catch (Exception e) {
                log.error("[KB-MD] failed to index section: {} - {}", 
                    section.title, e.getMessage(), e);
            }
        }
        
        return anySuccess;
    }
    
    /**
     * 索引单个章节文档
     */
    private boolean indexSectionDocument(DocSection section, String baseTitle, String sourceFileName) {
        // 生成唯一标识：原文件名#category#章节标题
        String uniqueKey = sourceFileName + "#" + section.category + "#" + section.title;
        
        // 去重检查
        if (!reindexIfExists) {
            List<KnowledgeDoc> existing = ragService.lambdaQuery()
                    .eq(KnowledgeDoc::getFileName, uniqueKey)
                    .eq(KnowledgeDoc::getDeleted, 0)
                    .list();
            if (!existing.isEmpty()) {
                log.debug("[KB-MD] skip existing section: {}", uniqueKey);
                return false;
            }
        }
        
        // 创建KnowledgeDoc
        KnowledgeDoc kd = new KnowledgeDoc();
        kd.setUserId(null);  // null=公共库
        kd.setCategory(section.category);
        kd.setTitle(baseTitle + "-" + section.title);  // 如"北京-美食"
        kd.setContent(section.content);
        kd.setFileName(uniqueKey);  // 唯一标识
        kd.setFileSize((long) section.content.length());
        kd.setStatus(0);
        kd.setDeleted(0);
        kd.setCreateTime(LocalDateTime.now());
        kd.setUpdateTime(LocalDateTime.now());
        
        // 保存到数据库
        ragService.save(kd);
        log.info("[KB-MD] saved section: [{}] {}", kd.getId(), kd.getTitle());
        
        // 向量化并存入Pinecone（带重试）
        try {
            indexDocumentWithRetry(kd);
            log.info("[KB-MD] indexed section: [{}] {} cat={}", 
                kd.getId(), kd.getTitle(), section.category);
        } catch (Exception e) {
            log.error("[KB-MD] failed to index section: {} - {}", 
                kd.getTitle(), e.getMessage(), e);
            // 索引失败不改变返回结果，因为数据库记录已保存
        }
        
        return true;
    }
    
    /**
     * 按原方式索引单个文档（兼容未拆分文档）
     */
    private boolean indexAsSingleDocument(String fileName, String title, String content, Path mdPath) throws Exception {
        // 去重检查
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

        String category = detectCategory(fileName, content);

        KnowledgeDoc kd = new KnowledgeDoc();
        kd.setUserId(null);
        kd.setCategory(category);
        kd.setTitle(title);
        kd.setContent(content);
        kd.setFileName(fileName);
        kd.setFileSize(Files.size(mdPath));
        kd.setStatus(0);
        kd.setDeleted(0);
        kd.setCreateTime(LocalDateTime.now());
        kd.setUpdateTime(LocalDateTime.now());

        ragService.save(kd);
        log.info("[KB-MD] saved: [{}] {}", kd.getId(), title);

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
     * 支持章节拆分后的文档（文件名包含#）
     */
    private String detectCategory(String fileName, String content) {
        String lower = fileName.toLowerCase();
        
        // 检查是否是章节拆分后的文档（文件名格式：原文件名#category#sectionTitle）
        if (fileName.contains("#")) {
            String[] parts = fileName.split("#");
            if (parts.length >= 2) {
                return parts[1];  // 返回category部分
            }
        }
        
        // 旧逻辑（兼容未拆分文档）
        String contentLower = content.toLowerCase();
        if (lower.contains("景点") || contentLower.contains("## 景点")) return "attraction";
        if (lower.contains("美食") || lower.contains("餐厅") || contentLower.contains("## 美食")) return "food";
        if (lower.contains("住宿") || contentLower.contains("酒店") || contentLower.contains("## 住宿")) return "hotel";

        // 默认归类为城市知识库
        return "city";
    }
    
    /**
     * 按章节拆分文档
     * 根据 ## 标题将文档拆分为多个章节
     * @param content 原始文档内容
     * @param fileName 文件名
     * @return 拆分后的章节列表
     */
    private List<DocSection> splitDocumentBySections(String content, String fileName) {
        List<DocSection> sections = new ArrayList<>();
        
        // 定义章节映射关系（章节关键词 -> category）
        Map<String, String> sectionCategoryMap = new HashMap<>();
        sectionCategoryMap.put("景点", "attraction");
        sectionCategoryMap.put("美食", "food");
        sectionCategoryMap.put("餐厅", "food");
        sectionCategoryMap.put("小吃", "food");
        sectionCategoryMap.put("住宿", "hotel");
        sectionCategoryMap.put("酒店", "hotel");
        sectionCategoryMap.put("民宿", "hotel");
        sectionCategoryMap.put("客栈", "hotel");
        sectionCategoryMap.put("旅社", "hotel");
        
        // 使用正则表达式匹配 ## 标题
        Pattern pattern = Pattern.compile("(?m)^##\\s+(.+)$");
        Matcher matcher = pattern.matcher(content);
        
        int lastEnd = 0;
        String lastTitle = null;
        
        while (matcher.find()) {
            // 保存上一个章节的内容
            if (lastTitle != null) {
                String sectionContent = content.substring(lastEnd, matcher.start()).trim();
                String category = detectSectionCategory(lastTitle, sectionCategoryMap);
                if (category != null) {
                    sections.add(new DocSection(category, lastTitle, sectionContent, fileName));
                }
            }
            
            lastTitle = matcher.group(1).trim();
            lastEnd = matcher.end();
        }
        
        // 保存最后一个章节
        if (lastTitle != null && lastEnd < content.length()) {
            String sectionContent = content.substring(lastEnd).trim();
            String category = detectSectionCategory(lastTitle, sectionCategoryMap);
            if (category != null) {
                sections.add(new DocSection(category, lastTitle, sectionContent, fileName));
            }
        }
        
        return sections;
    }
    
    /**
     * 检测章节分类
     */
    private String detectSectionCategory(String title, Map<String, String> categoryMap) {
        String lowerTitle = title.toLowerCase();
        for (Map.Entry<String, String> entry : categoryMap.entrySet()) {
            if (lowerTitle.contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }
        return null;  // 未识别的章节不处理
    }
    
    /**
     * 文档章节内部类
     */
    private static class DocSection {
        String category;
        String title;
        String content;
        String sourceFile;
        
        DocSection(String category, String title, String content, String sourceFile) {
            this.category = category;
            this.title = title;
            this.content = content;
            this.sourceFile = sourceFile;
        }
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

    /**
     * 将文件移动到"已向量化"目录
     * 保持原有的子目录结构
     */
    private void moveToIndexed(Path sourcePath, Path indexedPath) {
        try {
            // 计算相对路径（保留子目录结构）
            Path pendingPath = getPendingPath();
            Path relativePath = pendingPath.relativize(sourcePath);
            Path targetPath = indexedPath.resolve(relativePath);

            // 创建目标目录（如果不存在）
            Path targetDir = targetPath.getParent();
            if (targetDir != null && !Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            // 移动文件
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[KB-MD] moved to indexed: {} -> {}", sourcePath.getFileName(), targetPath);
        } catch (Exception e) {
            log.warn("[KB-MD] failed to move file to indexed: {} - {}", sourcePath.getFileName(), e.getMessage());
        }
    }
}

package com.travel.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * 工具3：文件操作（读写）
 */
@Slf4j
@Component
public class FileOperationTool {

    @Value("${file.storage-path:./uploads}")
    private String storagePath;

    @Tool(description = "读取本地文件内容，支持txt/md/json等文本文件")
    public String readFile(
            @ToolParam(description = "文件名（不含路径），如 plan.txt") String fileName) {
        try {
            Path path = Paths.get(storagePath, fileName);
            if (!Files.exists(path)) {
                return "文件不存在：" + fileName;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return content.length() > 5000 ? content.substring(0, 5000) + "..." : content;
        } catch (Exception e) {
            log.error("[FileRead] 读取失败: {}", fileName, e);
            return "文件读取失败：" + e.getMessage();
        }
    }

    @Tool(description = "将内容写入本地文件，用于保存旅游计划草稿、笔记等")
    public String writeFile(
            @ToolParam(description = "文件名，如 plan.txt") String fileName,
            @ToolParam(description = "要写入的文本内容") String content) {
        try {
            Path dir = Paths.get(storagePath);
            Files.createDirectories(dir);
            Path path = dir.resolve(fileName);
            Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "文件已保存：" + path.toAbsolutePath();
        } catch (Exception e) {
            log.error("[FileWrite] 写入失败: {}", fileName, e);
            return "文件写入失败：" + e.getMessage();
        }
    }
}

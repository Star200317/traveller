package com.travel.tools;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;

/**
 * 工具4：资源下载（图片/文件）
 */
@Slf4j
@Component
public class ResourceDownloadTool {

    @Value("${file.storage-path:./uploads}")
    private String storagePath;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    @Tool(description = "下载网络资源（图片、PDF等）到本地，返回保存的文件名")
    public String downloadResource(
            @ToolParam(description = "资源URL") String url,
            @ToolParam(description = "保存的文件名，如 map.jpg") String saveAs) {
        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return "下载失败，HTTP状态码：" + response.code();
                }
                Path dir = Paths.get(storagePath);
                Files.createDirectories(dir);
                Path savePath = dir.resolve(saveAs);
                try (InputStream is = response.body().byteStream();
                     OutputStream os = Files.newOutputStream(savePath)) {
                    is.transferTo(os);
                }
                return "下载完成，文件保存为：" + saveAs;
            }
        } catch (Exception e) {
            log.error("[Download] 下载失败: {}", url, e);
            return "下载失败：" + e.getMessage();
        }
    }
}

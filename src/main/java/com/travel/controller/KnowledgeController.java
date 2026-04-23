package com.travel.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.travel.common.Result;
import com.travel.entity.KnowledgeDoc;
import com.travel.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final RagService ragService;

    /**
     * 上传文档到公共知识库（管理员用）
     */
    @PostMapping("/admin/upload")
    public Result<KnowledgeDoc> uploadPublic(
            @RequestParam String category,
            @RequestParam String title,
            @RequestParam MultipartFile file) throws Exception {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setCategory(category);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setFileName(file.getOriginalFilename());
        doc.setFileSize(file.getSize());
        doc.setStatus(0);
        ragService.save(doc);
        ragService.indexDocument(doc);
        return Result.success(doc);
    }

    /**
     * 用户上传私有知识库文档
     */
    @PostMapping("/user/upload")
    public Result<KnowledgeDoc> uploadUser(
            @RequestParam String title,
            @RequestParam MultipartFile file) throws Exception {
        Long userId = StpUtil.getLoginIdAsLong();
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setUserId(userId);
        doc.setCategory("user");
        doc.setTitle(title);
        doc.setContent(content);
        doc.setFileName(file.getOriginalFilename());
        doc.setFileSize(file.getSize());
        doc.setStatus(0);
        ragService.save(doc);
        ragService.indexDocument(doc);
        return Result.success(doc);
    }

    /**
     * 获取用户私有知识库列表
     */
    @GetMapping("/user/list")
    public Result<List<KnowledgeDoc>> userDocList() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(ragService.lambdaQuery()
                .eq(KnowledgeDoc::getUserId, userId).list());
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{docId}")
    public Result<Void> deleteDoc(@PathVariable Long docId) {
        KnowledgeDoc doc = ragService.getById(docId);
        ragService.deleteDocument(doc);
        ragService.removeById(docId);
        return Result.success();
    }
}

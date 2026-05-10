package com.travel.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.travel.common.Result;
import com.travel.entity.Conversation;
import com.travel.entity.Message;
import com.travel.service.ChatService;
import com.travel.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;

    /**
     * 创建新会话
     */
    @PostMapping("/conversation/new")
    public Result<Conversation> newConversation(@RequestBody(required = false) Map<String, String> body) {
        Long userId = StpUtil.getLoginIdAsLong();
        String title = body != null ? body.get("title") : null;
        return Result.success(conversationService.createConversation(userId, title));
    }

    /**
     * 流式对话（SSE）
     * 前端使用 EventSource 或 fetch + ReadableStream 接收
     */
    @GetMapping(value = "/stream/{conversationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(
            @PathVariable Long conversationId,
            @RequestParam String message) {
        return chatService.chat(conversationId, message);
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/conversations")
    public Result<List<Conversation>> listConversations() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(conversationService.listByUser(userId));
    }

    /**
     * 获取会话消息历史
     */
    @GetMapping("/conversation/{conversationId}/messages")
    public Result<List<Message>> getMessages(@PathVariable Long conversationId) {
        return Result.success(conversationService.getMessages(conversationId));
    }

    /**
     * 删除会话（逻辑删除）
     */
    @DeleteMapping("/conversation/{conversationId}")
    public Result<Void> deleteConversation(@PathVariable Long conversationId) {
        conversationService.deleteConversation(conversationId);
        return Result.success();
    }
}

package com.travel.service;

import com.travel.entity.Message;
import com.travel.tools.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatModel chatModel;
    private final ConversationService conversationService;

    // 工具类
    private final WebSearchTool webSearchTool;
    private final WebScrapeTool webScrapeTool;
    private final FileOperationTool fileOperationTool;
    private final ResourceDownloadTool resourceDownloadTool;
    private final RagQueryTool ragQueryTool;
    private final SearchPlaceTool searchPlaceTool;

    private static final String SYSTEM_PROMPT = "";

    /**
     * 流式对话（SSE）
     */
    public Flux<String> chat(Long conversationId, String userInput) {
        // 保存用户消息
        conversationService.saveMessage(conversationId, "user", userInput);

        // 如果是第一条消息，更新会话标题
        conversationService.autoUpdateTitle(conversationId);

        // 构建历史消息列表
        List<Message> history = conversationService.getContextWindow(conversationId);
        List<org.springframework.ai.chat.messages.Message> springMessages = new ArrayList<>();
        springMessages.add(new SystemMessage(SYSTEM_PROMPT));

        for (Message msg : history) {
            if ("user".equals(msg.getRole())) {
                springMessages.add(new UserMessage(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                springMessages.add(new AssistantMessage(msg.getContent()));
            }
        }

        // 构建 ChatClient 并流式调用
        StringBuilder fullResponse = new StringBuilder();

        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build()
                .prompt()
                .messages(springMessages)
                .user(userInput)
                .tools(
                        webSearchTool,
                        webScrapeTool,
                        fileOperationTool,
                        resourceDownloadTool,
                        ragQueryTool,
                        searchPlaceTool
                )
                .stream()
                .content()
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .filter(throwable -> isNetworkError(throwable))
                        .doBeforeRetry(signal -> log.warn("[Chat] 网络错误，{} 秒后重试第 {} 次...",
                                signal.totalRetries() + 1, signal.totalRetries() + 1)))
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    // 流式完成后保存AI回复
                    conversationService.saveMessage(conversationId, "assistant", fullResponse.toString());
                    log.info("[Chat] conversationId={} 回复完成，长度={}", conversationId, fullResponse.length());
                })
                .doOnError(e -> log.error("[Chat] conversationId={} 出错", conversationId, e));
    }

    /**
     * 判断是否是网络相关错误（这些错误可以重试）
     */
    private boolean isNetworkError(Throwable throwable) {
        String msg = throwable.getMessage();
        if (msg == null) msg = "";
        msg = msg.toLowerCase();
        return msg.contains("connection reset")
                || msg.contains("connection refused")
                || msg.contains("connection abort")
                || msg.contains("timeout")
                || msg.contains("read timed out")
                || msg.contains("broken pipe")
                || msg.contains("peer closed")
                || msg.contains("connection closed");
    }
}

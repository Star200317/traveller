package com.travel.service;

import com.travel.entity.Message;
import com.travel.tools.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatModel chatModel;
    private final ConversationService conversationService;

    // 工具类
    private final WebSearchTool webSearchTool;
    private final WebScrapeTool webScrapeTool;
    private final FileOperationTool fileOperationTool;
    private final PdfExportTool pdfExportTool;
    private final ResourceDownloadTool resourceDownloadTool;
    private final RagQueryTool ragQueryTool;
    private final MapPlanTool mapPlanTool;
    private final SavePlanTool savePlanTool;

    private static final String SYSTEM_PROMPT = """
            你是一个专业的AI旅游向导助手，名叫"旅小智"。你精通全球各地的旅游信息，能够：
            1. 根据用户的需求（目的地、时间、预算、人数、偏好）制定个性化旅游计划
            2. 动态调整旅游计划，与用户反复确认直到满意
            3. 调用工具联网搜索最新旅游信息、景点详情、交通住宿等
            4. 查询本地知识库获取景点、城市详细信息
            5. 在用户确认计划后，保存计划、生成地图路线数据和PDF导出
            
            制定旅游计划时必须包含以下详细信息：
            - 每个景点的具体时间段（如 09:00-11:00）
            - 每个景点的门票价格（如 成人票35元，学生票半价）
            - 每个景点的经纬度坐标（latitude和longitude），用于地图显示
            - 景点之间的交通方式和预计时间
            - 午餐/晚餐的推荐餐厅和人均消费
            - 当天的住宿地点
            
            计划数据结构要求（重要）：
            保存计划时，planContentJson必须是以下格式的JSON数组字符串：
            [
              {
                "day": 1,
                "activities": [
                  {
                    "name": "景点名称",
                    "time": "09:00-11:00",
                    "ticket": "门票价格",
                    "description": "景点描述",
                    "latitude": 24.4637,
                    "longitude": 118.091
                  }
                ]
              }
            ]
            注意：每个activity必须包含latitude和longitude经纬度字段，这是地图显示的必要数据！
            
            对话规则：
            - 始终主动询问用户遗漏的关键信息（出行日期/天数/预算/人数/偏好）
            - 制定计划时主动搜索景点门票价格和开放时间
            - 制定计划后主动询问"这个计划您满意吗？需要调整哪些地方？"
            -             用户确认计划后，必须按以下顺序执行（重要！）：
              1) 先调用 save_plan 工具保存计划到数据库，它会返回一个长数字planId（如2045786267961270273）
              2) 将这个返回的planId作为参数调用 map_plan 生成地图路线数据
              3) 使用同一个planId调用 pdf_export 导出PDF
            - 重要：save_plan返回的planId是一个长数字字符串，必须原样传递给map_plan和pdf_export
            - 调用map_plan成功后，必须在回复中提供地图查看链接：http://localhost:5173/map/{planId}
            - 示例回复："地图路线已生成，您可以点击 http://localhost:5173/map/2045786267961270273 查看路线，或下载PDF"
            - 用中文回答，语气亲切自然
            - 推荐景点时提供简短介绍和实用建议
            """;

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
                        pdfExportTool,
                        resourceDownloadTool,
                        ragQueryTool,
                        mapPlanTool,
                        savePlanTool
                )
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    // 流式完成后保存AI回复
                    conversationService.saveMessage(conversationId, "assistant", fullResponse.toString());
                    log.info("[Chat] conversationId={} 回复完成，长度={}", conversationId, fullResponse.length());
                })
                .doOnError(e -> log.error("[Chat] conversationId={} 出错", conversationId, e));
    }
}

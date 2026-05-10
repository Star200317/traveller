package com.travel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.travel.entity.Conversation;
import com.travel.entity.Message;
import com.travel.mapper.ConversationMapper;
import com.travel.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService extends ServiceImpl<ConversationMapper, Conversation> {

    private final MessageMapper messageMapper;

    private static final int MAX_ROUNDS = 20;

    /** 创建新会话 */
    public Conversation createConversation(Long userId, String title) {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle(title != null ? title : "新对话");
        conv.setStatus(1);
        save(conv);
        return conv;
    }

    /** 获取用户会话列表（过滤已删除） */
    public List<Conversation> listByUser(Long userId) {
        return list(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getDeleted, 0)
                .orderByDesc(Conversation::getUpdateTime));
    }

    /** 获取会话消息历史 */
    public List<Message> getMessages(Long conversationId) {
        return messageMapper.selectByConversationId(conversationId);
    }

    /** 保存消息到DB */
    public Message saveMessage(Long conversationId, String role, String content) {
        Message msg = new Message();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        messageMapper.insert(msg);

        // 更新会话修改时间（触发UPDATE）
        Conversation conv = new Conversation();
        conv.setId(conversationId);
        updateById(conv);

        return msg;
    }

    /**
     * 逻辑删除会话（不物理删除）
     * 使用 UpdateWrapper 裸写 UPDATE，完全绕过 MyBatis-Plus 逻辑删除拦截，
     * 确保 deleted 字段一定被更新为 1
     */
    public boolean deleteConversation(Long conversationId) {
        UpdateWrapper<Conversation> uw = new UpdateWrapper<>();
        uw.eq("id", conversationId);
        uw.set("deleted", 1);
        int rows = baseMapper.update(null, uw);
        log.info("[Conversation] 逻辑删除 conversationId={}, 影响行数={}", conversationId, rows);
        return rows > 0;
    }

    /** 获取最近N轮上下文（滑动窗口） */
    public List<Message> getContextWindow(Long conversationId) {
        // 从DB取最新20条
        List<Message> msgs = messageMapper.selectLastN(conversationId, MAX_ROUNDS * 2);
        // 倒序转正序
        java.util.Collections.reverse(msgs);
        return msgs;
    }

    /** 更新会话标题（取第一条用户消息前20字） */
    public void autoUpdateTitle(Long conversationId) {
        List<Message> msgs = messageMapper.selectByConversationId(conversationId);
        if (!msgs.isEmpty()) {
            String firstContent = msgs.get(0).getContent();
            String title = firstContent.length() > 20 ? firstContent.substring(0, 20) + "..." : firstContent;
            Conversation conv = new Conversation();
            conv.setId(conversationId);
            conv.setTitle(title);
            updateById(conv);
        }
    }
}

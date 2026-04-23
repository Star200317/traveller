package com.travel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    @Select("SELECT * FROM message WHERE conversation_id = #{conversationId} ORDER BY create_time ASC")
    List<Message> selectByConversationId(Long conversationId);

    @Select("SELECT * FROM message WHERE conversation_id = #{conversationId} ORDER BY create_time DESC LIMIT #{limit}")
    List<Message> selectLastN(Long conversationId, int limit);
}

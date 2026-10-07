package io.github.p_feed.message;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    // 会话: 两个方向的
    @Select(
            "select * from messages where (from_id = #{me} and to_id = #{peer}) or (from_id ="
                + " #{peer} and to_id = #{me}) order by created_at desc limit #{limit}")
    List<Message> listConveration(
            @Param("me") long me, @Param("peer") long peer, @Param("limit") int limit);
}

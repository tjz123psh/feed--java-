package io.github.p_feed.comment;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import org.apache.ibatis.annotations.Param;

@Mapper
public interface CommentMapper extends BaseMapper<Comment>{

    // 列表：最早在前，最多两百条，无分页字段，超出直接截断
    @Select("select * from comments where video_id = #{video_id} order by created_at asc limit 200")
    List<Comment> listByVideoId(@Param("video_id") long videoId);

    // 查单条:  删除评论前校验存在与作者（避开 BaseMapper 同名方法的签名冲突）
    @Select("select * from comments where id = #{id}")
    Comment selectOneById(@Param("id") long id);

    // 删除： 返回影响行数
    @Delete("delete from comments where id = #{id}")
    int deleteOneById(@Param("id") long id);
}

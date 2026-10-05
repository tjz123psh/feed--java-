package io.github.p_feed.video;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;



@Mapper
public interface VideoMapper extends BaseMapper<Video>{


    @Select("select * from videos where author_id = #{author_id} order by create_time desc limit 200")
    List<Video> listByAuthorID(@Param("author_id") long authorId);

    // 评论/点赞/取赞共用 ; delta可为负 
    @Update("update videos set popularity = popularity + #{delta} where id = #{id}")
    int addPopularity(@Param("id") long id, @Param("delta") long delta);

    // 点赞计数原子 + 1 
    @Update("update videos set likes_count = likes_count + 1 where id = #{id}")
    int incrementLikesCount(@Param("id") long id);

    // 取消点赞原子 - 1 ，并且最小为0
    @Update("update videos set likes_count = GREATEST(likes_count - 1, 0) where id = #{id}")
    int decrementLikesCount(@Param("id") long id);

    // 热度带夹底的原子方法进行加减操作，防止负数
    @Update("update videos set popularity = GREATEST(popularity + #{delta}, 0) where id = #{id}")
    int addPopularityClamped(@Param("id") long id, @Param("delta") long delta);
}

package io.github.p_feed.like;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.github.p_feed.video.Video;
import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LikeMapper extends BaseMapper<Like> {

    // 点赞：唯一索引兜底幂等 ； 返回 false = 这行已经存在
    @Insert(
            "insert ignore into likes(video_id, account_id, created_at) values(#{video_id},"
                + " #{account_id}, now(3))")
    boolean insertIgnore(@Param("video_id") long videoId, @Param("account_id") long accountId);

    // 取消点赞： 返回影响行数
    @Delete("delete from likes where video_id = #{video_id} and account_id = #{account_id}")
    int deleteLike(@Param("video_id") long videoId, @Param("account_id") long accountId);

    // 是否赞过: 裸count，不走缓存
    @Select("select count(*) from likes where video_id = #{video_id} and account_id = #{account_id}")
    long countByVideoAndAccount(
            @Param("video_id") long videoId, @Param("account_id") long accountId);

    // 我赞过的视频： JOIN video , 按点赞时间倒序，限制200
    @Select(
            """
            select v.* from likes l
            join videos v on v.id = l.video_id
            where l.account_id = #{account_id}
            order by l.created_at desc
            limit 200
            """)
    List<Video> listLikedVideos(@Param("account_id") long accountId);


    // feed共用：批量提取我赞过的id。in一次性查完，不用循环
    @Select("<script>select video_id from likes where account_id = #{account_id} and video_id in "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'> #{id}</foreach>"
            + "</script>")
    List<Long> listLikedVideoIds(@Param("account_id") long accountId, @Param("ids") List<Long> ids);
}

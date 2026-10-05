package io.github.p_feed.feed;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import io.github.p_feed.video.Video;

/**
 * Feed 六条查询（第 5 步）。三种分页：时间游标 / 二元复合游标 / 三元复合游标。
 * 复合游标的 WHERE 是固定形状：WHERE (a < :a0) OR (a = :a0 AND id < :id0)
 * ——加主键 id 是为了排序键相等时仍然全序（不重不漏）。
 * 注意 <script> 里的小于号要写成 &lt;（XML 转义）。
 */

@Mapper
public interface FeedMapper{

    // 1.最新流: 时间游标
    @Select("""
            <script>
            select * from videos 
            <where>
                <if test="latestBefore != null"> create_time &lt; #{latestBefore}</if>
            </where>
            order by create_time desc
            limit #{limit}
            </script>
            """)
    List<Video> listLatest(@Param("limit") int limit, @Param("latestBefore") OffsetDateTime latestBefore);

    // 2.点赞榜：二元复合游标 ; 两个游标都为null = 第一页
    @Select(""" 
            <script>
            select * from videos
            <where>
                <if test="likesCount != null and id != null">
                    ((likes_count &lt; #{likesCount}) or (likes_count = #{likesCount} and id &lt; #{id}))
                </if>
            </where>
            order by likes_count desc , id desc
            limit #{limit}
            </script>
            """)
    List<Video> listLikesCountWithCursor(@Param("limit") int limit, @Param("likesCount") Long  likesCount, @Param("id") Long id);

    // 3.关注流：author 子查询 + 时间倒序 （viewAccountId > 0 才加子查询）
    @Select("""
            <script>
            select * from videos
            <where>
                <if test="viewAccountId > 0">
                    author_id in (select vlogger_id from socials where follower_id = #{viewAccountId})
                </if>
                <if test="latestBefore != null">
                    and create_time &lt; #{latestBefore}
                </if>
            </where>
            order by create_time desc
            limit #{limit}
            </script>
            """)
    List<Video> listByFollowing(@Param("limit") int limit, @Param("viewAccountId") long viewAccountId, @Param("latestBefore") OffsetDateTime latestBefore);

    // 4.热度DB兜底： 三元符合游标 ，这里的popularity允许为0 ，守卫看time/id
    @Select(""" 
            <script>
            select * from videos
            <where>
                <if test="popularityBefore != null and timeBefore != null and  idBefore != null">
                ((popularity &lt; #{popularityBefore})
                or (popularity = #{popularityBefore} and create_time &lt; #{timeBefore})
                or (popularity = #{popularityBefore} and create_time = #{timeBefore} and id &lt; #{idBefore}))
                </if>
            </where>
            order by popularity desc , create_time desc ,id desc
            limit #{limit}
            </script>
            """)
    List<Video> listByPopularity(@Param("limit") int limit, @Param("popularityBefore") Long popularityBefore, @Param("timeBefore") OffsetDateTime timeBefore, @Param("idBefore") Long idBefore);

    // 5.按id批量取，空列表由调用方短路
    @Select("<script>select * from videos where id in "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Video> listByIds(@Param("ids") List<Long> ids);
    
    // 6.话题页：join tags/video_tags, 按create_time desc（无游标，无id兜底）
    @Select(""" 
            select * from videos v
            join video_tags vt on vt.video_id = v.id
            join tags t on t.id = vt.tag_id
            where t.name = #{tagName}
            order by v.create_time desc
            limit #{limit}
            """)
    List<Video> listByTag(@Param("tagName") String tagName, @Param("limit")  int limit);

}

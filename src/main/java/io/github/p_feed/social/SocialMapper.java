package io.github.p_feed.social;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


@Mapper
public interface SocialMapper extends BaseMapper<Social> {

    // 是否已经关注 count
    @Select("select count(*) from socials where follower_id = #{follower_id} and vlogger_id = #{vlogger_id}")
    long countByFollowerAndVlogger(@Param("follower_id") long followerId, @Param("vlogger_id") long vloggerId);


    // 我的粉丝 -> follower_id  列表
    @Select("select follower_id from socials where vlogger_id = #{vlogger_id} limit 200")
    List<Long> listFollowersIds(@Param("vlogger_id") long vlogger_id);

    // 我关注的人-> 拿vlogger_id 列表
    @Select("select vlogger_id from socials where follower_id = #{follower_id} limit 200")
    List<Long> listVloggerIds(@Param("follower_id") long followerId);

    // 实时count: 粉丝数
    @Select("select count(*) from socials where vlogger_id =  #{vlogger_id}")
    long countFollowers(@Param("vlogger_id") long vlogger_id);

    // 实时count：关注数
    @Select("select count(*) from socials where follower_id = #{follower_id}")
    long countVloggers(@Param("follower_id") long followerId);
}

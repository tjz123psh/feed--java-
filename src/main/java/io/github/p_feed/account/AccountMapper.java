package io.github.p_feed.account;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {
    
    // 这里是登录/刷新的保存
    @Update("update accounts set token = #{token}, refresh_token = #{refreshToken} where id = #{id}")
    int updateTokens(@Param("id") long id, @Param("token") String token, @Param("refreshToken") String refreshToken);

    // 这里是登出/改密清理
    @Update("update accounts set token = NULL, refresh_token = NULL where id = #{id}")
    int clearTokens(@Param("id") long id);

    // 这里是同时改username和token
    @Update("update accounts set username = #{username} , token = #{token} where id = #{id}")
    int updateUsernameAndToken(@Param("id") long id , @Param("username") String username, @Param("token") String token);

    @Select("select * from accounts where refresh_token = #{refreshToken}")
    Account selectByRefreshToken(@Param("refreshToken") String refreshToken);

    // 按id批量查账号(空列表由调用方短路，不要拼 IN ()) 
    @Select("<script>select * from accounts where id in "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")

    List<Account> selectByIds(@Param("ids") List<Long> ids);
            


}

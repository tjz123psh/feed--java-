package io.github.p_feed.video;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;


@Mapper
public interface TagMapper extends BaseMapper<Tag>{

    @Insert("insert ignore into tags(name) values(#{name})")
    int insertIgnore(@Param("name") String name);
}

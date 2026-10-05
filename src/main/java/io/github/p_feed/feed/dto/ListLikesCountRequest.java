package io.github.p_feed.feed.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// 点赞榜请求：{limit, likes_count_before?, id_before?}；两个游标要么都传、要么都不传
public record ListLikesCountRequest(int limit, @JsonProperty("likes_count_before") Long likesCountBefore, @JsonProperty("id_before") Long idBefore){}

package io.github.p_feed.feed.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

// 推荐流响应: video_list/next_time/has_more，next_time 单位为毫秒
public record ListLatestResponse(
        @JsonProperty("video_list") List<FeedVideoItem> videoList,
        @JsonProperty("next_time") long nextTime,
        @JsonProperty("has_more") boolean hasMore
        
){}

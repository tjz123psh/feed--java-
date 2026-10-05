package io.github.p_feed.feed.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
//  关注流响应：next_time 是【秒】且恒出现（空页为 0）；has_more = 本页装满
public record ListByFollowingResponse(
        @JsonProperty("video_list") List<FeedVideoItem> videoList,
        @JsonProperty("next_time") long nextTime,
        @JsonProperty("has_more") boolean hasMore
        ){}

package io.github.p_feed.feed.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

// 话题页响应
public record FeedListResponse(@JsonProperty("video_list") List<FeedVideoItem> videoList){}
// 空话题返回[]

package io.github.p_feed.feed.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// 推荐流请求: latest_time 单位为毫秒 , 0/不传 = 首屏
public record ListLatestRequest(int limit, @JsonProperty("latest_time") Long latestTime){}
    

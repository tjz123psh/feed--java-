package io.github.p_feed.feed.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// 关注流请求：{limit, latest_time?}；latest_time 单位【秒】，0/不传 = 第一页
public record ListByFollowingRequest(
        int limit,
        @JsonProperty("latest_time") Long latestTime
){}

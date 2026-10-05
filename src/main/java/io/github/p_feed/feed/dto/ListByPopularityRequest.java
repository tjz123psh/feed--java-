package io.github.p_feed.feed.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
/** 热榜请求：as_of 单位秒（0=当前分钟）、offset 是条数下标、三元游标 latest_before 是 RFC3339 字符串 */
public record ListByPopularityRequest(
        int limit,
        @JsonProperty("as_of") long asOf,
        int offset,
        @JsonProperty("latest_popularity") long latestPopularity,
        @JsonProperty("latest_before") OffsetDateTime latestBefore,
        @JsonProperty("latest_id_before") Long latestIdBefore
){}

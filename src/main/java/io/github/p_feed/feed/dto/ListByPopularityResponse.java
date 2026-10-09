package io.github.p_feed.feed.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
/** 热榜响应：as_of=0 表示"这页走的是 DB 兜底"；三个 next_latest_* 为空时键不出现（NON_NULL 会省略空键） */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListByPopularityResponse(
        @JsonProperty("video_list") List<FeedVideoItem> videoList,
        @JsonProperty("as_of") long asOf,
        @JsonProperty("next_offset") int nextOffset,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("next_latest_popularity") Long nextLatestPopularity,
        @JsonProperty("next_latest_before") OffsetDateTime nextLatestBefore,
        @JsonProperty("next_latest_id_before") Long nextLatestIdBefore
){}

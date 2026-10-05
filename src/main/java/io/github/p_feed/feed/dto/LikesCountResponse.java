package io.github.p_feed.feed.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 点赞榜响应。NON_NULL 对齐 Go 的 omitempty：next_* 为空时整个键不出现。
 * has_more 的语义是"本页装满"，不是"后面还有"（原版如此）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LikesCountResponse(
        @JsonProperty("video_list") List<FeedVideoItem> videoList,
        @JsonProperty("next_likes_count_before") Long nextLikesCountBefore,
        @JsonProperty("next_id_before") Long nextIdBefore,
        @JsonProperty("has_more") boolean hasMore
){}

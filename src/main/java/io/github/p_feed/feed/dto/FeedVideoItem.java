package io.github.p_feed.feed.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

// Feed 元素（五个流共用）
public record FeedVideoItem(
    Long id,
    FeedAuthor author,
    String title,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) String description,
    @JsonProperty("play_url") String playUrl,
    @JsonProperty("cover_url") String coverUrl,
    @JsonProperty("create_time") long createTime,
    @JsonProperty("likes_count") long likesCount,
    @JsonProperty("is_liked") boolean isLiked
    
){}

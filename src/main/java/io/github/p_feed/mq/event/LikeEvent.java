package io.github.p_feed.mq.event;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LikeEvent(
    @JsonProperty("event_id") String eventId,
    String action,
    @JsonProperty("user_id") long userId,
    @JsonProperty("video_id") long videoId,
    @JsonProperty("occurred_at") String occurredAt
){} 

package io.github.p_feed.mq.event;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PopularityEvent(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("video_id") long videoId,
    long change,
    @JsonProperty("occurred_at") String occurredAt
){}

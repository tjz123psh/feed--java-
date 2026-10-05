package io.github.p_feed.mq.event;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TimelineEvent(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("video_id") long videoId,
    @JsonProperty("create_time") long createTime,
    @JsonProperty("occurred_at") String occurredAt
){}

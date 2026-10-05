package io.github.p_feed.mq.event;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SocialEvent(
    @JsonProperty("event_id") String eventId,
    String action,
    @JsonProperty("follower_id") long followerId,
    @JsonProperty("vlogger_id") long vloggerId,
    @JsonProperty("occurred_at") String occurredAt
){}

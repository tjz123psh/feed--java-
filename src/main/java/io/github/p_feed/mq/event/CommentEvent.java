package io.github.p_feed.mq.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
@JsonInclude(JsonInclude.Include.NON_NULL)

public record CommentEvent(
    @JsonProperty("event_id") String eventId,
    String action,
    @JsonProperty("comment_id") Long commentId,
    String username,
    @JsonProperty("video_id") Long videoId,
    @JsonProperty("author_id") Long authorId,
    String content,
    @JsonProperty("occurred_at") String occurredAt
){}

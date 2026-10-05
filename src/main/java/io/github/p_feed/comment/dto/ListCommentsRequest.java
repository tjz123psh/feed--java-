package io.github.p_feed.comment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ListCommentsRequest(@JsonProperty("video_id") Long videoId){}

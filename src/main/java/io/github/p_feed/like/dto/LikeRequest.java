package io.github.p_feed.like.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LikeRequest(@JsonProperty("video_id") Long videoId){}

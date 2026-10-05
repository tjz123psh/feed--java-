package io.github.p_feed.like.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IsLikedResponse(@JsonProperty("is_liked") boolean isLiked){}

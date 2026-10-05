package io.github.p_feed.video.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PublishVideoRequest(

    String title,
    String description,
    @JsonProperty("play_url") String playUrl,
    @JsonProperty("cover_url") String coverUrl
){}

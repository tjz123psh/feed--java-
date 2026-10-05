package io.github.p_feed.video.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UploadVideoResponse(String url ,@JsonProperty("play_url") String playUrl){
    public static UploadVideoResponse of(String url){
        return new UploadVideoResponse(url, url);
    }
}


package io.github.p_feed.video.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UploadCoverResponse(String url ,@JsonProperty("cover_url") String coverUrl){
    public static UploadCoverResponse of(String url){
        return new UploadCoverResponse(url, url);
    }
}

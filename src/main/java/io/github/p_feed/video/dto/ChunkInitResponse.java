package io.github.p_feed.video.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChunkInitResponse(
    @JsonProperty("upload_id") String uploadId,
    @JsonProperty("uploaded_chunks") int[] uploaded_chunks  
){}

package io.github.p_feed.video.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChunkCompleteRequest(@JsonProperty("upload_id") String uploadId){}

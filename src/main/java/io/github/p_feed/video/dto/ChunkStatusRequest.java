package io.github.p_feed.video.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChunkStatusRequest(@JsonProperty("upload_id") String uploadId){}

package io.github.p_feed.video.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChunkUploadResponse(@JsonProperty("chunk_index") int chunkIndex){}

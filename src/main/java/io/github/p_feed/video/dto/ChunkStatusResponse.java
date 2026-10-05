package io.github.p_feed.video.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// status响应：已传下标数组（不是位图）+ 总片数
public record ChunkStatusResponse(@JsonProperty("upload_id") String uploadId,
                                    @JsonProperty("uploaded_chunks") int[] uploadedChunks,   
                                    @JsonProperty("total_chunks") int totalChunks   
){}

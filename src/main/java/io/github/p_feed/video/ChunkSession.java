package io.github.p_feed.video;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChunkSession(
    @JsonProperty("upload_id") String uploadId,
    @JsonProperty("account_id") long accountId,
    @JsonProperty("filename") String filename,
    @JsonProperty("file_size") long fileSize,
    @JsonProperty("chunk_size") int chunkSize,
    @JsonProperty("total_chunks") int totalChunks,
    @JsonProperty("file_hash") String fileHash,
    @JsonProperty("uploaded_bits") boolean[] uploadedBits   
){
    
}

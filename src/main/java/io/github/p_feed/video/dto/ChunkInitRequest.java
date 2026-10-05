package io.github.p_feed.video.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;
public record ChunkInitRequest(
        @JsonProperty("filename") @NotBlank String fileName,
        @JsonProperty("file_size") @NotNull @Min(1) Long fileSize,
        @JsonProperty("chunk_size") @NotNull @Min(1) Integer chunkSize,
        @JsonProperty("total_chunks") @NotNull @Min(1) Integer totalChunks,
        @JsonProperty("file_hash") @NotBlank String fileHash) {}

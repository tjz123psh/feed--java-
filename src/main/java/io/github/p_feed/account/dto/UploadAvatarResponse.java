package io.github.p_feed.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UploadAvatarResponse(@JsonProperty("avatar_url") String avatarUrl){}

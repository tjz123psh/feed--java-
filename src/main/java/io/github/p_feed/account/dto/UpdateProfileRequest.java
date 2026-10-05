package io.github.p_feed.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateProfileRequest(@JsonProperty("avatar_url") String avatarUrl, String bio){}

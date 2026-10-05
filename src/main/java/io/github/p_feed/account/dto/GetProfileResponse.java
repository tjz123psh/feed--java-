package io.github.p_feed.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.p_feed.account.Account;

public record GetProfileResponse(Account account, @JsonProperty("video_count") long videoCount, @JsonProperty("total_likes") long totalLikes, @JsonProperty("follower_count") long followerCount, @JsonProperty("vlogger_count") long vloggerCount){}

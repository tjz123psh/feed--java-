package io.github.p_feed.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenPairResponse(String token ,@JsonProperty("refresh_token") String refreshToken, @JsonProperty("account_id") Long accountId, String username){}

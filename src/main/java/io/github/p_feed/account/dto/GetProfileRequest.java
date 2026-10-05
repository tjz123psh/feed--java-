package io.github.p_feed.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetProfileRequest(@JsonProperty("account_id") Long accountId){}

package io.github.p_feed.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChangePasswordRequest(@JsonProperty("old_password") String oldPassword, @JsonProperty("new_password") String newPassword, String username){}

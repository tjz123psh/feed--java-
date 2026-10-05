package io.github.p_feed.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RenameRequest(@JsonProperty("new_username") String newUsername){}

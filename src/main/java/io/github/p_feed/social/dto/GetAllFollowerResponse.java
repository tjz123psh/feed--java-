package io.github.p_feed.social.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.p_feed.account.Account;

//  粉丝列表响应：{followers, follower_count} 
public record GetAllFollowerResponse(List<Account> followers, @JsonProperty("follower_count") long followerCount){}

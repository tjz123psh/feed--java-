package io.github.p_feed.social.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
// FollowRequest.java —— {vlogger_id}（follow / unfollow / getAllFollowers 共用这个键名）
public record FollowRequest(@JsonProperty("vlogger_id") Long vloggerId){}

package io.github.p_feed.social.dto;

import com.fasterxml.jackson.annotation.JsonProperty;



// SocialCounts.java —— {follower_count, vlogger_count}（getCounts 的响应；两个计数接口也复用）
public record SocialCounts(@JsonProperty("follower_count") long followerCount, @JsonProperty("vlogger_count") long vloggerCount){}

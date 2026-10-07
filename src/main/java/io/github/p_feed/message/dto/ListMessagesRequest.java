package io.github.p_feed.message.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// /** 拉会话：{"peer_id": 2} *
public record ListMessagesRequest(@JsonProperty("peer_id") Long peerId){}

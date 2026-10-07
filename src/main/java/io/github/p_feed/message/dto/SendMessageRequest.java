package io.github.p_feed.message.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 发私信：{"to_id": 2, "content": "..."}；from_id 不在请求体里，只信登录态 */
public record SendMessageRequest(@JsonProperty("to_id") Long toId, String content){}

package io.github.p_feed.message.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.p_feed.message.Message;

/** 会话响应：{"messages":[...]}；空会话要是 [] 不是 null */
public record MessageListResponse(@JsonProperty("messages") List<Message> messages) {}

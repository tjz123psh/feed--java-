package io.github.p_feed.notification.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.p_feed.notification.Notification;

/** 通知列表响应：{"notifications":[...]}；空为 [] */
public record NotificationListResponse(@JsonProperty("notifications") List<Notification> notifications){}

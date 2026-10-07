package io.github.p_feed.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 标记已读：{"id": 1}；id 省略（或整个 body 省略）= 全部标已读 */
public record MarkReadRequest(@JsonProperty ("id") Long id){}

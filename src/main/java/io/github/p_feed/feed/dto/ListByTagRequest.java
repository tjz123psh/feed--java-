package io.github.p_feed.feed.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// 话题页请求：
public record ListByTagRequest(@JsonProperty("tag_name") String tagName, int limit){}

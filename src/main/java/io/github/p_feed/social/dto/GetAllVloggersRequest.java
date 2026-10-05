package io.github.p_feed.social.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 关注列表请求：{"follower_id": N}；不传（或传 0）表示看自己关注的人 */
public record GetAllVloggersRequest(@JsonProperty("follower_id") Long followerId){}

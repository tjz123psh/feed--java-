package io.github.p_feed.social.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 粉丝列表请求：{"vlogger_id": N}；不传（或传 0）表示看自己的粉丝 */

public record GetAllFollowersRequest(@JsonProperty("vlogger_id") Long vloggerId){}

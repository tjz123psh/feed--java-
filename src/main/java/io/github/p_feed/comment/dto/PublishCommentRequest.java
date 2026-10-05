package io.github.p_feed.comment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 发评论：{"video_id": 1, "content": "..."}；author_id/username 只取登录态，本 dto 不接收 */
public record PublishCommentRequest(@JsonProperty("video_id") Long videoId, String content){}

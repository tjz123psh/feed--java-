package io.github.p_feed.comment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// 删除评论：{"comment_id"}
public record DeleteCommentRequest(@JsonProperty("comment_id") Long commentId){}

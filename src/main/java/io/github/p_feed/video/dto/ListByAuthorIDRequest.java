package io.github.p_feed.video.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ListByAuthorIDRequest(@JsonProperty("author_id") long authorId){}

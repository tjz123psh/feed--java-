package io.github.p_feed.feed.dto;

// feed元素里面的作者（取自videos的冗余列，不查accounts 
public record FeedAuthor(Long id, String username){}

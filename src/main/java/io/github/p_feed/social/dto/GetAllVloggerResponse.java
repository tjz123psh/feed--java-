package io.github.p_feed.social.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.p_feed.account.Account;


// 关注列表响应：{vloggers, vlogger_count} 
public record GetAllVloggerResponse(List<Account> vloggers, @JsonProperty("vlogger_count") long vloggeCount){}

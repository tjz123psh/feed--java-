package io.github.p_feed.social;

import io.github.p_feed.account.dto.MessageResponse;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.social.dto.FollowRequest;
import io.github.p_feed.social.dto.GetAllFollowerResponse;
import io.github.p_feed.social.dto.GetAllFollowersRequest;
import io.github.p_feed.social.dto.GetAllVloggerResponse;
import io.github.p_feed.social.dto.GetAllVloggersRequest;
import io.github.p_feed.social.dto.SocialCounts;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/social")
public class SocialController {
    private final SocialService socialService;

    public SocialController(SocialService socialService) {
        this.socialService = socialService;
    }

    @PostMapping("/follow")
    public MessageResponse follow(HttpServletRequest request, @RequestBody FollowRequest body) {
        return socialService.follow(accountId(request), body.vloggerId());
    }

    @PostMapping("/unfollow")
    public MessageResponse unfollow(HttpServletRequest request, @RequestBody FollowRequest body) {
        return socialService.unfollow(accountId(request), body.vloggerId());
    }

    // 获取请求体，如果这里id为null或者0，说明用户想取的是用户态自己的信息，不是别人的
    // 算是复用吧
    @PostMapping("/getAllFollowers")
    public GetAllFollowerResponse getAllFollows(
            HttpServletRequest request,
            @RequestBody(required = false) GetAllFollowersRequest body) {
        Long vloggerId = body == null ? null : body.vloggerId();
        if (vloggerId == null || vloggerId == 0L) {
            vloggerId = accountId(request);
        }
        return socialService.getAllFollowers(vloggerId);
    }

    @PostMapping("/getAllVloggers")
    public GetAllVloggerResponse getAllVloggers(
            HttpServletRequest request, @RequestBody(required = false) GetAllVloggersRequest body) {
        Long followerId = body == null ? null : body.followerId();
        if (followerId == null || followerId == 0L) {
            followerId = accountId(request);
        }
        return socialService.getAllVloggers(followerId);
    }

    // 两个计数：只取登录态
    @PostMapping("/getCounts")
    public SocialCounts getCounts(HttpServletRequest request) {
        return socialService.getCounts(accountId(request));
    }

    private long accountId(HttpServletRequest request) {
        Long accountId = (Long) request.getAttribute("accountID");
        if (accountId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.ACCOUNT_ID_NOT_FOUND);
        }
        return accountId;
    }
}

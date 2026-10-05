package io.github.p_feed.feed;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.p_feed.feed.dto.FeedListResponse;
import io.github.p_feed.feed.dto.LikesCountResponse;
import io.github.p_feed.feed.dto.ListByFollowingRequest;
import io.github.p_feed.feed.dto.ListByFollowingResponse;
import io.github.p_feed.feed.dto.ListByPopularityRequest;
import io.github.p_feed.feed.dto.ListByPopularityResponse;
import io.github.p_feed.feed.dto.ListByTagRequest;
import io.github.p_feed.feed.dto.ListLatestRequest;
import io.github.p_feed.feed.dto.ListLatestResponse;
import io.github.p_feed.feed.dto.ListLikesCountRequest;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;

@RestController
@RequestMapping("/feed")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }
 /** 话题页：软鉴权——没带 token 就是匿名（viewer=0，is_liked 全 false） */
    @PostMapping("/listByTag")
    public FeedListResponse listByTag(
            HttpServletRequest request, @RequestBody ListByTagRequest body) {
        return feedService.listByTag(body.tagName(), body.limit(), viewerId(request));
    }


    // 点赞榜：软鉴权（匿名是0  ）
    @PostMapping("/listLikesCount")
        public LikesCountResponse listLikesCount(HttpServletRequest request, @RequestBody ListLikesCountRequest body) {
        return feedService.listLikesCount(body.limit(), body.likesCountBefore(), body.idBefore(), viewerId(request));
        }
        
    // 关注流: 硬 JWT（WebConfig 已挂拦截器），未登录请求根本到不了这里
    @PostMapping("/listByFollowing")
        public ListByFollowingResponse listByFollowing(HttpServletRequest request, @RequestBody ListByFollowingRequest body) {
        return feedService.listByFollowing(body.limit(), body.latestTime(), requiredViewerId(request));
        }
        
    // 推荐流（首页）: 软鉴权，未登录 viewer=0
    @PostMapping("/listLatest")
    public ListLatestResponse listLatest(HttpServletRequest request, @RequestBody ListLatestRequest body){
        return feedService.listLatest(body.limit(), body.latestTime(), viewerId(request));
    }
        
    // 热榜: 软鉴权 ; 同一asof的翻页用同一份快照
    @PostMapping("/listByPopularity")
        public ListByPopularityResponse listByPopularity(HttpServletRequest request, @RequestBody ListByPopularityRequest body) {
        return feedService.listByPopularity(body, viewerId(request));
        }
        

  private long requiredViewerId(HttpServletRequest request) {
        Object accountId = request.getAttribute("accountID");
        if(accountId == null){
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.ACCOUNT_ID_NOT_FOUND);
        }

        return (Long) accountId;
        
    }
  /** 软鉴权下没登录就没有 accountID 属性：匿名 = 0 */
    private long viewerId(HttpServletRequest request) {
        Object accountId = request.getAttribute("accountID");
        return accountId == null ? 0L : (Long) accountId;
    }
}

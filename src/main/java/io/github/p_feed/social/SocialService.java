package io.github.p_feed.social;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.account.dto.MessageResponse;
import io.github.p_feed.feed.FollowingFeedCache;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.social.dto.GetAllFollowerResponse;
import io.github.p_feed.social.dto.GetAllVloggerResponse;
import io.github.p_feed.social.dto.SocialCounts;

import org.slf4j.Logger;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SocialService {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(SocialService.class);

    private final SocialMapper socialMapper;
    private final AccountMapper accountMapper;
    private final SocialEventPublisher socialEventPublisher;
    private final FollowingFeedCache followingFeedCache;

    public SocialService(
            SocialMapper socialMapper,
            AccountMapper accountMapper,
            SocialEventPublisher socialEventPublisher,
            FollowingFeedCache followingFeedCache) {
        this.socialMapper = socialMapper;
        this.accountMapper = accountMapper;
        this.socialEventPublisher = socialEventPublisher;
        this.followingFeedCache = followingFeedCache;
    }

    // 关注：校验顺序固定为 参数(handler) → 双方账号存在 → 不能关注自己 → 不能重复关注。
    // 写序：先 INSERT → 再失效关注流缓存 → 最后发消息（失败只记日志）。
    // 并发防线是唯一索引 (follower_id, vlogger_id)，DuplicateKeyException 转 already followed。
    public MessageResponse follow(long followerId, Long vloggerId) {
        requireVloggerId(vloggerId);

        Account follower = accountMapper.selectById(followerId);
        if (follower == null) {
            throw new ApiException((HttpStatus.INTERNAL_SERVER_ERROR), Errors.ACCOUNT_NOT_FOUND);
        }

        Account vlogger = accountMapper.selectById(vloggerId);
        if (vlogger == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.ACCOUNT_NOT_FOUND);
        }

        if (followerId == vloggerId) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.CAN_NOT_FOLLOW_SELF);
        }

        if (socialMapper.countByFollowerAndVlogger(followerId, vloggerId) > 0) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.ALREADY_FOLLOWED);
        }

        Social social = new Social();
        social.setFollowerId(followerId);
        social.setVloggerId(vloggerId);
        try {
            socialMapper.insert(social);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时通过"已关注"检查，唯一索引兜住后者
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.ALREADY_FOLLOWED);
        }

        invalidateFollowingFeedCache(followerId);

        // 发布通知来关注
        if (!socialEventPublisher.publishFollow(followerId, vloggerId)) {
            log.debug("follow event not published (noop): {}", followerId);
        }

        return new MessageResponse("followed");
    }

    public MessageResponse unfollow(long followerId, Long vloggerId) {
        requireVloggerId(vloggerId);
        Account follower = accountMapper.selectById(followerId);
        if (follower == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.ACCOUNT_NOT_FOUND);
        }

        Account vlogger = accountMapper.selectById(vloggerId);
        if (vlogger == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.ACCOUNT_NOT_FOUND);
        }

        if (socialMapper.countByFollowerAndVlogger(followerId, vloggerId) == 0) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.NOT_FOLLOWED);
        }

        socialMapper.delete(
                new LambdaQueryWrapper<Social>()
                        .eq(Social::getFollowerId, followerId)
                        .eq(Social::getVloggerId, vloggerId));

        invalidateFollowingFeedCache(followerId);

        if (!socialEventPublisher.publishUnFollow(followerId, vloggerId)) {
            log.debug("unfollow event not published (noop): {}", followerId);
        }

        return new MessageResponse("unfollowed");
    }

    // 查找粉丝列表 + 粉丝数，targetId 为 0 时由 Controller 传登录态进来
    public GetAllFollowerResponse getAllFollowers(long vloggerId) {
        Account vlogger = accountMapper.selectById(vloggerId);
        if (vlogger == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.ACCOUNT_NOT_FOUND);
        }

        List<Long> followerIds = socialMapper.listFollowersIds(vloggerId);
        List<Account> followers = loadAccounts(followerIds);
        long count = socialMapper.countFollowers(vloggerId);
        return new GetAllFollowerResponse(followers, count);
    }

    // 查找关注列表 + 关注数
    public GetAllVloggerResponse getAllVloggers(long followerId) {
        Account follower = accountMapper.selectById(followerId);
        if (follower == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.ACCOUNT_NOT_FOUND);
        }

        List<Long> vloggerIds = socialMapper.listVloggerIds(followerId);
        List<Account> vloggers = loadAccounts(vloggerIds);
        long count = socialMapper.countVloggers(followerId);
        return new GetAllVloggerResponse(vloggers, count);
    }

    // 两个实时计数：count socials，不校验登录态
    public SocialCounts getCounts(long accountId) {
        long followerCount = socialMapper.countFollowers(accountId);
        long vloggerCount = socialMapper.countVloggers(accountId);
        return new SocialCounts(followerCount, vloggerCount);
    }

    private List<Account> loadAccounts(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        return accountMapper.selectByIds(ids);
    }

    private void invalidateFollowingFeedCache(long followerId) {
        // 第七步：实现SCAN删除v1:feed:listByFollowing:*:accountID={id}:
        // 关注/取关后失败该账号的关注流整页缓存
        followingFeedCache.invalidate(followerId);
    }

    private void requireVloggerId(Long vloggerId) {
        if (vloggerId == null || vloggerId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.VLOGGER_ID_IS_REQUIRED);
        }
    }
}

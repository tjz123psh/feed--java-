package io.github.p_feed.feed;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.p_feed.ApiApplication;
import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.account.AccountService;
import io.github.p_feed.account.dto.RegisterRequest;
import io.github.p_feed.feed.dto.FeedVideoItem;
import io.github.p_feed.feed.dto.ListByFollowingResponse;
import io.github.p_feed.infra.RedisKeys;
import io.github.p_feed.social.SocialService;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 7 步端到端：纯拉模型（关注后立刻能拉到）+ SCAN 失效真的生效。
 * 真库真 Redis：A 关注 B → 第一页含 B 的视频 → 再请求（命中缓存）→ A 关注 C →
 * 缓存被 SCAN 删掉 → 第一页立刻同时含 B、C 的视频。
 */
@SpringBootTest(classes = ApiApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FollowingFeedIntegrationTest {

    @Autowired private FeedService feedService;
    @Autowired private SocialService socialService;
    @Autowired private AccountService accountService;
    @Autowired private AccountMapper accountMapper;
    @Autowired private VideoMapper videoMapper;
    @Autowired private FollowingFeedCache followingFeedCache;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisKeys keys;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private long accA;
    private long accB;
    private long accC;
    private long videoB;
    private long videoC;

    @BeforeAll
    void seed() {
        accA = register("ffi_a_" + suffix);
        accB = register("ffi_b_" + suffix);
        accC = register("ffi_c_" + suffix);
        OffsetDateTime base = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        videoB = insertVideo(accB, "ffi_b_" + suffix, "B 的视频", base.minusSeconds(5));
        videoC = insertVideo(accC, "ffi_c_" + suffix, "C 的视频", base);
    }

    @AfterAll
    void cleanup() {
        followingFeedCache.invalidate(accA);
        jdbcTemplate.update("delete from socials where follower_id in (?,?,?) or vlogger_id in (?,?,?)",
                accA, accB, accC, accA, accB, accC);
        jdbcTemplate.update("delete from videos where author_id in (?,?,?)", accA, accB, accC);
        jdbcTemplate.update("delete from accounts where id in (?,?,?)", accA, accB, accC);
    }

    @Test
    void newFollow_appearsImmediately_andInvalidationWorks() {
        // 1) A 关注 B → 第一页立刻含 B 的视频（纯拉模型：不需要给粉丝补数据）
        socialService.follow(accA, accB);
        ListByFollowingResponse page1 = feedService.listByFollowing(10, null, accA);
        assertThat(ids(page1)).as("关注后立刻拉到 B 的视频").contains(videoB);

        // 2) 再请求一次：命中整页缓存，页键存在
        ListByFollowingResponse page2 = feedService.listByFollowing(10, null, accA);
        assertThat(ids(page2)).isEqualTo(ids(page1));
        String key = keys.key("feed:listByFollowing:limit=10:accountID=%d:before=0", accA);
        assertThat(redis.hasKey(key)).as("登录用户的页缓存已写入").isTrue();

        // 3) A 关注 C → SCAN 失效删掉 A 的页键 → 第一页立刻同时含 B、C 的视频
        socialService.follow(accA, accC);
        assertThat(redis.hasKey(key)).as("关注动作触发 SCAN 失效").isFalse();
        ListByFollowingResponse page3 = feedService.listByFollowing(10, null, accA);
        assertThat(ids(page3)).as("新关注的博主视频立刻出现").contains(videoB, videoC);
    }

    private List<Long> ids(ListByFollowingResponse response) {
        return response.videoList().stream().map(FeedVideoItem::id).toList();
    }

    private long register(String username) {
        accountService.register(new RegisterRequest(username, "pw123456"));
        Account account = accountMapper.selectOne(
                new LambdaQueryWrapper<Account>().eq(Account::getUsername, username));
        return account.getId();
    }

    private long insertVideo(long authorId, String username, String title, OffsetDateTime createTime) {
        Video v = new Video();
        v.setAuthorId(authorId);
        v.setUsername(username);
        v.setTitle(title);
        v.setDescription("d");
        v.setPlayUrl("http://localhost/static/probe/p.mp4");
        v.setCoverUrl("http://localhost/static/probe/c.jpg");
        v.setCreateTime(createTime);
        v.setLikesCount(0L);
        v.setPopularity(0L);
        videoMapper.insert(v);
        return v.getId();
    }
}

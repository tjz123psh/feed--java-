package io.github.p_feed.like;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.p_feed.ApiApplication;
import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.account.AccountService;
import io.github.p_feed.account.dto.RegisterRequest;
import io.github.p_feed.video.PopularityEventPublisher;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 并发正确性：预检会漏——两个线程可以同时通过 count==0 的判断，
 * 真正的防线是 (video_id, account_id) 唯一索引 + insertIgnore 返回值；
 * unlike 一侧则是 deleteLike 的影响行数决定"减不减"。
 */
@SpringBootTest(classes = ApiApplication.class)
class LikeConcurrencyTest {

    @Autowired private LikeService likeService;
    @Autowired private LikeMapper likeMapper;
    @Autowired private AccountService accountService;
    @Autowired private AccountMapper accountMapper;
    @Autowired private VideoMapper videoMapper;

    // 阶段 7：发布器 mock 成"投递失败"→ 固定走同步路径。本测试只测并发正确性，
    // 不该依赖 Worker 是否在跑（真实发布器 + 无消费者时，断言会输给赛跑）。
    @MockitoBean private LikeEventPublisher likeEventPublisher;
    @MockitoBean private PopularityEventPublisher popularityEventPublisher;

    @BeforeEach
    void forceSyncPath() {
        when(likeEventPublisher.publishLike(anyLong(), anyLong())).thenReturn(false);
        when(likeEventPublisher.publishUnlike(anyLong(), anyLong())).thenReturn(false);
        when(popularityEventPublisher.publishPopularity(anyLong(), anyLong())).thenReturn(false);
    }

    @Test
    void concurrentLike_onlyOneRowAndOneIncrement() throws Exception {
        Account acc = registerFreshAccount("cc_like_");
        Video video = firstVideo();
        long before = video.getLikesCount();

        try {
            // 两线程同时点赞同一视频
            runConcurrently(2, () -> likeService.like(acc.getId(), video.getId()));

            assertThat(likeMapper.countByVideoAndAccount(video.getId(), acc.getId())).isEqualTo(1);
            assertThat(videoMapper.selectById(video.getId()).getLikesCount()).isEqualTo(before + 1);
        } finally {
            // 清理放 finally：断言失败时也要清，否则残留行 + 漂移的计数会污染后续测试
            while (likeMapper.deleteLike(video.getId(), acc.getId()) == 1) {
                videoMapper.decrementLikesCount(video.getId());
                videoMapper.addPopularityClamped(video.getId(), -1L);
            }
        }
    }

    @Test
    void concurrentUnlike_onlyOneDecrement() throws Exception {
        Account acc = registerFreshAccount("cc_unlike_");
        Video video = firstVideo();

        likeService.like(acc.getId(), video.getId());            // 先赞上
        long before = videoMapper.selectById(video.getId()).getLikesCount();

        // 两线程同时取赞
        runConcurrently(2, () -> likeService.unlike(acc.getId(), video.getId()));

        assertThat(likeMapper.countByVideoAndAccount(video.getId(), acc.getId())).isZero();
        assertThat(videoMapper.selectById(video.getId()).getLikesCount()).isEqualTo(before - 1);
    }

    /**
     * 让 n 个线程在同一个瞬间起跑执行任务。
     * 失败方抛「user has liked / has not liked」属预期，吞掉即可——断言看的是最终数据。
     */
    private void runConcurrently(int n, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                } catch (Exception ignored) {
                    // 并发下的失败方，属预期
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
    }

    private Account registerFreshAccount(String prefix) {
        String name = prefix + UUID.randomUUID().toString().substring(0, 8);
        accountService.register(new RegisterRequest(name, "pw123456"));
        return accountMapper.selectOne(
                new LambdaQueryWrapper<Account>().eq(Account::getUsername, name));
    }

    private Video firstVideo() {
        Video video = videoMapper.selectOne(new LambdaQueryWrapper<Video>().last("limit 1"));
        assertThat(video).as("库里应至少有一条视频").isNotNull();
        return video;
    }
}

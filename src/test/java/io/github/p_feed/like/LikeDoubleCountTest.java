package io.github.p_feed.like;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.video.PopularityEventPublisher;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoCacheService;
import io.github.p_feed.video.VideoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 7 步"双计修复"回归：落库归 LikeConsumer（投递成功时 HTTP 侧不碰 DB），
 * 热度归 PopularityConsumer（只有投递失败才由 Service 补 updatePopularityCache）。
 * 两条通道互相独立——一条失败只触发它自己的兜底，另一条仍交给消费者。
 */
@SpringBootTest(classes = ApiApplication.class)
class LikeDoubleCountTest {

    @Autowired private LikeService likeService;
    @MockitoBean private LikeMapper likeMapper;
    @MockitoBean private VideoMapper videoMapper;
    @MockitoBean private VideoCacheService videoCacheService;
    @MockitoBean private LikeEventPublisher likeEventPublisher;
    @MockitoBean private PopularityEventPublisher popularityEventPublisher;

    @Test
    void likeBothChannelsSucceed_httpSideTouchesNeitherDbNorCache() {
        stubPrecheck();
        when(likeEventPublisher.publishLike(1L, 7L)).thenReturn(true);
        when(popularityEventPublisher.publishPopularity(7L, 1L)).thenReturn(true);

        assertThat(likeService.like(1L, 7L).message()).isEqualTo("like success");

        verify(likeEventPublisher).publishLike(1L, 7L);
        verify(popularityEventPublisher).publishPopularity(7L, 1L);
        verify(likeMapper, never()).insertIgnore(anyLong(), anyLong());
        verify(videoMapper, never()).incrementLikesCount(anyLong());
        verify(videoMapper, never()).addPopularity(anyLong(), anyLong());
        verify(videoCacheService, never()).updatePopularityCache(anyLong(), anyLong());
    }

    @Test
    void unlikeBothChannelsSucceed_httpSideTouchesNeitherDbNorCache() {
        when(likeMapper.countByVideoAndAccount(7L, 1L)).thenReturn(1L);
        when(videoMapper.selectById(7L)).thenReturn(new Video());
        when(likeEventPublisher.publishUnlike(1L, 7L)).thenReturn(true);
        when(popularityEventPublisher.publishPopularity(7L, -1L)).thenReturn(true);

        likeService.unlike(1L, 7L);

        verify(likeMapper, never()).deleteLike(anyLong(), anyLong());
        verify(videoMapper, never()).decrementLikesCount(anyLong());
        verify(videoMapper, never()).addPopularityClamped(anyLong(), anyLong());
        verify(videoCacheService, never()).updatePopularityCache(anyLong(), anyLong());
    }

    @Test
    void onlyPopularityFails_onlyCacheFallbackRuns() {
        stubPrecheck();
        when(likeEventPublisher.publishLike(1L, 7L)).thenReturn(true);
        when(popularityEventPublisher.publishPopularity(7L, 1L)).thenReturn(false);

        likeService.like(1L, 7L);

        verify(likeMapper, never()).insertIgnore(anyLong(), anyLong());
        verify(videoMapper, never()).incrementLikesCount(anyLong());
        verify(videoCacheService).updatePopularityCache(7L, 1L);
    }

    @Test
    void onlyDbFails_onlyDbFallbackRuns() {
        stubPrecheck();
        when(likeEventPublisher.publishLike(1L, 7L)).thenReturn(false);
        when(popularityEventPublisher.publishPopularity(7L, 1L)).thenReturn(true);
        when(likeMapper.insertIgnore(7L, 1L)).thenReturn(true);

        likeService.like(1L, 7L);

        verify(likeMapper).insertIgnore(7L, 1L);
        verify(videoMapper).incrementLikesCount(7L);
        verify(videoMapper).addPopularity(7L, 1L);
        verify(videoCacheService, never()).updatePopularityCache(anyLong(), anyLong());
    }

    private void stubPrecheck() {
        when(likeMapper.countByVideoAndAccount(7L, 1L)).thenReturn(0L);
        when(videoMapper.selectById(7L)).thenReturn(new Video());
    }
}

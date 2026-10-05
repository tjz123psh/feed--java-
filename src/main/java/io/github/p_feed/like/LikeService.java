package io.github.p_feed.like;

import io.github.p_feed.account.dto.MessageResponse;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.like.dto.IsLikedResponse;
import io.github.p_feed.video.PopularityEventPublisher;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoCacheService;
import io.github.p_feed.video.VideoMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LikeService {

    private final LikeMapper likeMapper;
    private final VideoMapper videoMapper;
    private final VideoCacheService videoCacheService;
    private final LikeEventPublisher publisher;
    private final PopularityEventPublisher popularityEventPublisher;

    public LikeService(
            LikeMapper likeMapper,
            VideoCacheService videoCacheService,
            VideoMapper videoMapper,
            LikeEventPublisher publisher,
            PopularityEventPublisher popularityEventPublisher) {
        this.likeMapper = likeMapper;
        this.videoMapper = videoMapper;
        this.videoCacheService = videoCacheService;
        this.publisher = publisher;
        this.popularityEventPublisher = popularityEventPublisher;
    }

    // 点赞：预检 -> 事务 -> 热度缓存
    @Transactional
    public MessageResponse like(long accountId, Long videoId) {
        requireVideoId(videoId);
        requireVideoExists(videoId);

        if (likeMapper.countByVideoAndAccount(videoId, accountId) > 0) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, Errors.USER_HAS_LIKED_THIS_VIDEO);
        }

        boolean dbEnqueued = publisher.publishLike(accountId, videoId);
        boolean popEnqueued = popularityEventPublisher.publishPopularity(videoId, 1L);

        if (!dbEnqueued) {

            // 同步降级：投递成功让消费者落库，失败同步写

            // 同一事务：写行 + 两个计数。
            // insertIgnore 返回 false = 唯一索引拦下（并发下预检漏掉的重复请求），
            // 此时绝不能加计数——等价于原版"dup key → 事务回滚"。
            boolean inserted = likeMapper.insertIgnore(videoId, accountId);
            if (!inserted) {
                throw new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR, Errors.USER_HAS_LIKED_THIS_VIDEO);
            }
            videoMapper.incrementLikesCount(videoId);
            videoMapper.addPopularity(videoId, 1L);
        }
        // 热度缓存这条边独立判断：阶段 7 投递成功时由 PopularityConsumer 负责，避免双计
        if (!popEnqueued) {
            // 只有热度投递失败才补这一份,成功时由popularityConsumer负责,避免双计
            videoCacheService.updatePopularityCache(videoId, 1L);
        }

        return new MessageResponse("like success");
    }

    // 取消点赞 -> 事务 -> 热度缓存 - 1
    @Transactional
    public MessageResponse unlike(long accountId, Long videoId) {
        requireVideoId(videoId);
        requireVideoExists(videoId);
        if (likeMapper.countByVideoAndAccount(videoId, accountId) == 0) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, Errors.USER_HAS_NOT_LIKED_THIS_VIDEO);
        }

        boolean dbEnqueued = publisher.publishUnlike(accountId, videoId);
        boolean popEnqueued = popularityEventPublisher.publishPopularity(videoId, -1L);

        if (!dbEnqueued) {
            // DELETE 影响行数决定减不减
            int affected = likeMapper.deleteLike(videoId, accountId);
            if (affected == 0) {
                throw new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR, Errors.USER_HAS_NOT_LIKED_THIS_VIDEO);
            }
            videoMapper.decrementLikesCount(videoId);
            videoMapper.addPopularityClamped(videoId, -1L);
        }

        if (!popEnqueued) {
            videoCacheService.updatePopularityCache(videoId, -1L);
        }
        return new MessageResponse("unlike success");
    }

    // 是否赞过的业务：裸count ，不走缓存
    public IsLikedResponse isLiked(long accountId, Long videoId) {
        if (videoId == null || videoId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.VIDEO_ID_IS_REQUIRED);
        }

        boolean liked = likeMapper.countByVideoAndAccount(videoId, accountId) > 0;
        return new IsLikedResponse(liked);
    }

    // 我赞过的视频：JSON 按点赞时间倒序 limit 200
    // 无入参校验（accountId 来自登录态，不可能为 0）；空列表序列化就是 []。
    public List<Video> listMyLikedVideos(long accountId) {
        return likeMapper.listLikedVideos(accountId);
    }

    private void requireVideoExists(Long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.VIDEO_NOT_FOUND);
        }
    }

    private void requireVideoId(Long videoId) {
        if (videoId == null || videoId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.VIDEO_ID_IS_REQUIRED);
        }
    }
}

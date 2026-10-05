package io.github.p_feed.comment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.account.dto.MessageResponse;
import io.github.p_feed.comment.dto.DeleteCommentRequest;
import io.github.p_feed.comment.dto.PublishCommentRequest;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.notification.Notification;
import io.github.p_feed.notification.NotificationMapper;
import io.github.p_feed.video.PopularityEventPublisher;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoCacheService;
import io.github.p_feed.video.VideoMapper;

import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class CommentService {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(CommentService.class);

    private final NotificationMapper notificationMapper;
    private final CommentMapper commentMapper;
    private final VideoMapper videoMapper;
    private final AccountMapper accountMapper;
    private final VideoCacheService videoCacheService;
    private final CommentEventPublisher commentEventPublisher;
    private final PopularityEventPublisher popularityEventPublisher;

    public CommentService(
            CommentMapper commentMapper,
            VideoMapper videoMapper,
            AccountMapper accountMapper,
            VideoCacheService videoCacheService,
            NotificationMapper notificationMapper,
            CommentEventPublisher commentEventPublisher,
            PopularityEventPublisher popularityEventPublisher) {
        this.notificationMapper = notificationMapper;
        this.commentMapper = commentMapper;
        this.videoMapper = videoMapper;
        this.accountMapper = accountMapper;
        this.videoCacheService = videoCacheService;
        this.commentEventPublisher = commentEventPublisher;
        this.popularityEventPublisher = popularityEventPublisher;
    }

    // 评论列表：契约四条——video_id 必填（400）、无缓存直查、
    // 视频不存在 500（不是 404，见 README 差异说明）、最早在前 LIMIT 200。
    public List<Comment> listAll(Long videoId) {
        if (videoId == null || videoId == 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, io.github.p_feed.infra.Errors.VIDEO_ID_IS_REQUIRED);
        }

        // 视频必须存在：500（接口契约），然后不使用getDetaill
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    io.github.p_feed.infra.Errors.VIDEO_NOT_FOUND);
        }

        // 直查数据库
        return commentMapper.listByVideoId(videoId);
    }

    // 发评论：校验 400/500 -> 事务(insert comments + popularity + 1) -> 热度缓存
    @Transactional
    public MessageResponse publish(long accountId, PublishCommentRequest req) {
        if (req.videoId() == null || req.videoId() == 0L) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.VIDEO_ID_IS_REQUIRED);
        }

        String content = req.content() == null ? "" : req.content().trim();
        if (content.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.CONTENT_IS_REQUIRED);
        }

        // 视频必须存在，500不是400
        Video video = videoMapper.selectById(req.videoId());
        if (video == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.VIDEO_NOT_FOUND);
        }

        // 作者信息只取登录态
        Account author = accountMapper.selectById(accountId);
        if (author == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, Errors.ACCOUNT_NOT_FOUND);
        }

        // 同一事务：插评论 + (热度列 + 1)
        Comment comment = new Comment();
        comment.setUsername(author.getUsername());
        comment.setVideoId(req.videoId());
        comment.setAuthorId(accountId);
        comment.setContent(content);
        comment.setCreatedAt(OffsetDateTime.now());

        boolean dbEnqueued =
                commentEventPublisher.publishComment(
                        author.getUsername(), req.videoId(), accountId, content);
        boolean popEnqueued = popularityEventPublisher.publishPopularity(req.videoId(), 1L);

        // 下面都是异步投递不成功的情况下进行同步投递
        if (!dbEnqueued) {
            commentMapper.insert(comment);
            videoMapper.addPopularity(req.videoId(), 1L);
        }

        if (!popEnqueued) {
            // 缓存封装：DEL + 两键 + ZINCRBY 分钟窗口
            videoCacheService.updatePopularityCache(req.videoId(), 1L);
        }

        // 被提及就写通知
        notifyMentions(comment);

        return new MessageResponse("comment published successfully");
    }

    // 删除评论：400 -> 500不存在 -> 401非作者；不加减任何计数
    public MessageResponse delete(long accountId, DeleteCommentRequest req) {
        if (req.commentId() == null || req.commentId() == 0L) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.COMMENT_ID_IS_REQUIRED);
        }
        Comment comment = commentMapper.selectOneById(req.commentId());
        if (comment == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.COMMENT_NOT_FOUND);
        }
        if (comment.getAuthorId() == null || comment.getAuthorId() != accountId) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.UNAUTHORIZED);
        }

        // 先投递删除事件
        boolean enqueued = commentEventPublisher.deleteComment(req.commentId());
        if (!enqueued) {
            // 投递失败才同步删除库
            commentMapper.deleteOneById(req.commentId());
        }

        return new MessageResponse("comment deleted successfully");
    }

    // 通知： 解析-> 查账号-> 查找不到静默跳过 -> 直查notifications(失败只log，不影响发评论)
    private void notifyMentions(Comment comment) {
        List<String> mentions = MentionParser.extract(comment.getContent(), comment.getUsername());

        for (String username : mentions) {
            Account target =
                    accountMapper.selectOne(
                            new LambdaQueryWrapper<Account>().eq(Account::getUsername, username));
            if (target == null) {
                continue; // @unknown：静默跳过
            }
            Notification notification = new Notification();
            notification.setRecipientId(target.getId());
            notification.setSenderId(comment.getAuthorId());
            notification.setType("mention");
            notification.setTargetId(comment.getVideoId());
            notification.setContent(comment.getUsername() + " 在评论中提到了你");
            notification.setIsRead(false);
            notification.setCreatedAt(OffsetDateTime.now());
            try {
                notificationMapper.insert(notification);
            } catch (RuntimeException e) {
                // 通知是附属品：失败只记日志，绝不能让发评论回滚
                log.warn("create mention notification failed: {}", e.getMessage());
            }
        }
    }
}

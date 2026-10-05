package io.github.p_feed.comment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.p_feed.ApiApplication;
import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.account.dto.RegisterRequest;
import io.github.p_feed.account.AccountService;
import io.github.p_feed.comment.dto.PublishCommentRequest;
import io.github.p_feed.notification.Notification;
import io.github.p_feed.notification.NotificationMapper;
import io.github.p_feed.video.PopularityEventPublisher;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * @提及写通知：一条评论同时含 重复@ / @自己 / @不存在 / @中文，断言只产生应有的那一条通知。
 * 连真库（项目惯例），自建账号与评论，跑完清理。
 */
@SpringBootTest(classes = ApiApplication.class)
class MentionNotificationTest {

    @Autowired CommentService commentService;
    @Autowired AccountService accountService;
    @Autowired AccountMapper accountMapper;
    @Autowired VideoMapper videoMapper;
    @Autowired NotificationMapper notificationMapper;
    @Autowired CommentMapper commentMapper;

    // 阶段 7：发布器 mock 成"投递失败"→ 固定走同步路径：评论行与计数确定性落库，
    // 且不再真发 Kafka 消息（否则被 Worker 消费后，清理只删评论行、popularity 会慢慢漂）。
    @MockitoBean private CommentEventPublisher commentEventPublisher;
    @MockitoBean private PopularityEventPublisher popularityEventPublisher;

    @BeforeEach
    void forceSyncPath() {
        when(commentEventPublisher.publishComment(anyString(), anyLong(), anyLong(), anyString()))
                .thenReturn(false);
        when(popularityEventPublisher.publishPopularity(anyLong(), anyLong())).thenReturn(false);
    }

    @Test
    void mentionRules_produceExactlyOneNotification() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String nameA = "men_a_" + suffix;
        String nameB = "men_b_" + suffix;
        accountService.register(new RegisterRequest(nameA, "pw123456"));
        accountService.register(new RegisterRequest(nameB, "pw123456"));
        Account a = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getUsername, nameA));
        Account b = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getUsername, nameB));

        Video video = videoMapper.selectOne(new LambdaQueryWrapper<Video>().last("limit 1"));
        assertThat(video).as("库里应至少有一条视频作为评论目标").isNotNull();

        // A 发评论：重复 @B、@A 自己、@不存在、@中文（不匹配）
        String content = "@" + nameB + " @" + nameB + " @" + nameA + " @nobody_" + suffix + " @张三";
        commentService.publish(a.getId(), new PublishCommentRequest(video.getId(), content));

        List<Notification> rows = notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getRecipientId, b.getId())
                        .eq(Notification::getTargetId, video.getId()));
        assertThat(rows).hasSize(1);                       // 去重 + 跳过自己 + 跳过不存在 = 只 1 条
        Notification n = rows.get(0);
        assertThat(n.getType()).isEqualTo("mention");
        assertThat(n.getSenderId()).isEqualTo(a.getId());
        assertThat(n.getContent()).isEqualTo(nameA + " 在评论中提到了你");
        assertThat(n.getIsRead()).isFalse();

        // 清理本次产生的通知与评论
        notificationMapper.delete(new LambdaQueryWrapper<Notification>().eq(Notification::getSenderId, a.getId()));
        commentMapper.delete(new LambdaQueryWrapper<Comment>().eq(Comment::getAuthorId, a.getId()));
    }
}

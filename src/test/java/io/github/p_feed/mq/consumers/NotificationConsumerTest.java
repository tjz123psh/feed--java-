package io.github.p_feed.mq.consumers;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.config.AppProps;
import io.github.p_feed.mq.dlq.DlqProducer;
import io.github.p_feed.notification.Notification;
import io.github.p_feed.notification.NotificationMapper;
import io.github.p_feed.notification.SseHub;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 6 步 cg-notification 消费者契约（直接调 handle()，不起 Kafka 线程）：
 * 过滤 like/publish/follow；收件人=视频作者、操作者=作者跳过；视频缺失/作者 0 跳过；
 * follow 的 target_id 是粉丝自己；落库字段逐字；点赞通知实时推到作者的流上。
 */
@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
class NotificationConsumerTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired private AppProps props;
    @Autowired private DlqProducer dlq;
    @Autowired private VideoMapper videoMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private SseHub sseHub;
    @Autowired private AccountMapper accountMapper;
    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> videoIds = new ArrayList<>();
    private final List<Long> accountIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (!accountIds.isEmpty()) {
            notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                    .and(w -> w.in(Notification::getRecipientId, accountIds).or().in(Notification::getSenderId, accountIds)));
        }
        for (Long videoId : videoIds) {
            videoMapper.deleteById(videoId);
        }
    }

    @Test
    void like_insertsRow_andPushesToAuthorStream() throws Exception {
        Probe author = registerAndLogin();
        Probe liker = registerAndLogin();
        long videoId = insertVideo(author.id(), author.name());

        MvcResult stream = openStream(author.token());
        consumer(NotificationConsumer.Kind.LIKE).handle("k", """
                {"event_id":"nc-1","action":"like","user_id":%d,"video_id":%d,"occurred_at":"2026-10-07T00:00:00Z"}
                """.formatted(liker.id(), videoId));

        Notification row = lastNotification(author.id());
        assertThat(row).isNotNull();
        assertThat(row.getSenderId()).isEqualTo(liker.id());
        assertThat(row.getType()).isEqualTo("like");
        assertThat(row.getTargetId()).isEqualTo(videoId);
        assertThat(row.getContent()).isEqualTo("点赞了你的视频");
        assertThat(row.getIsRead()).isFalse();

        assertThat(pollFor(stream, "\"type\":\"like\"")).isTrue();
        assertThat(stream.getResponse().getContentAsString())
                .contains("\"recipient_id\":" + author.id());
    }

    @Test
    void unlike_isSkipped() throws Exception {
        Probe author = registerAndLogin();
        Probe actor = registerAndLogin();
        long videoId = insertVideo(author.id(), author.name());

        consumer(NotificationConsumer.Kind.LIKE).handle("k", """
                {"event_id":"nc-2","action":"unlike","user_id":%d,"video_id":%d,"occurred_at":"2026-10-07T00:00:00Z"}
                """.formatted(actor.id(), videoId));

        assertThat(notificationsFor(author.id())).isZero();
    }

    @Test
    void selfLike_isSkipped() throws Exception {
        Probe solo = registerAndLogin();
        long videoId = insertVideo(solo.id(), solo.name());

        consumer(NotificationConsumer.Kind.LIKE).handle("k", """
                {"event_id":"nc-3","action":"like","user_id":%d,"video_id":%d,"occurred_at":"2026-10-07T00:00:00Z"}
                """.formatted(solo.id(), videoId));

        assertThat(notificationsFor(solo.id())).isZero();
    }

    @Test
    void comment_publishNotifiesAuthor_deleteIsSkipped() throws Exception {
        Probe author = registerAndLogin();
        Probe commenter = registerAndLogin();
        long videoId = insertVideo(author.id(), author.name());

        consumer(NotificationConsumer.Kind.COMMENT).handle("k", """
                {"event_id":"nc-4","action":"publish","comment_id":1,"username":"x","video_id":%d,"author_id":%d,"content":"hi","occurred_at":"2026-10-07T00:00:00Z"}
                """.formatted(videoId, commenter.id()));

        Notification row = lastNotification(author.id());
        assertThat(row).isNotNull();
        assertThat(row.getType()).isEqualTo("comment");
        assertThat(row.getSenderId()).isEqualTo(commenter.id());
        assertThat(row.getTargetId()).isEqualTo(videoId);
        assertThat(row.getContent()).isEqualTo("评论了你的视频");

        consumer(NotificationConsumer.Kind.COMMENT).handle("k", """
                {"event_id":"nc-5","action":"delete","comment_id":1,"video_id":%d,"author_id":%d,"occurred_at":"2026-10-07T00:00:00Z"}
                """.formatted(videoId, commenter.id()));

        assertThat(notificationsFor(author.id())).isEqualTo(1);
    }

    @Test
    void follow_targetIdIsTheFollower() throws Exception {
        Probe vlogger = registerAndLogin();
        Probe follower = registerAndLogin();

        consumer(NotificationConsumer.Kind.FOLLOW).handle("k", """
                {"event_id":"nc-6","action":"follow","follower_id":%d,"vlogger_id":%d,"occurred_at":"2026-10-07T00:00:00Z"}
                """.formatted(follower.id(), vlogger.id()));

        Notification row = lastNotification(vlogger.id());
        assertThat(row).isNotNull();
        assertThat(row.getType()).isEqualTo("follow");
        assertThat(row.getSenderId()).isEqualTo(follower.id());
        assertThat(row.getTargetId()).isEqualTo(follower.id()); // 陷阱：target 是粉丝自己
        assertThat(row.getContent()).isEqualTo("关注了你");
    }

    @Test
    void missingVideo_orAuthorZero_isSkipped() throws Exception {
        Probe actor = registerAndLogin();

        consumer(NotificationConsumer.Kind.LIKE).handle("k", """
                {"event_id":"nc-7","action":"like","user_id":%d,"video_id":999999999,"occurred_at":"2026-10-07T00:00:00Z"}
                """.formatted(actor.id()));

        long orphanVideoId = insertVideo(0L, "ghost");
        consumer(NotificationConsumer.Kind.LIKE).handle("k", """
                {"event_id":"nc-8","action":"like","user_id":%d,"video_id":%d,"occurred_at":"2026-10-07T00:00:00Z"}
                """.formatted(actor.id(), orphanVideoId));

        assertThat(notificationsFor(actor.id())).isZero();
    }

    // ---- 工具 ----

    private record Probe(long id, String name, String token) {}

    private NotificationConsumer consumer(NotificationConsumer.Kind kind) {
        return new NotificationConsumer(kind, props, dlq, videoMapper, notificationMapper, sseHub);
    }

    private long insertVideo(long authorId, String username) {
        Video video = new Video();
        video.setAuthorId(authorId);
        video.setUsername(username);
        video.setTitle("probe");
        video.setPlayUrl("/static/videos/probe.mp4");
        video.setCoverUrl("/static/covers/probe.jpg");
        video.setCreateTime(OffsetDateTime.now());
        videoMapper.insert(video);
        videoIds.add(video.getId());
        return video.getId();
    }

    private long notificationsFor(long accountId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, accountId));
    }

    private Notification lastNotification(long recipientId) {
        return notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, recipientId)
                .orderByDesc(Notification::getId)
                .last("limit 1"));
    }

    private MvcResult openStream(String token) throws Exception {
        return mockMvc.perform(get("/notification/stream").param("token", token))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private boolean pollFor(MvcResult result, String needle) throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (result.getResponse().getContentAsString().contains(needle)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private Probe registerAndLogin() throws Exception {
        String name = "ct_nc_" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/account/register")
                        .contentType(JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pw123456\"}"))
                .andExpect(status().isOk());
        String body = mockMvc.perform(post("/account/login")
                        .contentType(JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pw123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asText();
        Account account = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getUsername, name));
        accountIds.add(account.getId());
        return new Probe(account.getId(), name, token);
    }
}

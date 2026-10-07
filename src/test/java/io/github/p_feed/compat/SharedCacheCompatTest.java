package io.github.p_feed.compat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.feed.FeedService;
import io.github.p_feed.feed.dto.ListByFollowingResponse;
import io.github.p_feed.infra.RedisKeys;
import io.github.p_feed.like.LikeMapper;
import io.github.p_feed.notification.Notification;
import io.github.p_feed.notification.NotificationMapper;
import io.github.p_feed.social.Social;
import io.github.p_feed.social.SocialMapper;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoCacheService;
import io.github.p_feed.video.VideoMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 8 步 · 与参考实现的互通验证（前提：Go 版跑在 8080、与本测试共享 MySQL/Redis；没跑则整类跳过）。
 * <p>两条缓存双向互读：① `v1:video:detail:id=*`；② `v1:feed:listByFollowing:*`（整页，含游标与 is_liked）。
 * 每条都用"标记值"钉住"确实命中了对方写的字节"——把缓存里的 title 换成 marker 再让对方读，
 * 若对方解码失败偷偷回源数据库，marker 不会出现在响应里，测试就会红。
 */
@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
class SharedCacheCompatTest {

    private static final String GO_DOWN = "Go 参考实现（127.0.0.1:8080）未运行——互通测试跳过";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoCacheService videoCacheService;
    @Autowired private FeedService feedService;
    @Autowired private VideoMapper videoMapper;
    @Autowired private AccountMapper accountMapper;
    @Autowired private SocialMapper socialMapper;
    @Autowired private LikeMapper likeMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisKeys keys;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private final List<Long> videoIds = new ArrayList<>();
    private final List<Long> accountIds = new ArrayList<>();
    private final List<long[]> likePairs = new ArrayList<>();

    // ---- ① detail 双向 ----

    @Test
    void detail_javaWrites_goReadsAndDecodes() throws Exception {
        Assumptions.assumeTrue(goUp(), GO_DOWN);
        Probe author = register();
        String title = unique("compat-detail");
        long videoId = insertVideo(author.id(), title);

        redis.delete(detailKey(videoId));
        Video fromJava = videoCacheService.getDetail(videoId);   // Java 写缓存（Jackson 3 序列化）
        String raw = redis.opsForValue().get(detailKey(videoId));
        assertThat(raw).as("Java 应已回填 detail 缓存").isNotNull();

        // 语义一致：未改动缓存时，Go 读出的内容与 Java 侧一致
        JsonNode first = goGetDetail(videoId);
        assertThat(first.get("id").asLong()).isEqualTo(videoId);
        assertThat(first.get("title").asText()).isEqualTo(title);
        assertThat(OffsetDateTime.parse(first.get("create_time").asText()).toInstant())
                .isEqualTo(fromJava.getCreateTime().toInstant());
        assertThat(first.get("likes_count").asLong()).isEqualTo(fromJava.getLikesCount());

        // 决定性读取证明：缓存里的 title 换成 marker → Go 的响应必须跟着变
        String marker = unique("marker-java-wrote");
        redis.opsForValue().set(detailKey(videoId), raw.replace(title, marker));
        JsonNode marked = goGetDetail(videoId);
        assertThat(marked.get("title").asText())
                .as("Go 必须原样读出 Java 写的缓存（marker 生效才算真命中）")
                .isEqualTo(marker);
    }

    @Test
    void detail_goWrites_javaReadsAndDecodes() throws Exception {
        Assumptions.assumeTrue(goUp(), GO_DOWN);
        Probe author = register();
        String title = unique("compat-detail");
        long videoId = insertVideo(author.id(), title);

        redis.delete(detailKey(videoId));
        HttpResponse<String> go = goPost("/video/getDetail", null, "{\"id\":" + videoId + "}");
        assertThat(go.statusCode()).isEqualTo(200);
        String raw = redis.opsForValue().get(detailKey(videoId));
        assertThat(raw).as("Go 应已回填 detail 缓存").isNotNull();

        // 语义一致：Java 的读路径（Jackson 3）解 Go 的字节，与 DB 真值逐字段相等
        Video fromJava = videoCacheService.getDetail(videoId);
        Video fromDb = videoMapper.selectById(videoId);
        assertThat(fromJava.getId()).isEqualTo(fromDb.getId());
        assertThat(fromJava.getAuthorId()).isEqualTo(fromDb.getAuthorId());
        assertThat(fromJava.getTitle()).isEqualTo(fromDb.getTitle());
        assertThat(fromJava.getPlayUrl()).isEqualTo(fromDb.getPlayUrl());
        assertThat(fromJava.getCoverUrl()).isEqualTo(fromDb.getCoverUrl());
        assertThat(fromJava.getLikesCount()).isEqualTo(fromDb.getLikesCount());
        assertThat(fromJava.getPopularity()).isEqualTo(fromDb.getPopularity());
        assertThat(fromJava.getCreateTime().toInstant()).isEqualTo(fromDb.getCreateTime().toInstant());

        // 决定性读取证明
        String marker = unique("marker-go-wrote");
        redis.opsForValue().set(detailKey(videoId), raw.replace(title, marker));
        Video marked = videoCacheService.getDetail(videoId);
        assertThat(marked.getTitle())
                .as("Java 必须原样读出 Go 写的缓存（marker 生效才算真命中）")
                .isEqualTo(marker);
    }

    // ---- ② listByFollowing 整页双向 ----

    @Test
    void followingPage_javaWrites_goReadsAndDecodes() throws Exception {
        Assumptions.assumeTrue(goUp(), GO_DOWN);
        Fixture fx = followingFixture();

        redis.delete(pageKey(fx.viewer().id()));
        ListByFollowingResponse fromJava = feedService.listByFollowing(10, null, fx.viewer().id());
        assertThat(fromJava.videoList()).isNotEmpty();
        assertThat(fromJava.videoList().get(0).id()).isEqualTo(fx.videoId());
        assertThat(fromJava.videoList().get(0).isLiked()).isTrue(); // B 已赞过

        String raw = redis.opsForValue().get(pageKey(fx.viewer().id()));
        assertThat(raw).as("Java 应已回填整页缓存").isNotNull();

        String goToken = goLogin(fx.viewer().name());
        JsonNode page = goFollowing(goToken);
        assertThat(page.size()).isEqualTo(fromJava.videoList().size());
        assertThat(page.get(0).get("id").asLong()).isEqualTo(fx.videoId());
        assertThat(page.get(0).get("title").asText()).isEqualTo(fx.title());
        assertThat(page.get(0).get("is_liked").asBoolean()).isTrue();

        String marker = unique("marker-java-wrote");
        redis.opsForValue().set(pageKey(fx.viewer().id()), raw.replace(fx.title(), marker));
        JsonNode marked = goFollowing(goToken);
        assertThat(marked.get(0).get("title").asText())
                .as("Go 必须原样读出 Java 写的整页缓存（marker 生效才算真命中）")
                .isEqualTo(marker);
    }

    @Test
    void followingPage_goWrites_javaReadsAndDecodes() throws Exception {
        Assumptions.assumeTrue(goUp(), GO_DOWN);
        Fixture fx = followingFixture();

        redis.delete(pageKey(fx.viewer().id()));
        String goToken = goLogin(fx.viewer().name());
        JsonNode page = goFollowing(goToken);
        assertThat(page.get(0).get("id").asLong()).isEqualTo(fx.videoId());
        String raw = redis.opsForValue().get(pageKey(fx.viewer().id()));
        assertThat(raw).as("Go 应已回填整页缓存").isNotNull();

        // 语义一致：Java 读出 Go 写的整页，游标与 is_liked 都对
        ListByFollowingResponse fromJava = feedService.listByFollowing(10, null, fx.viewer().id());
        assertThat(fromJava.videoList()).isNotEmpty();
        assertThat(fromJava.videoList().get(0).id()).isEqualTo(fx.videoId());
        assertThat(fromJava.videoList().get(0).isLiked()).isTrue();
        assertThat(fromJava.nextTime()).isGreaterThan(0L);

        // 决定性读取证明
        String marker = unique("marker-go-wrote");
        redis.opsForValue().set(pageKey(fx.viewer().id()), raw.replace(fx.title(), marker));
        ListByFollowingResponse marked = feedService.listByFollowing(10, null, fx.viewer().id());
        assertThat(marked.videoList().get(0).title())
                .as("Java 必须原样读出 Go 写的整页缓存（marker 生效才算真命中）")
                .isEqualTo(marker);
    }

    // ---- 夹具与工具 ----

    private record Probe(long id, String name) {}

    private record Fixture(Probe viewer, Probe author, long videoId, String title) {}

    /** viewer 关注 author、author 有唯一标题的视频、viewer 已赞该视频。 */
    private Fixture followingFixture() throws Exception {
        Probe viewer = register();
        Probe author = register();
        String title = unique("compat-following");
        long videoId = insertVideo(author.id(), title);
        socialMapper.insert(newSocial(viewer.id(), author.id()));
        likeMapper.insertIgnore(videoId, viewer.id());
        likePairs.add(new long[]{videoId, viewer.id()});
        return new Fixture(viewer, author, videoId, title);
    }

    private Social newSocial(long followerId, long vloggerId) {
        Social social = new Social();
        social.setFollowerId(followerId);
        social.setVloggerId(vloggerId);
        return social;
    }

    private long insertVideo(long authorId, String title) {
        Video video = new Video();
        video.setAuthorId(authorId);
        video.setUsername("compat-author");
        video.setTitle(title);
        video.setPlayUrl("/static/videos/compat.mp4");
        video.setCoverUrl("/static/covers/compat.jpg");
        video.setCreateTime(OffsetDateTime.now());
        videoMapper.insert(video);
        videoIds.add(video.getId());
        return video.getId();
    }

    private Probe register() throws Exception {
        String name = "ct_compat_" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/account/register")
                        .contentType(JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pw123456\"}"))
                .andExpect(status().isOk());
        Account account = accountMapper.selectOne(
                new LambdaQueryWrapper<Account>().eq(Account::getUsername, name));
        accountIds.add(account.getId());
        return new Probe(account.getId(), name);
    }

    private JsonNode goGetDetail(long videoId) throws Exception {
        HttpResponse<String> response = goPost("/video/getDetail", null, "{\"id\":" + videoId + "}");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private JsonNode goFollowing(String goToken) throws Exception {
        HttpResponse<String> response =
                goPost("/feed/listByFollowing", goToken, "{\"limit\":10,\"latest_time\":0}");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).get("video_list");
    }

    private String goLogin(String username) throws Exception {
        HttpResponse<String> response = goPost(
                "/account/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"pw123456\"}");
        assertThat(response.statusCode())
                .as("Go 版应能用 Java 侧注册的账号登录（共享库 + 同款哈希）")
                .isEqualTo(200);
        return objectMapper.readTree(response.body()).get("token").asText();
    }

    private HttpResponse<String> goPost(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private boolean goUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 8080), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String detailKey(long videoId) {
        return keys.key("video:detail:id=%d", videoId);
    }

    private String pageKey(long accountId) {
        return keys.key("feed:listByFollowing:limit=%d:accountID=%d:before=%d", 10, accountId, 0L);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanup() {
        for (Long videoId : videoIds) {
            redis.delete(detailKey(videoId));
            videoMapper.deleteById(videoId);
        }
        for (long[] pair : likePairs) {
            likeMapper.deleteLike(pair[0], pair[1]);
        }
        for (Long accountId : accountIds) {
            redis.delete(pageKey(accountId));
        }
        if (!accountIds.isEmpty()) {
            notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                    .and(w -> w.in(Notification::getRecipientId, accountIds)
                            .or().in(Notification::getSenderId, accountIds)));
            socialMapper.delete(new LambdaQueryWrapper<Social>()
                    .and(w -> w.in(Social::getFollowerId, accountIds)
                            .or().in(Social::getVloggerId, accountIds)));
        }
        videoIds.clear();
        accountIds.clear();
        likePairs.clear();
    }
}

package io.github.p_feed.feed;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.p_feed.ApiApplication;
import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.account.AccountService;
import io.github.p_feed.account.dto.RegisterRequest;
import io.github.p_feed.feed.dto.FeedAuthor;
import io.github.p_feed.feed.dto.FeedVideoItem;
import io.github.p_feed.like.LikeMapper;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ApiApplication.class)
class FeedServiceTest {

    @Autowired private FeedService feedService;
    @Autowired private AccountService accountService;
    @Autowired private AccountMapper accountMapper;
    @Autowired private VideoMapper videoMapper;
    @Autowired private LikeMapper likeMapper;

    @Test
    void buildFeedVideos_loggedInVsAnonymous() {
        String name = "feed_" + UUID.randomUUID().toString().substring(0, 8);
        accountService.register(new RegisterRequest(name, "pw123456"));
        Account acc = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getUsername, name));

        List<Video> videos = videoMapper.selectList(
                new LambdaQueryWrapper<Video>().orderByAsc(Video::getId).last("limit 2"));
        assertThat(videos).as("库里至少要有两条视频").hasSize(2);
        Video first = videos.get(0);

        likeMapper.insertIgnore(first.getId(), acc.getId());   // 只赞第一条；直插 mapper 不影响计数
        try {
            // 登录态：第一条 true、其余 false；create_time 是秒；author 来自 videos 冗余列
            List<FeedVideoItem> loggedIn = feedService.buildFeedVideos(videos, acc.getId());
            assertThat(loggedIn).hasSize(2);
            assertThat(loggedIn.get(0).isLiked()).isTrue();
            assertThat(loggedIn.get(1).isLiked()).isFalse();
            assertThat(loggedIn.get(0).createTime()).isEqualTo(first.getCreateTime().toEpochSecond());
            assertThat(loggedIn.get(0).author().id()).isEqualTo(first.getAuthorId());
            assertThat(loggedIn.get(0).author().username()).isEqualTo(first.getUsername());

            // 匿名：全部 false（viewer=0 不查库）
            List<FeedVideoItem> anonymous = feedService.buildFeedVideos(videos, 0L);
            assertThat(anonymous).hasSize(2);
            assertThat(anonymous).allMatch(item -> !item.isLiked());
        } finally {
            likeMapper.deleteLike(first.getId(), acc.getId());   // 清理（同样不经 service，不动计数）
        }
    }

    @Test
    void feedVideoItem_jsonShape() throws Exception {
        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(
                new FeedVideoItem(1L, new FeedAuthor(2L, "u"), "t", "d", "p", "c", 1756814652L, 3L, true));
        assertThat(json)
                .contains("\"is_liked\":true")          // 防 Jackson 剥 is 前缀
                .contains("\"play_url\"")
                .contains("\"description\":\"d\"")      // 防 description 拼错（曾写成 desription）
                .contains("\"create_time\":1756814652")
                .contains("\"likes_count\":3")
                .contains("\"author\"");

        // description 空则键不出现
        String noDesc = new tools.jackson.databind.ObjectMapper().writeValueAsString(
                new FeedVideoItem(1L, new FeedAuthor(2L, "u"), "t", null, "p", "c", 1756814652L, 3L, false));
        assertThat(noDesc).doesNotContain("\"description\"");
    }

    @Test
    void feedListResponse_jsonKeyIsVideoList() throws Exception {
        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(
                new io.github.p_feed.feed.dto.FeedListResponse(List.of()));
        // 键名必须是 video_list（曾错拼成 vidoe_list，且空列表也要出现该键）
        assertThat(json).isEqualTo("{\"video_list\":[]}");
    }
}


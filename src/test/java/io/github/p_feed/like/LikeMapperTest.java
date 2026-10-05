package io.github.p_feed.like;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.p_feed.ApiApplication;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段5 第2步：Like 实体 + LikeMapper。
 * 覆盖第5步要依赖的两条语义：insertIgnore 的幂等（唯一索引兜底）、deleteLike 的影响行数
 * （0 行时调用方不能减计数，否则 likes_count 会漂）。
 */
@SpringBootTest(classes = ApiApplication.class)
class LikeMapperTest {

    @Autowired
    private LikeMapper likeMapper;

    @Autowired
    private VideoMapper videoMapper;

    /** 用远离真实数据的 id，避免污染已有视频与账号 */
    private static final long VIDEO_ID = 999_201L;
    private static final long ACCOUNT_ID = 999_202L;

    @Test
    void insertIgnore_isIdempotent() {
        // 第一次插入成功
        assertThat(likeMapper.insertIgnore(VIDEO_ID, ACCOUNT_ID)).isTrue();
        // 第二次被 (video_id, account_id) 唯一索引忽略 —— 第5步并发点赞的最终防线
        assertThat(likeMapper.insertIgnore(VIDEO_ID, ACCOUNT_ID)).isFalse();

        assertThat(likeMapper.countByVideoAndAccount(VIDEO_ID, ACCOUNT_ID)).isEqualTo(1);

        likeMapper.deleteLike(VIDEO_ID, ACCOUNT_ID);
    }

    @Test
    void deleteLike_returnsAffectedRows() {
        likeMapper.insertIgnore(VIDEO_ID, ACCOUNT_ID);

        assertThat(likeMapper.deleteLike(VIDEO_ID, ACCOUNT_ID)).isEqualTo(1);
        // 再删没有行 —— 调用方据此跳过"计数 -1"
        assertThat(likeMapper.deleteLike(VIDEO_ID, ACCOUNT_ID)).isEqualTo(0);
    }

    @Test
    void countByVideoAndAccount_onlyCountsOwnLike() {
        long other = 999_203L;
        likeMapper.insertIgnore(VIDEO_ID, ACCOUNT_ID);

        assertThat(likeMapper.countByVideoAndAccount(VIDEO_ID, ACCOUNT_ID)).isEqualTo(1);
        // 换个账号，不该被算进来（曾因 SQL 误用 video_id 比对 account_id 而判错）
        assertThat(likeMapper.countByVideoAndAccount(VIDEO_ID, other)).isEqualTo(0);

        likeMapper.deleteLike(VIDEO_ID, ACCOUNT_ID);
    }

    @Test
    void listLikedVideos_joinsVideoAndReturnsPlayUrl() {
        // join 要求 videos 表里有对应行：直接取库里的任一视频，不依赖硬编码作者 id
        Video any = videoMapper.selectOne(new LambdaQueryWrapper<Video>().last("limit 1"));
        assertThat(any)
                .as("库里至少要有 1 条视频（阶段4 publish 产生），否则本用例无法验证 join")
                .isNotNull();

        long accountId = 999_204L;
        likeMapper.insertIgnore(any.getId(), accountId);
        var videos = likeMapper.listLikedVideos(accountId);

        assertThat(videos).hasSize(1);
        assertThat(videos.get(0).getId()).isEqualTo(any.getId());
        assertThat(videos.get(0).getPlayUrl()).isNotBlank();   // 契约：元素是完整 Video，前端拿到能播

        likeMapper.deleteLike(any.getId(), accountId);
    }
}

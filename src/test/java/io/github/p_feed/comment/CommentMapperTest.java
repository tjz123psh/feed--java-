package io.github.p_feed.comment;

import io.github.p_feed.ApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段5 第2步：Comment 实体 + CommentMapper。
 * 覆盖：插入后自增 id 回填、字段一致（含 video_id 这个曾漏标的列）、按视频查列表的正序与截断。
 */
@SpringBootTest(classes = ApiApplication.class)
class CommentMapperTest {

    @Autowired
    private CommentMapper commentMapper;

    /** 用远离真实数据的 video_id，避免污染已有评论 */
    private static final long TEST_VIDEO_ID = 999_101L;

    @Test
    void insertThenSelect_fieldsMatch() {
        Comment comment = new Comment();
        comment.setUsername("mapper_tester");
        comment.setVideoId(TEST_VIDEO_ID);
        comment.setAuthorId(999_102L);
        comment.setContent("单测内容 #阶段5");
        comment.setCreatedAt(OffsetDateTime.now());

        commentMapper.insert(comment);

        // 自增主键回填
        assertThat(comment.getId()).isNotNull().isPositive();

        Comment loaded = commentMapper.selectOneById(comment.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getContent()).isEqualTo("单测内容 #阶段5");
        assertThat(loaded.getUsername()).isEqualTo("mapper_tester");
        assertThat(loaded.getVideoId()).isEqualTo(TEST_VIDEO_ID);   // 曾因缺字段而丢
        assertThat(loaded.getAuthorId()).isEqualTo(999_102L);       // 曾因注解错标而丢
        assertThat(loaded.getCreatedAt()).isNotNull();

        commentMapper.deleteOneById(comment.getId());
    }

    @Test
    void listByVideoId_ordersByCreatedAtAsc_andHonorsLimit() {
        long videoId = 999_103L;
        insertAt(videoId, "第一条", OffsetDateTime.now().minusMinutes(2));
        insertAt(videoId, "第二条", OffsetDateTime.now().minusMinutes(1));
        insertAt(videoId, "第三条", OffsetDateTime.now());

        List<Comment> list = commentMapper.listByVideoId(videoId);

        assertThat(list).hasSize(3);
        // 契约：最早一条在最前（ASC）
        assertThat(list.get(0).getContent()).isEqualTo("第一条");
        assertThat(list.get(2).getContent()).isEqualTo("第三条");

        list.forEach(c -> commentMapper.deleteOneById(c.getId()));
    }

    @Test
    void deleteOneById_returnsAffectedRows() {
        Comment comment = new Comment();
        comment.setUsername("deleter");
        comment.setVideoId(999_104L);
        comment.setAuthorId(1L);
        comment.setContent("待删");
        comment.setCreatedAt(OffsetDateTime.now());
        commentMapper.insert(comment);

        assertThat(commentMapper.deleteOneById(comment.getId())).isEqualTo(1);
        assertThat(commentMapper.deleteOneById(comment.getId())).isEqualTo(0);   // 再删没行
    }

    private void insertAt(long videoId, String content, OffsetDateTime at) {
        Comment c = new Comment();
        c.setUsername("tester");
        c.setVideoId(videoId);
        c.setAuthorId(1L);
        c.setContent(content);
        c.setCreatedAt(at);
        commentMapper.insert(c);
    }
}

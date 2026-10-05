package io.github.p_feed.video;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TagExtractorTest {

    @Test
    void extractsTagsAndDeduplicates() {
        assertThat(TagExtractor.extract("测试 #java #中文标签", "补充 #java"))
                .isEqualTo(List.of("java", "中文标签"));
    }

    @Test
    void unicodeDigitsAreValidTags() {
        assertThat(TagExtractor.extract("编号 #１２３", null))
                .isEqualTo(List.of("１２３"));
    }

    @Test
    void noTags_returnsEmptyList() {
        assertThat(TagExtractor.extract("没话题", "也没有")).isEmpty();
    }
}

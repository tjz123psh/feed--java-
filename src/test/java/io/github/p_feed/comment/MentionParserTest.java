package io.github.p_feed.comment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MentionParserTest {

    @Test
    void deduplicatesRepeatedMentions() {
        assertThat(MentionParser.extract("@alice 你好 @alice @alice", "me"))
                .isEqualTo(List.of("alice"));
    }

    @Test
    void skipsSelf() {
        assertThat(MentionParser.extract("@me @alice", "me"))
                .isEqualTo(List.of("alice"));
    }

    @Test
    void chineseMentionsDoNotMatch() {
        assertThat(MentionParser.extract("@张三 你好 @李四", "me")).isEmpty();
    }

    @Test
    void keepsOrderAndAllowsUnderscoreDigits() {
        assertThat(MentionParser.extract("@b @a_1 @c9", "me"))
                .isEqualTo(List.of("b", "a_1", "c9"));
    }
}

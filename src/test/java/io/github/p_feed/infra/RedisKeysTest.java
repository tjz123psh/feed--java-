package io.github.p_feed.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.p_feed.config.AppProps;

class RedisKeysTest {

    @Test
    void prefixApplied() {
        assertThat(new RedisKeys(new AppProps(new AppProps.Redis("v1:"), new AppProps.Jwt("x"), null, null, null))
                .key("video:detail:id=%d", 7)).isEqualTo("v1:video:detail:id=7");
    }
}

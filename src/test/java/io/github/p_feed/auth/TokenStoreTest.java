package io.github.p_feed.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.config.AppProps;
import io.github.p_feed.infra.RedisKeys;

class TokenStoreTest {

    private final RedisKeys keys = new RedisKeys(
            new AppProps(new AppProps.Redis("v1:"), new AppProps.Jwt("test-secret-key"), null, null, null));

    @Test
    void cacheMiss_fallsBackToDb_andBackfillsCache() {
        // Redis 键不存在（相当于"删掉后"）
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(keys.key("account:%d", 1L))).thenReturn(null);

        // 库里躺着 token
        AccountMapper mapper = mock(AccountMapper.class);
        Account dbAccount = new Account();
        dbAccount.setId(1L);
        dbAccount.setToken("db-token");
        when(mapper.selectById(1L)).thenReturn(dbAccount);

        TokenStore store = new TokenStore(redis, keys, mapper);
        String token = store.current(1L);

        assertThat(token).isEqualTo("db-token");                       // 从库取到
        verify(ops).set(keys.key("account:%d", 1L), "db-token", Duration.ofHours(24)); // 并回填
    }

    @Test
    void cacheHit_returnsCached_withoutDb() {
        // Redis 命中，不该去查库
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(keys.key("account:%d", 2L))).thenReturn("cached-token");

        AccountMapper mapper = mock(AccountMapper.class);
        TokenStore store = new TokenStore(redis, keys, mapper);

        assertThat(store.current(2L)).isEqualTo("cached-token");
        verify(mapper, never()).selectById(anyLong());
    }
}

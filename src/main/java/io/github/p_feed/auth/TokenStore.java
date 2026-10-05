package io.github.p_feed.auth;

import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.infra.RedisKeys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;

@Component
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(24);

    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);

    private static final Duration REFRESH_INDEX_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    private final RedisKeys keys;

    private final AccountMapper accountMapper;

    public TokenStore(StringRedisTemplate redis, RedisKeys keys, AccountMapper accountMapper) {
        this.redis = redis;
        this.keys = keys;
        this.accountMapper = accountMapper;
    }

    // 这里是登录/刷新的保存
    public void save(long accountId, String accessToken, String refreshToken) {
        accountMapper.updateTokens(accountId, accessToken, refreshToken);

        try {
            redis.opsForValue()
                    .set(keys.key("account:%d", accountId), accessToken, ACCESS_TOKEN_TTL);
            redis.opsForValue()
                    .set(
                            keys.key("account:%d:refresh", accountId),
                            refreshToken,
                            REFRESH_TOKEN_TTL);
            redis.opsForValue()
                    .set(
                            keys.key("refresh:%s", refreshToken),
                            String.valueOf(accountId),
                            REFRESH_INDEX_TTL);

        } catch (RuntimeException e) {
            // TODO: handle exception
            log.warn("token cache write failed: {}", e.getMessage());
        }
    }

    // 查询token
    public String current(long accountId) {

        // 先查缓存
        try {
            String token = redis.opsForValue().get(keys.key("account:%d", accountId));
            if (token != null && !token.isEmpty()) {
                return token;
            }
        } catch (RuntimeException e) {
            // TODO: handle exception
            log.warn("token cache read failed: {}", e.getMessage());
        }

        Account account = accountMapper.selectById(accountId);
        String dbToken = (account == null) ? null : account.getToken();

        if (dbToken != null && !dbToken.isEmpty()) {
            // 回写缓存
            backfillAccess(accountId, dbToken);
        }

        return dbToken;
    }

    // 登出/改密-> 将所有token删除
    public void revoke(long accountId) {
        String oldRefresh = readRefreshToken(accountId);
        accountMapper.clearTokens(accountId);

        try {
            redis.delete(
                    Arrays.asList(
                            keys.key("account:%d", accountId),
                            keys.key("account:%d:refresh", accountId)));

            if (oldRefresh != null && !oldRefresh.isEmpty()) {
                redis.delete(keys.key("refresh:%s", oldRefresh));
            }
        } catch (RuntimeException e) {
            log.warn("token cache clear failed: {}", e.getMessage());
        }
    }
    
    // 反查：v1:refresh:{token} 优先，miss 查库 refresh_token 列，命中回填索引
    public Long findAccountIdByRefresh(String refreshToken) {
        try {
            String cached = redis.opsForValue().get(keys.key("refresh:%s", refreshToken));
            if (cached != null && !cached.isEmpty()) {
                return Long.valueOf(cached);
            }
        } catch (RuntimeException e) {
            log.warn("refresh index cache read failed: {}", e.getMessage());
        }
        Account account = accountMapper.selectByRefreshToken(refreshToken); // 建列查询，非全表扫
        if (account != null) {
            backfillRefreshIndex(refreshToken, account.getId());
        }
        return account == null ? null : account.getId();
    }

    // 查旧值
    private String readRefreshToken(long accountId) {
        try {
            String cache = redis.opsForValue().get(keys.key("account:%d:refresh", accountId));
            if (cache != null && !cache.isEmpty()) {
                return cache;
            }
        } catch (RuntimeException e) {
            log.warn("refresh token cache read failed: {}", e.getMessage());
        }

        Account account = accountMapper.selectById(accountId);
        return account == null ? null : account.getRefreshToken();
    }

    private void backfillRefreshIndex(String refreshToken, Long id) {
        try {
            redis.opsForValue()
                    .set(
                            keys.key("refresh:%s", refreshToken),
                            String.valueOf(id),
                            REFRESH_INDEX_TTL);
        } catch (RuntimeException e) {
            log.warn("refresh index backfill failed: {}", e.getMessage());
        }
    }

    private void backfillAccess(long accountId, String dbToken) {
        try {
            redis.opsForValue().set(keys.key("account:%d", accountId), dbToken, ACCESS_TOKEN_TTL);
        } catch (RuntimeException e) {
            // TODO: handle exception
            log.warn("token cache backfill failed: {}", e.getMessage());
        }
    }
}

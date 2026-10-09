package io.github.p_feed.video;

import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.infra.RedisKeys;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

/**
 * 分块会话的唯一 Redis 出入口（第 7/8/9 步）。 两个键：会话键存 JSON、hash 键存裸 upload_id；TTL 都是 24h。 错误映射： -
 * 连接类异常（Redis 真挂了） → 503 chunk upload requires redis - 键查不到 → 返回 null，由 Service 给 404 upload
 * session not found - 会话 JSON 坏了 → 404 invalid session data - 写会话失败（非连接类） → 500 failed to
 * create/update session
 */
@Component
public class ChunkSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ChunkSessionStore.class);
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChunkSessionStore(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    // 新建会话：两个键都写，ttl = 24h
    public void create(ChunkSession session) {
        try {
            redis.opsForValue()
                    .set(
                            sessionKey(session.uploadId()),
                            objectMapper.writeValueAsString(session),
                            SESSION_TTL);
            redis.opsForValue()
                    .set(
                            hashKey(session.accountId(), session.fileHash()),
                            session.uploadId(),
                            SESSION_TTL);
        } catch (DataAccessException e) {
            throw redisDown(); // redis不可用，503
        } catch (Exception e) {

            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, Errors.FAILED_TO_CREATE_SESSION); // 其他情况500
        }
    }

    // 读会话；查无返回null(404文案由service提供)； json坏了 -> 404 = invalid session data
    public ChunkSession load(String uploadId) {
        try {
            String json = redis.opsForValue().get(sessionKey(uploadId));
            if (json == null) {
                return null;
            }

            return objectMapper.readValue(json, ChunkSession.class);
        } catch (DataAccessException e) {
            throw redisDown();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.NOT_FOUND, Errors.INVALID_SESSION_DATA);
        }
    }

    // 按hash键反查upload_id; 未命中返回null;未命中返回null
    public String findByHash(long accountId, String fileHash) {
        try {
            return redis.opsForValue().get(hashKey(accountId, fileHash));
        } catch (DataAccessException e) {
            throw redisDown();
        } catch (RuntimeException e) {
            log.warn("chunk hash lookup failed, treat as miss: {}",e.getMessage());
            return null;
        }
    }

    // 回写会话（上传位图后）：每次回写的时候都将ttl重置为24h，这里保持一致
    // 这个方法的作用是将上传的位图进行更新。
    public void update(ChunkSession session) {
        try {
            redis.opsForValue()
                    .set(
                            sessionKey(session.uploadId()),
                            objectMapper.writeValueAsString(session),
                            SESSION_TTL);
        } catch (DataAccessException e) {
            throw redisDown();
        }catch(Exception e){
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Errors.FAILED_TO_UPDATE_SESSION);
        }
    }

    // complete后清理两个键，并记录日志
    public void deleteSession(String uploadId, long accountId , String fileHash){
        try {
            redis.delete(List.of(sessionKey(uploadId),hashKey(accountId, fileHash)));
        } catch (Exception e) {
            log.warn("chunk session cleanup failed:{}", e.getMessage());
        }
    }

    private String sessionKey(String uploadId) {
        return keys.key("chunk_upload:%s", uploadId);
    }

    private ApiException redisDown() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                io.github.p_feed.infra.Errors.CHUNK_UPLOAD_REQUIRES_REDIS);
    }

    private @NonNull String hashKey(long accountId, String fileHash) {
        return keys.key("chunk_upload_hash:%d:%s", accountId, fileHash);
    }
}

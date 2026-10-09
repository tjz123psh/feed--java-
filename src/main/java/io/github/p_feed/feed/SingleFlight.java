package io.github.p_feed.feed;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
/**
 * 进程内请求合并（第 9 步）：同一个 key 上的并发调用只执行一次 loader，其余等它的结果。
 * key 只存在于这张 map 里，永远不写 Redis。
 * 必须是单例 bean —— 每次 new 一个就等于没有合并。
 */
@Component
public class SingleFlight{

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <V> V run(String key, Supplier<V> loader){
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = inflight.putIfAbsent(key, mine);

        if(existing != null){
            try {
                return (V) existing.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();;
                throw new IllegalStateException("interrupted while waiting in-flight: " + key, e);
            }catch(ExecutionException e){
                Throwable cause = e.getCause();
                // 领头的失败,等待的也同样失败
                if(cause instanceof RuntimeException runtime){
                    throw runtime;
                }
                throw new IllegalStateException("in-flight loader failed: " + key , cause);
            }
        }

        try {
            V value = loader.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        }finally{
            inflight.remove(key,mine);// 两参数：只删自己注册的条目
        }
    }
    























































    
}

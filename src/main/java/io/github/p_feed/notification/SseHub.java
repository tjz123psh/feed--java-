package io.github.p_feed.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * SSE 通知 Hub： 一个用户多条连接；每连接一个容量 20 的有界缓冲，offer 满即丢、绝不阻塞； 断线即摘除、不留死连接；无重连游标、无 ACK（断线期间不补发，靠下次 /list
 * 兜底）； 空闲 30 秒发一行注释 `: keepalive` 防代理掐闲置连接。
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    private static final int BUFFER_CAPACITY = 20;
    private static final long KEEPALIVE_SECONDS = 30;

    private final Map<Long, List<Client>> clients = new HashMap<>(); // 读多写少, 配读写锁
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // 写成小类而不是 record：写线程要能"引用自己"来摘除，record 在构造期拿不到这个环
    private static final class Client {
        final SseEmitter emitter;
        final PendingBuffer pending = new PendingBuffer();
        volatile Thread writer;

        Client(SseEmitter emitter) {
            this.emitter = emitter;
        }
    }

    /** 容量 20 的有界缓冲：offer 非阻塞、满了返回 false（丢）；poll 至多等 30 秒，空 = 该发 keepalive。 */
    private static final class PendingBuffer {
        private final LinkedBlockingQueue<Notification> queue =
                new LinkedBlockingQueue<>(BUFFER_CAPACITY);

        boolean offer(Notification n) {
            return queue.offer(n);
        }

        Notification poll(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }
    }

    // 订阅：0 = 永不空闲超时（默认构造是容器默认约 30 秒，到点断流）
    public SseEmitter subscribe(long userId) {
        SseEmitter emitter = new SseEmitter(0L);
        Client client = new Client(emitter);

        lock.writeLock().lock();
        try {
            List<Client> list = clients.computeIfAbsent(userId, k -> new ArrayList<>());
            list.add(client);
            log.info("sse subscribe user={} clients={}", userId, list.size());
        } finally {
            lock.writeLock().unlock();
        }

        // 这里的start开始后就是由虚拟机线程来进行工作,主线程是往下面走了
        client.writer =
                Thread.ofVirtual()
                        .name("sse-writer-" + userId)
                        .start(() -> writeLoop(userId, client));

        // 三个回调,现在是注册回调,一旦发现成相应的事情后将告诉emitter跑一下remove
        emitter.onCompletion(() -> remove(userId, client));
        emitter.onTimeout(() -> remove(userId, client));
        emitter.onError(e -> remove(userId, client));
        // Poller 线程是 Tomcat  监视每一个emitter
        return emitter;
    }

    // 投递: 读锁遍历该用户的所有连接; 非阻塞 offer , 满了就丢
    public void push(long userId, Notification notification) {
        lock.readLock().lock();
        
        try {
            List<Client> list = clients.get(userId);
            if(list == null){
                return;
            }

            for(Client client : list){
                if(!client.pending.offer(notification)){
                    log.info("sse drop user={}: 缓冲满(靠list兜底))",userId);
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    // 每连接一个专职写线程：send 在真实 HTTP 上会阻塞，阻塞只卡这一条连接
    private void writeLoop(long userId, Client client) {
        try {
            while (true) {
                Notification n = client.pending.poll(KEEPALIVE_SECONDS, TimeUnit.SECONDS);
                if (n == null) {
                    client.emitter.send(SseEmitter.event().comment(" keepalive"));
                } else {
                    client.emitter.send(n); // 框架序列化成data:
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 摘除时被中断: 正常退出
        } catch (Exception e) {
            // 客户端已断开(写失败) : 自己将自己摘掉
            log.info("sse while failed user={} (摘除) : {}", userId, e.toString());
            // 兜底进行remove, 特别是send出现异常,上面的回调是容器的更快察觉
            remove(userId, client);
        }
    }

    private void remove(long userId, Client client) {
        lock.writeLock().lock();
        try {
            List<Client> list = clients.get(userId);
            if (list == null || !list.remove(client)) {
                return;
            }
            if (list.isEmpty()) {
                clients.remove(userId);
            }

            log.info("sse remove user={} clients={}", userId, list.size());
        } finally {
            lock.writeLock().unlock();
        }

        Thread writer = client.writer;
        if (writer != null) {
            writer.interrupt(); // 唤醒阻塞在poll的写线程退出
        }
    }
}

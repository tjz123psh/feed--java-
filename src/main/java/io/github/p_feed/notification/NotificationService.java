package io.github.p_feed.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import io.github.p_feed.account.dto.MessageResponse;
import io.github.p_feed.notification.dto.MarkReadRequest;
import io.github.p_feed.notification.dto.NotificationListResponse;
import io.github.p_feed.notification.dto.UnreadCountResponse;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;

    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    // 本人收件、最新 50 条；空为 []
    public NotificationListResponse list(long accountId) {
        List<Notification> notifications =
                notificationMapper.selectList(
                        new LambdaQueryWrapper<Notification>()
                                .eq(Notification::getRecipientId, accountId)
                                .orderByDesc(Notification::getCreatedAt)
                                .last("limit 50"));

        if (notifications == null) {
            notifications = List.of();
        }

        return new NotificationListResponse(notifications);
    }

    // 传 id 标单条（WHERE id AND recipient_id：只能标自己的）；省略 id / 整个 body = 标全部
    public MessageResponse markRead(long accountId, MarkReadRequest req) {
        LambdaUpdateWrapper<Notification> update =
                new LambdaUpdateWrapper<Notification>()
                        .set(Notification::getIsRead, true)
                        .eq(Notification::getRecipientId, accountId);

        if (req != null && req.id() != null) {
            update.eq(Notification::getId, req.id());
        }

        notificationMapper.update(null, update);
        return new MessageResponse("ok");
    }

    // 未读取: 本人收件且未读
    public UnreadCountResponse unreadCount(long accountId){
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>().eq(Notification::getRecipientId, accountId).eq(Notification::getIsRead, false));
        return new UnreadCountResponse(count == null ? 0 : count);
    }
}

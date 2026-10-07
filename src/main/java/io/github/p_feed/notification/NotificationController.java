package io.github.p_feed.notification;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.p_feed.account.dto.MessageResponse;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.notification.dto.MarkReadRequest;
import io.github.p_feed.notification.dto.NotificationListResponse;
import io.github.p_feed.notification.dto.UnreadCountResponse;

@RestController
@RequestMapping("/notification")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/list")
    public NotificationListResponse list(HttpServletRequest request) {
        return notificationService.list(accountId(request));
    }

    @PostMapping("/markRead")
    public MessageResponse markRead(
            HttpServletRequest request, @RequestBody(required = false) MarkReadRequest body) {
        return notificationService.markRead(accountId(request), body);
    }

    @PostMapping("/unreadCount")
    public UnreadCountResponse unreadCount(HttpServletRequest request) {
        return notificationService.unreadCount(accountId(request));
    }

    private long accountId(HttpServletRequest request) {
        Long accountId = (Long) request.getAttribute("accountID");
        if (accountId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.INVALID_ACCOUNT);
        }

        return accountId;
    }
}

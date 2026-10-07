package io.github.p_feed.message;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.message.dto.ListMessagesRequest;
import io.github.p_feed.message.dto.MessageListResponse;
import io.github.p_feed.message.dto.SendMessageRequest;

@RestController
@RequestMapping("/message")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping("/send")
    public Message send(HttpServletRequest request, @RequestBody SendMessageRequest body) {
        return messageService.send(accountId(request), body);
    }


    @PostMapping("/list")
        public MessageListResponse list(HttpServletRequest request, @RequestBody ListMessagesRequest body) {
        return messageService.list(accountId(request), body);
        }
        

    private long accountId(HttpServletRequest request) {
        Long accountId = (Long) request.getAttribute("accountID");

        if (accountId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, Errors.ACCOUNT_ID_NOT_FOUND);
        }
        return accountId;
    }
}

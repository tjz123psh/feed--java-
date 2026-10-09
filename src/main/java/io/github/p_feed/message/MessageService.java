package io.github.p_feed.message;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.message.dto.ListMessagesRequest;
import io.github.p_feed.message.dto.MessageListResponse;
import io.github.p_feed.message.dto.SendMessageRequest;

@Service
public class MessageService {

    private static final int MESSAGESLIMIT = 50;
    private final MessageMapper messageMapper;

    public MessageService(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    // 发私信：to_id=0 或 content trim 后为空 → 400；from_id 只信登录态；content trim 后落库
    public Message send(long fromId, SendMessageRequest req){
        String content = req.content() == null ? "" : req.content().trim();
        if(req.toId() == null || req.toId() == 0L || content.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.TO_ID_AND_CONTENT_ARE_REQUIRED);
        }

        Message message = new Message();
        message.setFromId(fromId);
        message.setToId(req.toId());
        message.setContent(content);
        message.setIsRead(false);
        message.setCreatedAt(OffsetDateTime.now());
        messageMapper.insert(message);
        return message;
    }
   // 会话：最新 50 条；空会话兜底成 []
    public MessageListResponse list(long accountId, ListMessagesRequest req){
        if(req.peerId() == null || req.peerId() == 0L){
            throw new ApiException(HttpStatus.BAD_REQUEST, Errors.PEER_ID_IS_REQUIRED);
        }

        List<Message> messages = messageMapper.listConveration(accountId, req.peerId(), MESSAGESLIMIT);
        if(messages == null){
            messages = List.of();
        }

        return new MessageListResponse(messages);
    }
    
}
